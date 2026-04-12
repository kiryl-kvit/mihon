package eu.kanade.tachiyomi.ui.video

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.util.formattedMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.GetVideoCategories
import tachiyomi.domain.category.interactor.SetVideoCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.video.interactor.SyncVideoWithSource
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoPlaybackState
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.model.VideoTitleUpdate
import tachiyomi.domain.video.repository.VideoEpisodeRepository
import tachiyomi.domain.video.repository.VideoPlaybackStateRepository
import tachiyomi.domain.video.repository.VideoRepository
import tachiyomi.domain.source.service.VideoSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

class VideoScreenModel(
    private val context: Context,
    private val videoId: Long,
    private val videoRepository: VideoRepository = Injekt.get(),
    private val videoEpisodeRepository: VideoEpisodeRepository = Injekt.get(),
    private val videoPlaybackStateRepository: VideoPlaybackStateRepository = Injekt.get(),
    private val videoSourceManager: VideoSourceManager = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getVideoCategories: GetVideoCategories = Injekt.get(),
    private val setVideoCategories: SetVideoCategories = Injekt.get(),
    private val syncVideoWithSource: SyncVideoWithSource = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<VideoScreenModel.State>(State.Loading) {

    private val successState: State.Success?
        get() = state.value as? State.Success

    init {
        observeVideo()
        refresh(initial = true)
    }

    private fun observeVideo() {
        screenModelScope.launchIO {
            combine(
                videoRepository.getVideoByIdAsFlow(videoId),
                videoEpisodeRepository.getEpisodesByVideoIdAsFlow(videoId),
                videoPlaybackStateRepository.getByVideoIdAsFlow(videoId),
                getVideoCategories.subscribe(videoId),
            ) { video, episodes, playbackStates, categories ->
                val currentSuccess = successState
                val sortedEpisodes = episodes.sortedBy { it.sourceOrder }
                val playbackStateByEpisodeId = playbackStates.associateBy { it.episodeId }
                State.Success(
                    video = video,
                    sourceName = videoSourceManager.get(video.source)?.name,
                    episodes = sortedEpisodes.toImmutableList(),
                    playbackStateByEpisodeId = playbackStateByEpisodeId,
                    primaryEpisodeId = selectPrimaryEpisodeId(sortedEpisodes, playbackStateByEpisodeId),
                    categories = categories.filterNot { it.isSystemCategory }.toImmutableList(),
                    isRefreshing = currentSuccess?.isRefreshing ?: false,
                    dialog = currentSuccess?.dialog,
                )
            }
                .catch { e ->
                    logcat(LogPriority.ERROR, e)
                    mutableState.value = State.Error(with(context) { e.formattedMessage })
                }
                .collectLatest { mutableState.value = it }
        }
    }

    fun refresh(initial: Boolean = false) {
        screenModelScope.launchIO {
            val currentVideo = successState?.video ?: runCatching {
                videoRepository.getVideoById(videoId)
            }.getOrElse {
                mutableState.value = State.Error(with(context) { it.formattedMessage })
                return@launchIO
            }

            if (videoSourceManager.get(currentVideo.source) == null) {
                return@launchIO
            }

            setRefreshing(true)
            try {
                syncVideoWithSource(currentVideo)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
                if (!initial || successState != null) {
                    screenModelScope.launch {
                        snackbarHostState.showSnackbar(with(context) { e.formattedMessage })
                    }
                }
            } finally {
                setRefreshing(false)
            }
        }
    }

    fun toggleFavorite() {
        val currentVideo = successState?.video ?: return
        screenModelScope.launchIO {
            val favorite = !currentVideo.favorite
            val updated = videoRepository.update(
                VideoTitleUpdate(
                    id = currentVideo.id,
                    favorite = favorite,
                    dateAdded = when (favorite) {
                        true -> Instant.now().toEpochMilli()
                        false -> 0L
                    },
                ),
            )
            if (!updated) {
                screenModelScope.launch {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.unknown_error))
                }
            }
        }
    }

    private fun selectPrimaryEpisodeId(
        episodes: List<VideoEpisode>,
        playbackStateByEpisodeId: Map<Long, VideoPlaybackState>,
    ): Long? {
        val inProgressEpisode = episodes
            .asSequence()
            .mapNotNull { episode ->
                val playbackState = playbackStateByEpisodeId[episode.id] ?: return@mapNotNull null
                if (playbackState.completed || playbackState.positionMs <= 0L) return@mapNotNull null
                episode to playbackState
            }
            .maxByOrNull { (_, playbackState) -> playbackState.lastWatchedAt }
            ?.first
        if (inProgressEpisode != null) {
            return inProgressEpisode.id
        }

        return episodes.firstOrNull { !it.completed }?.id ?: episodes.firstOrNull()?.id
    }

    fun showChangeCategoryDialog() {
        val currentVideo = successState?.video ?: return
        if (!currentVideo.favorite) return

        screenModelScope.launchIO {
            val selectedCategoryIds = getVideoCategories.await(currentVideo.id)
                .filterNot { it.isSystemCategory }
                .map { it.id }
                .toSet()
            val availableCategories = getCategories.await()
                .filterNot { it.isSystemCategory }
                .mapAsCheckboxState { it.id in selectedCategoryIds }
                .toImmutableList()

            mutableState.update { currentState ->
                val success = currentState as? State.Success ?: return@update currentState
                success.copy(dialog = Dialog.ChangeCategory(availableCategories))
            }
        }
    }

    fun setCategories(categoryIds: List<Long>) {
        val currentVideo = successState?.video ?: return
        screenModelScope.launchIO {
            setVideoCategories.await(currentVideo.id, categoryIds)
            dismissDialog()
        }
    }

    fun dismissDialog() {
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(dialog = null)
        }
    }

    private fun setRefreshing(isRefreshing: Boolean) {
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(isRefreshing = isRefreshing)
        }
    }

    sealed interface Dialog {
        data class ChangeCategory(
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
    }

    sealed interface State {
        data object Loading : State

        data class Error(val message: String) : State

        @Immutable
        data class Success(
            val video: VideoTitle,
            val sourceName: String?,
            val episodes: ImmutableList<VideoEpisode>,
            val playbackStateByEpisodeId: Map<Long, VideoPlaybackState>,
            val primaryEpisodeId: Long?,
            val categories: ImmutableList<Category>,
            val isRefreshing: Boolean,
            val dialog: Dialog? = null,
        ) : State {
            val sourceAvailable: Boolean = sourceName != null

            val primaryEpisode: VideoEpisode?
                get() = episodes.firstOrNull { it.id == primaryEpisodeId }
        }
    }
}
