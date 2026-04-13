package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.AnimeWebViewSource
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
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

class AnimeScreenModel(
    private val context: Context,
    private val animeId: Long,
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val syncAnimeWithSource: SyncAnimeWithSource = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<AnimeScreenModel.State>(State.Loading) {

    val webViewSource: AnimeWebViewSource?
        get() = successState?.anime?.let { animeSourceManager.get(it.source) as? AnimeWebViewSource }

    private val successState: State.Success?
        get() = state.value as? State.Success

    init {
        observeAnime()
        refresh(initial = true)
    }

    private fun observeAnime() {
        screenModelScope.launchIO {
            combine(
                animeRepository.getAnimeByIdAsFlow(animeId),
                animeEpisodeRepository.getEpisodesByAnimeIdAsFlow(animeId),
                animePlaybackStateRepository.getByAnimeIdAsFlow(animeId),
                getAnimeCategories.subscribe(animeId),
            ) { anime, episodes, playbackStates, categories ->
                val currentSuccess = successState
                val sortedEpisodes = episodes.sortedBy { it.sourceOrder }
                val playbackStateByEpisodeId = playbackStates.associateBy { it.episodeId }
                State.Success(
                    anime = anime,
                    sourceName = animeSourceManager.get(anime.source)?.name,
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
            val currentAnime = successState?.anime ?: runCatching {
                animeRepository.getAnimeById(animeId)
            }.getOrElse {
                mutableState.value = State.Error(with(context) { it.formattedMessage })
                return@launchIO
            }

            if (animeSourceManager.get(currentAnime.source) == null) {
                return@launchIO
            }

            setRefreshing(true)
            try {
                syncAnimeWithSource(currentAnime)
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
        val currentAnime = successState?.anime ?: return
        screenModelScope.launchIO {
            val favorite = !currentAnime.favorite
            val updated = animeRepository.update(
                AnimeTitleUpdate(
                    id = currentAnime.id,
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
        episodes: List<AnimeEpisode>,
        playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
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
        val currentAnime = successState?.anime ?: return
        if (!currentAnime.favorite) return

        screenModelScope.launchIO {
            val selectedCategoryIds = getAnimeCategories.await(currentAnime.id)
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
        val currentAnime = successState?.anime ?: return
        screenModelScope.launchIO {
            setAnimeCategories.await(currentAnime.id, categoryIds)
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
            val anime: AnimeTitle,
            val sourceName: String?,
            val episodes: ImmutableList<AnimeEpisode>,
            val playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
            val primaryEpisodeId: Long?,
            val categories: ImmutableList<Category>,
            val isRefreshing: Boolean,
            val dialog: Dialog? = null,
        ) : State {
            val sourceAvailable: Boolean = sourceName != null

            val primaryEpisode: AnimeEpisode?
                get() = episodes.firstOrNull { it.id == primaryEpisodeId }
        }
    }
}
