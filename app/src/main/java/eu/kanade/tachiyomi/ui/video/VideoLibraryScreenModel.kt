package eu.kanade.tachiyomi.ui.video

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.video.model.toMangaCover
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.VideoSourceManager
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoHistoryWithRelations
import tachiyomi.domain.video.model.VideoPlaybackState
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.repository.VideoEpisodeRepository
import tachiyomi.domain.video.repository.VideoHistoryRepository
import tachiyomi.domain.video.repository.VideoPlaybackStateRepository
import tachiyomi.domain.video.repository.VideoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VideoLibraryScreenModel(
    private val videoRepository: VideoRepository = Injekt.get(),
    private val videoEpisodeRepository: VideoEpisodeRepository = Injekt.get(),
    private val videoPlaybackStateRepository: VideoPlaybackStateRepository = Injekt.get(),
    private val videoHistoryRepository: VideoHistoryRepository = Injekt.get(),
    private val videoSourceManager: VideoSourceManager = Injekt.get(),
) : StateScreenModel<VideoLibraryScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launchIO {
            combine(
                videoRepository.getFavoritesAsFlow(),
                videoHistoryRepository.getLastHistoryAsFlow(),
            ) { favorites, lastHistory ->
                favorites to lastHistory
            }
                .flatMapLatest { (favorites, lastHistory) ->
                    val videoIds = favorites.map { it.id }
                    if (videoIds.isEmpty()) {
                        return@flatMapLatest flowOf(
                            State.Success(
                                videos = emptyList<VideoLibraryItem>().toImmutableList(),
                                continueWatching = lastHistory?.toContinueWatchingItem(),
                            ),
                        )
                    }

                    combine(
                        videoEpisodeRepository.getEpisodesByVideoIdsAsFlow(videoIds),
                        combine(videoIds.map(videoPlaybackStateRepository::getByVideoIdAsFlow)) { playbackLists ->
                            playbackLists.flatMap { it }
                        },
                    ) { episodes, playbackStates ->
                        buildState(
                            favorites = favorites,
                            lastHistory = lastHistory,
                            episodes = episodes,
                            playbackStates = playbackStates,
                        )
                    }
                }
                .catch { e ->
                    logcat(LogPriority.ERROR, e)
                    mutableState.value = State.Error(e.message ?: "Unknown error")
                }
                .collectLatest { mutableState.value = it }
        }
    }

    private fun buildState(
        favorites: List<VideoTitle>,
        lastHistory: VideoHistoryWithRelations?,
        episodes: List<VideoEpisode>,
        playbackStates: List<VideoPlaybackState>,
    ): State.Success {
        val episodesByVideoId = episodes.groupBy { it.videoId }
        val playbackStateByEpisodeId = playbackStates.associateBy { it.episodeId }

        return State.Success(
            videos = favorites
                .sortedWith(compareByDescending<VideoTitle> { it.favoriteModifiedAt ?: it.dateAdded }
                    .thenBy { it.displayTitle.lowercase() })
                .map { video ->
                    val videoEpisodes = episodesByVideoId[video.id].orEmpty()
                    val unwatchedCount = videoEpisodes.count { !it.completed }
                    val inProgressPlayback = videoEpisodes
                        .asSequence()
                        .mapNotNull { episode -> playbackStateByEpisodeId[episode.id] }
                        .filter { !it.completed && it.positionMs > 0L && it.durationMs > 0L }
                        .maxByOrNull { it.lastWatchedAt }
                    val primaryEpisode = inProgressPlayback
                        ?.let { playbackState ->
                            videoEpisodes.firstOrNull { it.id == playbackState.episodeId }
                        }
                        ?: videoEpisodes.firstOrNull { !it.completed }
                        ?: videoEpisodes.firstOrNull()

                    VideoLibraryItem(
                        videoId = video.id,
                        title = video.displayTitle,
                        coverData = video.toMangaCover(),
                        sourceName = videoSourceManager.get(video.source)?.name,
                        sourceId = video.source,
                        primaryEpisodeId = primaryEpisode?.id,
                        unwatchedCount = unwatchedCount,
                        hasInProgress = inProgressPlayback != null,
                        progressFraction = inProgressPlayback?.progressFraction(),
                    )
                }
                .toImmutableList(),
            continueWatching = lastHistory?.toContinueWatchingItem(),
        )
    }

    @Immutable
    data class VideoLibraryItem(
        val videoId: Long,
        val title: String,
        val coverData: tachiyomi.domain.manga.model.MangaCover,
        val sourceName: String?,
        val sourceId: Long,
        val primaryEpisodeId: Long?,
        val unwatchedCount: Int,
        val hasInProgress: Boolean,
        val progressFraction: Float?,
    )

    @Immutable
    data class ContinueWatchingItem(
        val videoId: Long,
        val episodeId: Long,
        val title: String,
        val episodeName: String,
        val coverData: tachiyomi.domain.manga.model.MangaCover,
        val watchedAt: Long,
    )

    sealed interface State {
        data object Loading : State

        data class Error(val message: String) : State

        @Immutable
        data class Success(
            val videos: ImmutableList<VideoLibraryItem>,
            val continueWatching: ContinueWatchingItem?,
        ) : State
    }
}

private fun VideoHistoryWithRelations.toContinueWatchingItem(): VideoLibraryScreenModel.ContinueWatchingItem {
    return VideoLibraryScreenModel.ContinueWatchingItem(
        videoId = videoId,
        episodeId = episodeId,
        title = title,
        episodeName = episodeName,
        coverData = coverData,
        watchedAt = watchedAt?.time ?: System.currentTimeMillis(),
    )
}

private fun VideoPlaybackState.progressFraction(): Float {
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
