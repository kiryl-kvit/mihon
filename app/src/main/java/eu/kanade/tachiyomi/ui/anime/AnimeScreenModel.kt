package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import eu.kanade.domain.anime.model.episodesFiltered
import eu.kanade.core.util.addOrRemove
import eu.kanade.tachiyomi.source.AnimeScheduleSource
import eu.kanade.presentation.updates.UpdatesSelectionState
import eu.kanade.tachiyomi.source.AnimeWebViewSource
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.util.formattedMessage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.util.lang.launchNonCancellable
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
import tachiyomi.domain.anime.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import eu.kanade.tachiyomi.source.model.SAnimeScheduleEpisode
import mihon.domain.anime.model.toSAnime

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
    private val setAnimeEpisodeFlags: SetAnimeEpisodeFlags = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val syncAnimeWithSource: SyncAnimeWithSource = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<AnimeScreenModel.State>(State.Loading) {

    private val selectionState = UpdatesSelectionState()
    private val selectedEpisodeIds = HashSet<Long>()

    val webViewSource: AnimeWebViewSource?
        get() = successState?.anime?.let { animeSourceManager.get(it.source) as? AnimeWebViewSource }

    val scheduleSource: AnimeScheduleSource?
        get() = successState?.anime?.let { animeSourceManager.get(it.source) as? AnimeScheduleSource }

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
                val playbackStateByEpisodeId = playbackStates.associateBy { it.episodeId }
                val displayedEpisodes = episodes
                    .filterEpisodes(anime, playbackStateByEpisodeId)
                    .sortEpisodes(anime)
                val source = animeSourceManager.get(anime.source)
                val hasScheduleSupport = source is AnimeScheduleSource
                val schedule = if (hasScheduleSupport) {
                    when (val currentSchedule = currentSuccess?.schedule) {
                        null,
                        ScheduleState.Unavailable,
                        -> ScheduleState.NotLoaded
                        else -> currentSchedule
                    }
                } else {
                    ScheduleState.Unavailable
                }
                State.Success(
                    anime = anime,
                    sourceName = animeSourceManager.get(anime.source)?.name,
                    episodes = displayedEpisodes.toImmutableList(),
                    playbackStateByEpisodeId = playbackStateByEpisodeId,
                    primaryEpisodeId = selectPrimaryEpisodeId(displayedEpisodes, playbackStateByEpisodeId),
                    selection = displayedEpisodes.asSequence()
                        .map(AnimeEpisode::id)
                        .filter(selectedEpisodeIds::contains)
                        .toSet()
                        .toImmutableSet(),
                    categories = categories.filterNot { it.isSystemCategory }.toImmutableList(),
                    hasScheduleSupport = hasScheduleSupport,
                    schedule = schedule,
                    isRefreshing = currentSuccess?.isRefreshing ?: false,
                    dialog = currentSuccess?.dialog,
                )
            }
                .catch { e ->
                    logcat(LogPriority.ERROR, e)
                    mutableState.value = State.Error(with(context) { e.formattedMessage })
                }
                .collectLatest {
                    mutableState.value = it
                    if (it is State.Success) {
                        if (!it.anime.favorite && it.anime.episodeFlags == 0L) {
                            setAnimeDefaultEpisodeFlags.await(it.anime)
                        }
                        prefetchScheduleIfNeeded(it)
                    }
                }
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
                if (!initial) {
                    loadSchedule(force = true)
                }
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
        if (currentAnime.favorite && successState?.isSelectionMode == true) {
            clearSelection()
        }
        screenModelScope.launchIO {
            val favorite = !currentAnime.favorite
            if (favorite) {
                setAnimeDefaultEpisodeFlags.await(currentAnime)
            }
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

    fun setUnwatchedFilter(state: tachiyomi.core.common.preference.TriState) {
        val anime = successState?.anime ?: return

        val flag = when (state) {
            tachiyomi.core.common.preference.TriState.DISABLED -> AnimeTitle.SHOW_ALL
            tachiyomi.core.common.preference.TriState.ENABLED_IS -> AnimeTitle.EPISODE_SHOW_UNWATCHED
            tachiyomi.core.common.preference.TriState.ENABLED_NOT -> AnimeTitle.EPISODE_SHOW_WATCHED
        }
        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetUnwatchedFilter(anime, flag)
        }
    }

    fun setStartedFilter(state: tachiyomi.core.common.preference.TriState) {
        val anime = successState?.anime ?: return

        val flag = when (state) {
            tachiyomi.core.common.preference.TriState.DISABLED -> AnimeTitle.SHOW_ALL
            tachiyomi.core.common.preference.TriState.ENABLED_IS -> AnimeTitle.EPISODE_SHOW_STARTED
            tachiyomi.core.common.preference.TriState.ENABLED_NOT -> AnimeTitle.EPISODE_SHOW_NOT_STARTED
        }
        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetStartedFilter(anime, flag)
        }
    }

    fun setDisplayMode(mode: Long) {
        val anime = successState?.anime ?: return

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetDisplayMode(anime, mode)
        }
    }

    fun setSorting(sort: Long) {
        val anime = successState?.anime ?: return

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetSortingModeOrFlipOrder(anime, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setEpisodeSettingsDefault(anime)
            if (applyToExisting) {
                animeRepository.getFavorites()
                    .forEach { favoriteAnime ->
                        setAnimeDefaultEpisodeFlags.await(favoriteAnime)
                    }
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.episode_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeDefaultEpisodeFlags.await(anime)
        }
    }

    fun toggleSelection(
        episode: AnimeEpisode,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            val selectedIndex = success.episodes.indexOfFirst { it.id == episode.id }
            if (selectedIndex < 0) return@update currentState

            val currentlySelected = episode.id in success.selection
            if (currentlySelected == selected) return@update currentState

            val mutableSelection = HashSet(success.selection)
            val firstSelection = mutableSelection.isEmpty()
            mutableSelection.addOrRemove(episode.id, selected)
            selectedEpisodeIds.addOrRemove(episode.id, selected)

            if (selected && fromLongPress) {
                selectionState.updateRangeSelection(selectedIndex, firstSelection).forEach { rangeIndex ->
                    val inbetweenEpisode = success.episodes[rangeIndex]
                    if (mutableSelection.add(inbetweenEpisode.id)) {
                        selectedEpisodeIds.add(inbetweenEpisode.id)
                    }
                }
            } else if (!fromLongPress) {
                selectionState.updateSelectionBounds(
                    selectedIndex = selectedIndex,
                    selected = selected,
                    firstSelectedIndex = success.episodes.indexOfFirst { it.id in mutableSelection },
                    lastSelectedIndex = success.episodes.indexOfLast { it.id in mutableSelection },
                )
            }

            success.copy(selection = mutableSelection.toImmutableSet())
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.episodes.forEach { selectedEpisodeIds.addOrRemove(it.id, selected) }
            val selection = if (selected) {
                success.episodes.map(AnimeEpisode::id).toSet().toImmutableSet()
            } else {
                emptySet<Long>().toImmutableSet()
            }
            success.copy(selection = selection)
        }

        selectionState.reset()
    }

    fun invertSelection() {
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            val inverted = success.episodes.mapNotNull { episode ->
                val nextSelected = episode.id !in success.selection
                selectedEpisodeIds.addOrRemove(episode.id, nextSelected)
                episode.id.takeIf { nextSelected }
            }.toSet().toImmutableSet()
            success.copy(selection = inverted)
        }

        selectionState.reset()
    }

    fun clearSelection() {
        selectedEpisodeIds.clear()
        selectionState.reset()
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(selection = emptySet<Long>().toImmutableSet())
        }
    }

    fun markSelectedEpisodesWatched(watched: Boolean) {
        val currentState = successState ?: return
        val selectedIds = currentState.selection
        if (selectedIds.isEmpty()) return

        screenModelScope.launchNonCancellable {
            val episodeUpdates = currentState.episodes
                .filter { episode ->
                    episode.id in selectedIds && when (watched) {
                        true -> !episode.completed || !episode.watched
                        false -> episode.completed || episode.watched
                    }
                }
                .map { episode ->
                    AnimeEpisodeUpdate(
                        id = episode.id,
                        watched = watched,
                        completed = watched,
                    )
                }
            if (episodeUpdates.isNotEmpty()) {
                animeEpisodeRepository.updateAll(episodeUpdates)
            }

            currentState.playbackStateByEpisodeId.values
                .filter { it.episodeId in selectedIds }
                .forEach { playbackState ->
                    animePlaybackStateRepository.upsert(
                        playbackState.copy(
                            positionMs = if (watched) playbackState.positionMs else 0L,
                            completed = watched,
                        ),
                    )
                }

            clearSelection()
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

    fun showEditDisplayNameDialog() {
        val currentAnime = successState?.anime ?: return
        if (!currentAnime.favorite) return

        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(dialog = Dialog.EditDisplayName(currentAnime.displayName.orEmpty()))
        }
    }

    fun showSettingsDialog() {
        val currentAnime = successState?.anime ?: return

        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(dialog = Dialog.SettingsSheet)
        }
    }

    fun showCoverDialog() {
        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(dialog = Dialog.FullCover)
        }
    }

    fun showScheduleDialog() {
        val currentState = successState ?: return
        if (!currentState.showScheduleButton) return

        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(dialog = Dialog.Schedule)
        }
    }

    fun retryLoadSchedule() {
        loadSchedule(force = true)
    }

    private fun prefetchScheduleIfNeeded(state: State.Success) {
        if (!state.hasScheduleSupport || state.schedule != ScheduleState.NotLoaded) return
        loadSchedule()
    }

    private fun loadSchedule(force: Boolean = false) {
        val currentState = successState ?: return
        val currentAnime = currentState.anime
        val source = animeSourceManager.get(currentAnime.source) as? AnimeScheduleSource ?: return

        val shouldLoad = when (currentState.schedule) {
            ScheduleState.Unavailable -> false
            ScheduleState.NotLoaded -> true
            ScheduleState.Empty -> force
            ScheduleState.Loading -> false
            is ScheduleState.Error -> force
            is ScheduleState.Success -> force
        }
        if (!shouldLoad) return

        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(schedule = ScheduleState.Loading)
        }

        screenModelScope.launchIO {
            val schedule = runCatching {
                source.getEpisodeSchedule(currentAnime.toSAnime())
            }

            mutableState.update { currentState ->
                val success = currentState as? State.Success ?: return@update currentState
                schedule.fold(
                    onSuccess = {
                        val entries = it.map(SAnimeScheduleEpisode::toUi).toImmutableList()
                        success.copy(
                            schedule = entries.takeIf { it.isNotEmpty() }
                                ?.let(ScheduleState::Success)
                                ?: ScheduleState.Empty,
                            dialog = success.dialog.takeUnless { dialog ->
                                dialog is Dialog.Schedule && entries.isEmpty()
                            },
                        )
                    },
                    onFailure = {
                        logcat(LogPriority.ERROR, it)
                        success.copy(schedule = ScheduleState.Error(with(context) { it.formattedMessage }))
                    },
                )
            }
        }
    }

    fun updateDisplayName(displayName: String) {
        val currentAnime = successState?.anime ?: return
        screenModelScope.launchIO {
            val updated = animeRepository.updateDisplayName(
                animeId = currentAnime.id,
                displayName = displayName.trim().ifBlank { null },
            )
            if (!updated) {
                screenModelScope.launch {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.unknown_error))
                }
                return@launchIO
            }
            dismissDialog()
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
        data object FullCover : Dialog

        data object Schedule : Dialog

        data object SettingsSheet : Dialog

        data class ChangeCategory(
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog

        data class EditDisplayName(
            val initialValue: String,
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
            val selection: kotlinx.collections.immutable.ImmutableSet<Long>,
            val categories: ImmutableList<Category>,
            val hasScheduleSupport: Boolean,
            val schedule: ScheduleState,
            val isRefreshing: Boolean,
            val dialog: Dialog? = null,
        ) : State {
            val sourceAvailable: Boolean = sourceName != null

            val isSelectionMode: Boolean = selection.isNotEmpty()

            val selectedCount: Int = selection.size

            val filterActive: Boolean = anime.episodesFiltered()

            val showScheduleButton: Boolean
                get() = when (schedule) {
                    is ScheduleState.Success,
                    is ScheduleState.Error,
                    -> true
                    ScheduleState.Unavailable,
                    ScheduleState.NotLoaded,
                    ScheduleState.Loading,
                    ScheduleState.Empty,
                    -> false
                }

            val primaryEpisode: AnimeEpisode?
                get() = episodes.firstOrNull { it.id == primaryEpisodeId }

            val scheduleSummary: ScheduleSummary
                get() = when (val schedule = schedule) {
                    ScheduleState.NotLoaded,
                    ScheduleState.Loading -> ScheduleSummary.Loading
                    is ScheduleState.Success -> {
                        val today = LocalDate.now(ZoneId.systemDefault())
                        val upcomingCount = schedule.entries.count { it.isUpcoming(today) }
                        when {
                            upcomingCount > 0 -> ScheduleSummary.Upcoming(upcomingCount)
                            schedule.entries.isNotEmpty() -> ScheduleSummary.Scheduled(schedule.entries.size)
                            else -> ScheduleSummary.Empty
                        }
                    }
                    is ScheduleState.Error -> {
                        ScheduleSummary.Error
                    }
                    ScheduleState.Empty -> {
                        ScheduleSummary.Empty
                    }
                    ScheduleState.Unavailable -> {
                        ScheduleSummary.Unavailable
                    }
                }
        }
    }

    sealed interface ScheduleState {
        data object Unavailable : ScheduleState

        data object NotLoaded : ScheduleState

        data object Loading : ScheduleState

        data object Empty : ScheduleState

        data class Error(val message: String) : ScheduleState

        data class Success(val entries: ImmutableList<AnimeScheduleEpisode>) : ScheduleState
    }

    sealed interface ScheduleSummary {
        data object Loading : ScheduleSummary

        data class Upcoming(val count: Int) : ScheduleSummary

        data class Scheduled(val count: Int) : ScheduleSummary

        data object Error : ScheduleSummary

        data object Empty : ScheduleSummary

        data object Unavailable : ScheduleSummary
    }

    @Immutable
    data class AnimeScheduleEpisode(
        val seasonNumber: Int?,
        val episodeNumber: Float?,
        val title: String?,
        val airDate: Long,
        val statusText: String?,
        val isAvailable: Boolean?,
    ) {
        val airLocalDate: LocalDate = Instant.ofEpochMilli(airDate).atZone(ZoneId.systemDefault()).toLocalDate()

        fun isUpcoming(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Boolean {
            return when (isAvailable) {
                true -> false
                false -> true
                null -> airLocalDate >= today
            }
        }
    }
}

private fun List<AnimeEpisode>.filterEpisodes(
    anime: AnimeTitle,
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
): List<AnimeEpisode> {
    val unwatchedFilter = anime.unwatchedFilter
    val startedFilter = anime.startedFilter

    return asSequence()
        .filter { episode ->
            applyFilter(unwatchedFilter) { !episode.completed }
        }
        .filter { episode ->
            applyFilter(startedFilter) {
                val playbackState = playbackStateByEpisodeId[episode.id]
                playbackState?.let { !it.completed && it.positionMs > 0L && it.durationMs > 0L } == true ||
                    episode.watched || episode.completed
            }
        }
        .toList()
}

private fun List<AnimeEpisode>.sortEpisodes(anime: AnimeTitle): List<AnimeEpisode> {
    val ascending = !anime.sortDescending()
    val comparator = when (anime.sorting) {
        AnimeTitle.EPISODE_SORTING_NUMBER -> compareBy<AnimeEpisode> {
            it.episodeNumber.takeIf { number -> number >= 0.0 } ?: Double.MAX_VALUE
        }.thenBy { it.sourceOrder }
        AnimeTitle.EPISODE_SORTING_UPLOAD_DATE -> compareBy<AnimeEpisode> {
            it.dateUpload.takeIf { date -> date > 0L } ?: Long.MAX_VALUE
        }.thenBy { it.sourceOrder }
        AnimeTitle.EPISODE_SORTING_ALPHABET -> compareBy<AnimeEpisode>({ it.name.ifBlank { it.url } }, { it.sourceOrder })
        else -> compareBy<AnimeEpisode> { it.sourceOrder }
    }
    return if (ascending) {
        sortedWith(comparator)
    } else {
        sortedWith(comparator.reversed())
    }
}

private fun SAnimeScheduleEpisode.toUi() = AnimeScreenModel.AnimeScheduleEpisode(
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    airDate = airDate,
    statusText = statusText,
    isAvailable = isAvailable,
)
