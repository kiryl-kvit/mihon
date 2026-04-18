package eu.kanade.tachiyomi.ui.video.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackOption
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.PlayerQualityMode
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.service.sortedForReading
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VideoPlayerViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val resolveVideoStream: VideoStreamResolver = Injekt.get<ResolveVideoStream>(),
    private val animePlaybackPreferencesRepository: AnimePlaybackPreferencesRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val getAnimeWithEpisodes: GetAnimeWithEpisodes? = runCatching {
        Injekt.get<GetAnimeWithEpisodes>()
    }.getOrNull(),
    private val videoPlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val videoHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val resolveDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val mutableState = MutableStateFlow<State>(State.Loading)
    val state = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()

    private var initialized = false
    private var playbackSession: VideoPlaybackSession? = null
    private val persistMutex = Mutex()
    private var visibleAnimeId: Long = INVALID_ID
    private var ownerAnimeId: Long = INVALID_ID
    private var episodeId: Long = INVALID_ID
    private var bypassMerge: Boolean = false
    private var applySelectionJob: Job? = null
    private var previewSelectionJob: Job? = null
    private val selectionResultCache = LinkedHashMap<SelectionCacheKey, ResolveVideoStream.Result.Success>()

    fun init(
        animeId: Long,
        episodeId: Long,
        ownerAnimeId: Long = animeId,
        bypassMerge: Boolean = false,
    ) {
        if (initialized) return
        initialized = true
        this.visibleAnimeId = animeId
        this.ownerAnimeId = ownerAnimeId
        this.episodeId = episodeId
        this.bypassMerge = bypassMerge
        savedState[VIDEO_ID_KEY] = animeId
        savedState[OWNER_VIDEO_ID_KEY] = ownerAnimeId
        savedState[EPISODE_ID_KEY] = episodeId
        savedState[BYPASS_MERGE_KEY] = bypassMerge

        viewModelScope.launch {
            resolvePlayback(initial = true)
        }
    }

    fun applySourceSelection(selection: VideoPlaybackSelection) {
        val current = mutableState.value as? State.Ready ?: return
        previewSelectionJob?.cancel()
        applySelectionJob?.cancel()
        applySelectionJob = viewModelScope.launch {
            val preservedSubtitle = current.playback.currentSubtitle
            persistPlaybackPreferences(
                animeId = current.ownerAnimeId,
                sourceSelection = selection,
                adaptiveQuality = current.playback.currentAdaptiveQuality,
            )
            if (!isActive) return@launch
            val cachedResult = cachedSelectionResult(current.episodeId, selection)
            if (cachedResult != null) {
                mutableState.value = buildReadyState(
                    result = cachedResult,
                    preservePositionMs = current.resumePositionMs,
                    preview = VideoPlaybackPreviewState(),
                    isSourceSwitching = false,
                    requestedSubtitle = preservedSubtitle,
                )
            } else {
                resolvePlayback(
                    selection = selection,
                    preservePositionMs = current.resumePositionMs,
                    showLoading = false,
                    requestedSubtitle = preservedSubtitle,
                )
            }
        }
    }

    fun previewSourceSelection(selection: VideoPlaybackSelection) {
        val current = mutableState.value as? State.Ready ?: return
        if (selection.dubKey == current.playback.sourceSelection.dubKey) {
            previewSelectionJob?.cancel()
            mutableState.value = current.copy(
                playback = current.playback.copy(preview = VideoPlaybackPreviewState()),
            )
            return
        }

        val currentPreview = current.playback.preview
        if (currentPreview.selection == selection && currentPreview.isLoading) return

        val cachedPreview = cachedSelectionResult(current.episodeId, selection)
        if (cachedPreview != null) {
            mutableState.value = current.copy(
                playback = current.playback.copy(
                    preview = VideoPlaybackPreviewState(
                        selection = selection,
                        playbackData = cachedPreview.playbackData,
                        subtitles = cachedPreview.subtitles,
                        isLoading = false,
                    ),
                ),
            )
            return
        }

        previewSelectionJob?.cancel()
        mutableState.value = current.copy(
            playback = current.playback.copy(
                preview = VideoPlaybackPreviewState(
                    selection = selection,
                    isLoading = true,
                ),
            ),
        )

        previewSelectionJob = viewModelScope.launch {
            val result = try {
                withContext(resolveDispatcher) {
                    resolveVideoStream(
                        animeId = current.visibleAnimeId,
                        episodeId = current.episodeId,
                        ownerAnimeId = current.ownerAnimeId,
                        selection = selection,
                    )
                }
            } catch (_: CancellationException) {
                return@launch
            }

            val latestState = mutableState.value as? State.Ready ?: return@launch
            val latestPreview = latestState.playback.preview
            if (latestPreview.selection != selection) return@launch

            when (result) {
                is ResolveVideoStream.Result.Success -> {
                    cacheSelectionResult(current.episodeId, selection, result)
                    mutableState.value = latestState.copy(
                        playback = latestState.playback.copy(
                            preview = VideoPlaybackPreviewState(
                                selection = selection,
                                playbackData = result.playbackData,
                                subtitles = result.subtitles,
                                isLoading = false,
                            ),
                        ),
                    )
                }
                is ResolveVideoStream.Result.Error -> {
                    mutableEvents.tryEmit(Event.ShowPreviewMessage(result.reason.toMessage()))
                    mutableState.value = latestState.copy(
                        playback = latestState.playback.copy(
                            preview = VideoPlaybackPreviewState(
                                selection = selection,
                                isLoading = false,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun selectAdaptiveQuality(preference: VideoAdaptiveQualityPreference) {
        val current = mutableState.value as? State.Ready ?: return
        mutableState.value = current.copy(
            playback = current.playback.copy(currentAdaptiveQuality = preference),
        )
        viewModelScope.launch {
            persistPlaybackPreferences(
                animeId = current.ownerAnimeId,
                sourceSelection = current.playback.persistedSourceSelection,
                adaptiveQuality = preference,
            )
        }
    }

    fun updateSubtitleAppearance(appearance: VideoSubtitleAppearance) {
        val current = mutableState.value as? State.Ready ?: return
        val normalizedAppearance = appearance.normalized()
        if (current.playback.subtitleAppearance == normalizedAppearance) return
        mutableState.value = current.copy(
            playback = current.playback.copy(subtitleAppearance = normalizedAppearance),
        )
        viewModelScope.launch {
            persistPlaybackPreferences(
                animeId = current.ownerAnimeId,
                sourceSelection = current.playback.persistedSourceSelection,
                adaptiveQuality = current.playback.currentAdaptiveQuality,
                subtitleAppearance = normalizedAppearance,
            )
        }
    }

    fun updateAdaptiveQualities(options: List<VideoAdaptiveQualityOption>) {
        val current = mutableState.value as? State.Ready ?: return
        mutableState.value = current.copy(
            playback = current.playback.copy(adaptiveQualities = options),
        )
    }

    fun updateSubtitleOptions(options: List<VideoPlayerSubtitleOption>) {
        val current = mutableState.value as? State.Ready ?: return
        if (current.playback.subtitleOptions == options) return
        mutableState.value = current.copy(
            playback = current.playback.copy(subtitleOptions = options),
        )
    }

    fun selectSubtitle(selection: VideoPlayerSubtitleSelection) {
        val current = mutableState.value as? State.Ready ?: return
        if (current.playback.currentSubtitle == selection) return
        mutableState.value = current.copy(
            playback = current.playback.copy(currentSubtitle = selection),
        )
    }

    fun persistPlayback(positionMs: Long, durationMs: Long) {
        val current = mutableState.value as? State.Ready ?: return
        val safePositionMs = positionMs.coerceAtLeast(0L)
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        mutableState.value = current.copy(resumePositionMs = safePositionMs)
        val session = playbackSession ?: VideoPlaybackSession(current.episodeId).also { playbackSession = it }
        val snapshot = session.snapshot(positionMs = safePositionMs, durationMs = safeDurationMs)

        viewModelScope.launch(persistenceDispatcher) {
            withContext(NonCancellable) {
                persistMutex.withLock {
                    videoPlaybackStateRepository.upsertAndSyncEpisodeState(snapshot.playbackState)
                    snapshot.historyUpdate?.let { historyUpdate ->
                        videoHistoryRepository.upsertHistory(historyUpdate)
                    }
                }
            }
        }
    }

    fun resetPlaybackBaseline(positionMs: Long) {
        playbackSession?.restore(positionMs)
        val current = mutableState.value as? State.Ready ?: return
        mutableState.value = current.copy(resumePositionMs = positionMs.coerceAtLeast(0L))
    }

    fun playPreviousEpisode() {
        val current = mutableState.value as? State.Ready ?: return
        val previousEpisodeId = current.previousEpisodeId ?: return
        viewModelScope.launch {
            playEpisode(previousEpisodeId)
        }
    }

    fun playNextEpisode() {
        val current = mutableState.value as? State.Ready ?: return
        val nextEpisodeId = current.nextEpisodeId ?: return
        viewModelScope.launch {
            playEpisode(nextEpisodeId)
        }
    }

    private suspend fun resolvePlayback(
        selection: VideoPlaybackSelection? = null,
        preservePositionMs: Long? = null,
        initial: Boolean = false,
        showLoading: Boolean = true,
        requestedSubtitle: VideoPlayerSubtitleSelection? = null,
    ) {
        val previousReady = mutableState.value as? State.Ready
        previewSelectionJob?.cancel()
        mutableState.value = if (showLoading || previousReady == null) {
            State.Loading
        } else {
            previousReady.copy(isSourceSwitching = true)
        }

        mutableState.value = when (
            val result = withContext(resolveDispatcher) {
                resolveVideoStream(
                    animeId = visibleAnimeId,
                    episodeId = episodeId,
                    ownerAnimeId = ownerAnimeId,
                    selection = selection,
                )
            }
        ) {
            is ResolveVideoStream.Result.Success -> {
                cacheSelectionResult(result.episode.id, result.playbackData.selection, result)
                buildReadyState(
                    result = result,
                    preservePositionMs = preservePositionMs,
                    preview = VideoPlaybackPreviewState(),
                    isSourceSwitching = false,
                    requestedSubtitle = requestedSubtitle,
                )
            }
            is ResolveVideoStream.Result.Error -> {
                if (!showLoading && previousReady != null) {
                    mutableEvents.tryEmit(Event.ShowMessage(result.reason.toMessage()))
                    previousReady.copy(
                        playback = previousReady.playback.copy(preview = VideoPlaybackPreviewState()),
                        isSourceSwitching = false,
                    )
                } else {
                    State.Error(result.reason.toMessage())
                }
            }
        }

        val current = mutableState.value
        if (current is State.Ready) {
            val session = playbackSession?.takeIf { !initial }
                ?: VideoPlaybackSession(current.episodeId)
            session.restore(current.resumePositionMs)
            playbackSession = session
        }
    }

    private suspend fun resolveEpisodeNavigation(
        visibleAnimeId: Long,
        episodeId: Long,
    ): EpisodeNavigation {
        val mergedAnimeId = if (bypassMerge) ownerAnimeId else visibleAnimeId
        val sortedEpisodes = getAnimeWithEpisodes?.let { getAnimeWithEpisodes ->
            val anime = getAnimeWithEpisodes.awaitAnime(mergedAnimeId)
            val episodes = getAnimeWithEpisodes.awaitEpisodes(id = mergedAnimeId, bypassMerge = bypassMerge)
            episodes.sortedForReading(anime)
        } ?: animeEpisodeRepository.getEpisodesByAnimeId(mergedAnimeId)
            .sortedBy(AnimeEpisode::sourceOrder)
        val currentIndex = sortedEpisodes.indexOfFirst { it.id == episodeId }
        if (currentIndex == -1) return EpisodeNavigation()

        return EpisodeNavigation(
            previousEpisodeId = sortedEpisodes.getOrNull(currentIndex - 1)?.id,
            nextEpisodeId = sortedEpisodes.getOrNull(currentIndex + 1)?.id,
        )
    }

    private suspend fun playEpisode(targetEpisodeId: Long) {
        mutableState.value as? State.Ready ?: return
        applySelectionJob?.cancel()
        previewSelectionJob?.cancel()
        clearSelectionResultCache()
        ownerAnimeId = animeEpisodeRepository.getEpisodeById(targetEpisodeId)?.animeId ?: ownerAnimeId
        savedState[OWNER_VIDEO_ID_KEY] = ownerAnimeId
        savedState[EPISODE_ID_KEY] = targetEpisodeId
        episodeId = targetEpisodeId
        playbackSession = null
        resolvePlayback(initial = true)
    }

    private suspend fun buildReadyState(
        result: ResolveVideoStream.Result.Success,
        preservePositionMs: Long?,
        preview: VideoPlaybackPreviewState,
        isSourceSwitching: Boolean,
        requestedSubtitle: VideoPlayerSubtitleSelection? = null,
    ): State.Ready {
        val resumePositionMs = preservePositionMs
            ?: videoPlaybackStateRepository.getByEpisodeId(result.episode.id)?.positionMs
            ?: 0L
        val navigation = resolveEpisodeNavigation(
            visibleAnimeId = result.visibleAnime.id,
            episodeId = result.episode.id,
        )
        val playback = buildPlaybackUiState(result.playbackData, result.stream, result.savedPreferences)
            .copy(
                subtitles = result.subtitles,
                currentSubtitle = resolveSourceSubtitleSelection(requestedSubtitle, result.subtitles),
            )
            .copy(preview = preview)
        return State.Ready(
            visibleAnimeId = result.visibleAnime.id,
            ownerAnimeId = result.ownerAnime.id,
            episodeId = result.episode.id,
            previousEpisodeId = navigation.previousEpisodeId,
            nextEpisodeId = navigation.nextEpisodeId,
            videoTitle = result.visibleAnime.displayTitle,
            episodeName = result.episode.name,
            streamLabel = playback.currentStreamLabel,
            streamUrl = playback.currentStream.request.url,
            stream = playback.currentStream,
            playback = playback,
            resumePositionMs = resumePositionMs,
            isSourceSwitching = isSourceSwitching,
        )
    }

    private fun buildPlaybackUiState(
        playbackData: VideoPlaybackData,
        currentStream: VideoStream,
        savedPreferences: AnimePlaybackPreferences,
    ): VideoPlaybackUiState {
        val streamOptions = playbackData.streams
            .filter { it.request.url.isNotBlank() }
            .map { stream ->
                VideoPlaybackOption(
                    key = stream.key.ifBlank { stream.label.ifBlank { stream.request.url } },
                    label = stream.label.ifBlank { stream.request.url },
                )
            }

        return VideoPlaybackUiState(
            sourceSelection = playbackData.selection.copy(
                streamKey = currentStream.key.ifBlank { currentStream.label.ifBlank { currentStream.request.url } },
            ),
            preferredSourceQualityKey = savedPreferences.sourceQualityKey,
            currentStream = currentStream,
            subtitles = emptyList(),
            currentStreamLabel = currentStream.label.ifBlank { currentStream.request.url },
            streamOptions = streamOptions,
            playbackData = playbackData,
            currentAdaptiveQuality = savedPreferences.toAdaptiveQualityPreference(),
            subtitleAppearance = savedPreferences.toSubtitleAppearance(),
        )
    }

    private fun cachedSelectionResult(
        episodeId: Long,
        selection: VideoPlaybackSelection,
    ): ResolveVideoStream.Result.Success? {
        return selectionResultCache[SelectionCacheKey(episodeId = episodeId, selection = selection.normalized())]
    }

    private fun cacheSelectionResult(
        episodeId: Long,
        selection: VideoPlaybackSelection,
        result: ResolveVideoStream.Result.Success,
    ) {
        selectionResultCache[SelectionCacheKey(episodeId = episodeId, selection = selection.normalized())] = result
        trimSelectionResultCache()
    }

    private fun trimSelectionResultCache() {
        while (selectionResultCache.size > SELECTION_CACHE_LIMIT) {
            val firstKey = selectionResultCache.entries.firstOrNull()?.key ?: break
            selectionResultCache.remove(firstKey)
        }
    }

    private fun clearSelectionResultCache() {
        selectionResultCache.clear()
    }

    private suspend fun persistPlaybackPreferences(
        animeId: Long,
        sourceSelection: VideoPlaybackSelection? = null,
        adaptiveQuality: VideoAdaptiveQualityPreference? = null,
        subtitleAppearance: VideoSubtitleAppearance? = null,
    ) {
        val existingPreferences = animePlaybackPreferencesRepository.getByAnimeId(animeId)
            ?: defaultPlaybackPreferences(animeId)
        val resolvedSourceSelection = sourceSelection ?: VideoPlaybackSelection(
            dubKey = existingPreferences.dubKey,
            streamKey = existingPreferences.streamKey,
            sourceQualityKey = existingPreferences.sourceQualityKey,
        )
        val resolvedAdaptiveQuality = adaptiveQuality ?: existingPreferences.toAdaptiveQualityPreference()
        val resolvedSubtitleAppearance = (subtitleAppearance ?: existingPreferences.toSubtitleAppearance()).normalized()
        animePlaybackPreferencesRepository.upsert(
            AnimePlaybackPreferences(
                animeId = animeId,
                dubKey = resolvedSourceSelection.dubKey,
                streamKey = resolvedSourceSelection.streamKey,
                sourceQualityKey = resolvedSourceSelection.sourceQualityKey,
                playerQualityMode = resolvedAdaptiveQuality.toPlayerQualityMode(),
                playerQualityHeight = resolvedAdaptiveQuality.heightOrNull(),
                subtitleOffsetX = resolvedSubtitleAppearance.toPersistedOffsetX(),
                subtitleOffsetY = resolvedSubtitleAppearance.toPersistedOffsetY(),
                subtitleTextSize = resolvedSubtitleAppearance.toPersistedTextSize(),
                subtitleTextColor = resolvedSubtitleAppearance.toPersistedTextColor(),
                subtitleBackgroundColor = resolvedSubtitleAppearance.toPersistedBackgroundColor(),
                subtitleBackgroundOpacity = resolvedSubtitleAppearance.toPersistedBackgroundOpacity(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun defaultPlaybackPreferences(animeId: Long): AnimePlaybackPreferences {
        return AnimePlaybackPreferences(
            animeId = animeId,
            dubKey = null,
            streamKey = null,
            sourceQualityKey = null,
            playerQualityMode = PlayerQualityMode.AUTO,
            playerQualityHeight = null,
            updatedAt = 0L,
        )
    }

    sealed interface State {
        data object Loading : State

        data class Ready(
            val visibleAnimeId: Long,
            val ownerAnimeId: Long,
            val episodeId: Long,
            val previousEpisodeId: Long?,
            val nextEpisodeId: Long?,
            val videoTitle: String,
            val episodeName: String,
            val streamLabel: String,
            val streamUrl: String,
            val stream: VideoStream,
            val playback: VideoPlaybackUiState,
            val resumePositionMs: Long,
            val isSourceSwitching: Boolean = false,
        ) : State

        data class Error(val message: String) : State
    }

    sealed interface Event {
        data class ShowMessage(val message: String) : Event

        data class ShowPreviewMessage(val message: String) : Event
    }

    private fun ResolveVideoStream.Reason.toMessage(): String {
        return when (this) {
            ResolveVideoStream.Reason.VideoNotFound -> "Video not found"
            ResolveVideoStream.Reason.EpisodeNotFound -> "Episode not found"
            ResolveVideoStream.Reason.EpisodeMismatch -> "Episode does not belong to the selected video"
            ResolveVideoStream.Reason.SourceLoadTimeout -> "Video source took too long to load"
            ResolveVideoStream.Reason.SourceNotFound -> "Video source not available"
            ResolveVideoStream.Reason.NoStreams -> "No playable streams returned"
            ResolveVideoStream.Reason.StreamFetchTimeout -> "Timed out while resolving streams"
            is ResolveVideoStream.Reason.StreamFetchFailed -> listOfNotNull(
                cause::class.simpleName,
                cause.message,
            ).joinToString(": ").ifBlank { "Failed to resolve streams" }
        }
    }

    companion object {
        private const val VIDEO_ID_KEY = "video_id"
        private const val OWNER_VIDEO_ID_KEY = "owner_video_id"
        private const val EPISODE_ID_KEY = "episode_id"
        private const val BYPASS_MERGE_KEY = "bypass_merge"
        private const val INVALID_ID = -1L
        private const val SELECTION_CACHE_LIMIT = 12
    }
}

private data class SelectionCacheKey(
    val episodeId: Long,
    val selection: VideoPlaybackSelection,
)

private fun VideoPlaybackSelection.normalized(): VideoPlaybackSelection {
    return copy(streamKey = null)
}

private data class EpisodeNavigation(
    val previousEpisodeId: Long? = null,
    val nextEpisodeId: Long? = null,
)
