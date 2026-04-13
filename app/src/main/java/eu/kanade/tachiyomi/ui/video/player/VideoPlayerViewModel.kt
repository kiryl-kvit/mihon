package eu.kanade.tachiyomi.ui.video.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.source.model.VideoStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VideoPlayerViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val resolveVideoStream: VideoStreamResolver = Injekt.get<ResolveVideoStream>(),
    private val videoPlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val videoHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val mutableState = MutableStateFlow<State>(State.Loading)
    val state = mutableState.asStateFlow()

    private var initialized = false
    private var playbackSession: VideoPlaybackSession? = null
    private val persistMutex = Mutex()

    fun init(animeId: Long, episodeId: Long) {
        if (initialized) return
        initialized = true
        savedState[VIDEO_ID_KEY] = animeId
        savedState[EPISODE_ID_KEY] = episodeId

        viewModelScope.launch {
            mutableState.value = State.Loading
            mutableState.value = when (val result = resolveVideoStream(animeId, episodeId)) {
                is ResolveVideoStream.Result.Success -> State.Ready(
                    episodeId = result.episode.id,
                    videoTitle = result.video.displayTitle,
                    episodeName = result.episode.name,
                    streamLabel = result.stream.label.ifBlank { result.stream.request.url },
                    streamUrl = result.stream.request.url,
                    stream = result.stream,
                    resumePositionMs = videoPlaybackStateRepository.getByEpisodeId(result.episode.id)?.positionMs ?: 0L,
                )
                is ResolveVideoStream.Result.Error -> State.Error(result.reason.toMessage())
            }

            val current = mutableState.value
            if (current is State.Ready) {
                playbackSession = VideoPlaybackSession(current.episodeId).also {
                    it.restore(current.resumePositionMs)
                }
            }
        }
    }

    fun persistPlayback(positionMs: Long, durationMs: Long) {
        val current = mutableState.value as? State.Ready ?: return
        val session = playbackSession ?: VideoPlaybackSession(current.episodeId).also { playbackSession = it }
        val snapshot = session.snapshot(positionMs = positionMs, durationMs = durationMs)

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
    }

    sealed interface State {
        data object Loading : State

        data class Ready(
            val episodeId: Long,
            val videoTitle: String,
            val episodeName: String,
            val streamLabel: String,
            val streamUrl: String,
            val stream: VideoStream,
            val resumePositionMs: Long,
        ) : State

        data class Error(val message: String) : State
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
            is ResolveVideoStream.Reason.StreamFetchFailed -> cause.message ?: "Failed to resolve streams"
        }
    }

    companion object {
        private const val VIDEO_ID_KEY = "video_id"
        private const val EPISODE_ID_KEY = "episode_id"
    }
}
