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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.service.groupedByMergedMember
import tachiyomi.domain.anime.service.sortedForMergedDisplay
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
    private val bypassMerge: Boolean = false,
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val getAnimeWithEpisodes: GetAnimeWithEpisodes = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = Injekt.get(),
    private val updateMergedAnime: UpdateMergedAnime = Injekt.get(),
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

    private inline fun updateSuccessState(transform: (State.Success) -> State.Success) {
        mutableState.update { currentState ->
            when (currentState) {
                State.Loading -> currentState
                is State.Error -> currentState
                is State.Success -> transform(currentState)
            }
        }
    }

    init {
        observeAnime()
        refresh(initial = true)
    }

    private fun observeAnime() {
        screenModelScope.launchIO {
            combine(
                getAnimeWithEpisodes.subscribe(animeId, bypassMerge = bypassMerge),
                if (bypassMerge) {
                    flowOf(emptyList())
                } else {
                    getMergedAnime.subscribeGroupByAnimeId(animeId)
                },
            ) { (anime, episodes), merges ->
                Triple(anime, episodes, merges.sortedBy(AnimeMerge::position))
            }
                .flatMapLatest { (anime, episodes, merges) ->
                    val memberIds = merges.map(AnimeMerge::animeId).ifEmpty { listOf(anime.id) }
                    combine(
                        mergedMemberAnimeFlow(memberIds),
                        mergedPlaybackStatesFlow(memberIds),
                        mergedCategoriesFlow(memberIds),
                    ) { memberAnimes, playbackStates, categories ->
                        buildSuccessState(
                            anime = anime,
                            episodes = episodes,
                            memberIds = memberIds,
                            memberAnimes = memberAnimes,
                            playbackStates = playbackStates,
                            categories = categories,
                        )
                    }
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
                getAnimeWithEpisodes.awaitAnime(animeId)
            }.getOrElse {
                mutableState.value = State.Error(with(context) { it.formattedMessage })
                return@launchIO
            }

            val membersToRefresh = getMergeMembers().ifEmpty { listOf(currentAnime) }

            setRefreshing(true)
            try {
                var failure: Throwable? = null
                membersToRefresh.forEach { memberAnime ->
                    runCatching {
                        if (animeSourceManager.get(memberAnime.source) != null) {
                            syncAnimeWithSource(memberAnime)
                        }
                    }.onFailure { throwable ->
                        logcat(LogPriority.ERROR, throwable)
                        if (failure == null) {
                            failure = throwable
                        }
                    }
                }
                failure?.let { throw it }
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
        val currentState = successState ?: return
        val currentAnime = currentState.anime
        if (currentAnime.favorite && currentState.isSelectionMode) {
            clearSelection()
        }
        if (currentState.isMerged && currentAnime.favorite) {
            showRemoveMergedAnimeDialog()
            return
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
        val flag = when (state) {
            tachiyomi.core.common.preference.TriState.DISABLED -> AnimeTitle.SHOW_ALL
            tachiyomi.core.common.preference.TriState.ENABLED_IS -> AnimeTitle.EPISODE_SHOW_UNWATCHED
            tachiyomi.core.common.preference.TriState.ENABLED_NOT -> AnimeTitle.EPISODE_SHOW_WATCHED
        }
        screenModelScope.launchNonCancellable {
            updateMergedMemberAnime { memberAnime ->
                setAnimeEpisodeFlags.awaitSetUnwatchedFilter(memberAnime, flag)
            }
        }
    }

    fun setStartedFilter(state: tachiyomi.core.common.preference.TriState) {
        val flag = when (state) {
            tachiyomi.core.common.preference.TriState.DISABLED -> AnimeTitle.SHOW_ALL
            tachiyomi.core.common.preference.TriState.ENABLED_IS -> AnimeTitle.EPISODE_SHOW_STARTED
            tachiyomi.core.common.preference.TriState.ENABLED_NOT -> AnimeTitle.EPISODE_SHOW_NOT_STARTED
        }
        screenModelScope.launchNonCancellable {
            updateMergedMemberAnime { memberAnime ->
                setAnimeEpisodeFlags.awaitSetStartedFilter(memberAnime, flag)
            }
        }
    }

    fun setDisplayMode(mode: Long) {
        screenModelScope.launchNonCancellable {
            updateMergedMemberAnime { memberAnime ->
                setAnimeEpisodeFlags.awaitSetDisplayMode(memberAnime, mode)
            }
        }
    }

    fun setSorting(sort: Long) {
        screenModelScope.launchNonCancellable {
            updateMergedMemberAnime { memberAnime ->
                setAnimeEpisodeFlags.awaitSetSortingModeOrFlipOrder(memberAnime, sort)
            }
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
        screenModelScope.launchNonCancellable {
            updateMergedMemberAnime { memberAnime ->
                setAnimeDefaultEpisodeFlags.await(memberAnime)
            }
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
            val selectedCategoryIds = getMergedMemberIds()
                .flatMap { memberId -> getAnimeCategories.await(memberId) }
                .filterNot(Category::isSystemCategory)
                .map(Category::id)
                .toSet()
            val availableCategories = getCategories.await()
                .filterNot { it.isSystemCategory }
                .mapAsCheckboxState { it.id in selectedCategoryIds }
                .toImmutableList()

            updateSuccessState { it.copy(dialog = Dialog.ChangeCategory(availableCategories)) }
        }
    }

    fun showEditDisplayNameDialog() {
        val currentAnime = successState?.anime ?: return
        if (!currentAnime.favorite) return

        updateSuccessState { it.copy(dialog = Dialog.EditDisplayName(currentAnime.displayName.orEmpty())) }
    }

    fun showSettingsDialog() {
        successState ?: return
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showScheduleDialog() {
        val currentState = successState ?: return
        if (!currentState.showScheduleButton) return

        updateSuccessState { it.copy(dialog = Dialog.Schedule) }
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
        screenModelScope.launchIO {
            getMergedMemberIds().forEach { memberId ->
                setAnimeCategories.await(memberId, categoryIds)
            }
            dismissDialog()
        }
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    private fun setRefreshing(isRefreshing: Boolean) {
        updateSuccessState { it.copy(isRefreshing = isRefreshing) }
    }

    sealed interface Dialog {
        data object FullCover : Dialog

        data object Schedule : Dialog

        data object SettingsSheet : Dialog

        data class ChangeCategory(
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog

        data class ManageMerge(
            val targetId: Long,
            val members: ImmutableList<MergeMember>,
            val removableIds: ImmutableList<Long> = persistentListOf(),
        ) : Dialog

        data class RemoveMergedAnime(
            val members: ImmutableList<AnimeTitle>,
        ) : Dialog

        data class EditDisplayName(
            val initialValue: String,
        ) : Dialog
    }

    @Immutable
    data class MergeMember(
        val id: Long,
        val anime: AnimeTitle,
        val subtitle: String,
    )

    sealed interface State {
        data object Loading : State

        data class Error(val message: String) : State

        @Immutable
        data class Success(
            val anime: AnimeTitle,
            val sourceName: String?,
            val memberIds: ImmutableList<Long>,
            val memberTitleById: Map<Long, String>,
            val mergedMemberTitles: ImmutableList<String>,
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
            val isMerged: Boolean
                get() = memberIds.size > 1

            val sourceAvailable: Boolean
                get() = sourceName != null || isMerged

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

            val episodeListItems: List<AnimeEpisodeListEntry>
                get() = if (!isMerged) {
                    episodes.map(AnimeEpisodeListEntry::Item)
                } else {
                    buildList {
                        episodes.groupedByMergedMember(memberIds).forEach { (memberId, memberEpisodes) ->
                            add(
                                AnimeEpisodeListEntry.MemberHeader(
                                    animeId = memberId,
                                    title = memberTitleById[memberId].orEmpty().ifBlank { anime.displayTitle },
                                ),
                            )
                            addAll(memberEpisodes.map(AnimeEpisodeListEntry::Item))
                        }
                    }
                }

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

    fun showManageMergeDialog() {
        val currentState = successState ?: return
        if (!currentState.isMerged) return

        screenModelScope.launchIO {
            val targetId = getVisibleAnimeId(currentState.anime.id)
            val members = getMergeMembers().map { memberAnime ->
                MergeMember(
                    id = memberAnime.id,
                    anime = memberAnime,
                    subtitle = buildMergeSubtitle(memberAnime),
                )
            }
                .toImmutableList()
            updateSuccessState {
                it.copy(dialog = Dialog.ManageMerge(targetId = targetId, members = members))
            }
        }
    }

    fun removeMergedMembers(animeIds: List<Long>) {
        val currentState = successState ?: return
        val dialog = currentState.dialog as? Dialog.ManageMerge
        screenModelScope.launchIO {
            if (animeIds.isEmpty()) return@launchIO
            if (dialog != null) {
                saveManageMerge(dialog, animeIds)
            } else {
                updateMergedAnime.awaitRemoveMembers(getVisibleAnimeId(currentState.anime.id), animeIds)
            }
            dismissDialog()
        }
    }

    fun reorderMergeMembers(fromIndex: Int, toIndex: Int) {
        updateSuccessState { currentState ->
            val dialog = currentState.dialog as? Dialog.ManageMerge ?: return@updateSuccessState currentState
            if (fromIndex !in dialog.members.indices || toIndex !in dialog.members.indices) {
                return@updateSuccessState currentState
            }

            val reordered = dialog.members.toMutableList().apply {
                val item = removeAt(fromIndex)
                add(toIndex, item)
            }
            val reorderedRemovalIds = reordered.mapNotNull { member ->
                member.id.takeIf { it in dialog.removableIds }
            }.toImmutableList()

            currentState.copy(dialog = dialog.copy(members = reordered.toImmutableList(), removableIds = reorderedRemovalIds))
        }
    }

    fun toggleMergedMemberRemoval(animeId: Long) {
        updateSuccessState { currentState ->
            val dialog = currentState.dialog as? Dialog.ManageMerge ?: return@updateSuccessState currentState
            if (animeId == dialog.targetId || dialog.members.none { it.id == animeId }) {
                return@updateSuccessState currentState
            }

            val updatedIds = dialog.removableIds.toMutableList().apply {
                if (animeId in this) {
                    remove(animeId)
                } else {
                    add(animeId)
                }
            }.toImmutableList()

            currentState.copy(dialog = dialog.copy(removableIds = updatedIds))
        }
    }

    fun saveMergeOrder() {
        val dialog = successState?.dialog as? Dialog.ManageMerge ?: return
        screenModelScope.launchIO {
            saveManageMerge(dialog, dialog.removableIds)
            dismissDialog()
        }
    }

    fun unmergeAll() {
        val currentState = successState ?: return
        screenModelScope.launchIO {
            updateMergedAnime.awaitDeleteGroup(getVisibleAnimeId(currentState.anime.id))
            dismissDialog()
        }
    }

    fun removeMergedAnime(animes: List<AnimeTitle>) {
        val currentState = successState ?: return
        screenModelScope.launchIO {
            updateMergedAnime.awaitDeleteGroup(getVisibleAnimeId(currentState.anime.id))
            animes.forEach { anime ->
                animeRepository.update(
                    AnimeTitleUpdate(
                        id = anime.id,
                        favorite = false,
                        dateAdded = 0L,
                    ),
                )
            }
            dismissDialog()
        }
    }

    suspend fun getVisibleAnimeId(animeId: Long): Long {
        if (bypassMerge) return animeId
        return getMergedAnime.awaitVisibleTargetId(animeId)
    }

    private fun showRemoveMergedAnimeDialog() {
        val currentState = successState ?: return
        if (!currentState.isMerged) return

        screenModelScope.launchIO {
            val members = getMergeMembers().toImmutableList()
            updateSuccessState {
                it.copy(dialog = Dialog.RemoveMergedAnime(members))
            }
        }
    }

    private suspend fun buildSuccessState(
        anime: AnimeTitle,
        episodes: List<AnimeEpisode>,
        memberIds: List<Long>,
        memberAnimes: List<AnimeTitle>,
        playbackStates: List<AnimePlaybackState>,
        categories: List<Category>,
    ): State.Success {
        val currentSuccess = successState
        val playbackStateByEpisodeId = playbackStates.associateBy(AnimePlaybackState::episodeId)
        val displayedEpisodes = episodes
            .filterEpisodes(anime, playbackStateByEpisodeId)
            .sortEpisodes(anime, memberIds)
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

        return State.Success(
            anime = anime,
            sourceName = getSourceName(anime, memberIds),
            memberIds = memberIds.toImmutableList(),
            memberTitleById = memberAnimes.associate { it.id to it.displayTitle },
            mergedMemberTitles = memberAnimes.map(AnimeTitle::displayTitle)
                .filter { it.isNotBlank() }
                .distinct()
                .toImmutableList(),
            episodes = displayedEpisodes.toImmutableList(),
            playbackStateByEpisodeId = playbackStateByEpisodeId,
            primaryEpisodeId = selectPrimaryEpisodeId(displayedEpisodes, playbackStateByEpisodeId),
            selection = displayedEpisodes.asSequence()
                .map(AnimeEpisode::id)
                .filter(selectedEpisodeIds::contains)
                .toSet()
                .toImmutableSet(),
            categories = categories.toImmutableList(),
            hasScheduleSupport = hasScheduleSupport,
            schedule = schedule,
            isRefreshing = currentSuccess?.isRefreshing ?: false,
            dialog = currentSuccess?.dialog,
        )
    }

    private suspend fun mergedMemberAnimeFlow(memberIds: List<Long>) =
        combine(memberIds.map { memberId -> animeRepository.getAnimeByIdAsFlow(memberId) }) { animes ->
            animes.map { it as AnimeTitle }
        }

    private fun mergedPlaybackStatesFlow(memberIds: List<Long>) =
        combine(memberIds.map(animePlaybackStateRepository::getByAnimeIdAsFlow)) { playbackStates ->
            playbackStates.flatMap { it as List<AnimePlaybackState> }
        }

    private fun mergedCategoriesFlow(memberIds: List<Long>) =
        combine(memberIds.map(getAnimeCategories::subscribe)) { categories ->
            categories.flatMap { it as List<Category> }
                .filterNot(Category::isSystemCategory)
                .distinctBy(Category::id)
                .sortedBy(Category::order)
        }

    private suspend fun saveManageMerge(dialog: Dialog.ManageMerge, animeIdsToRemove: Collection<Long>) {
        val animeIdsToRemoveSet = animeIdsToRemove.toSet()
        val remainingIds = dialog.members.map(MergeMember::id)
            .filterNot(animeIdsToRemoveSet::contains)
        val targetId = remainingIds.firstOrNull { it == dialog.targetId } ?: remainingIds.firstOrNull()

        if (targetId != null && remainingIds.size > 1) {
            updateMergedAnime.awaitMerge(targetId, remainingIds)
        } else {
            updateMergedAnime.awaitDeleteGroup(dialog.targetId)
        }
    }

    private suspend fun getMergedMemberIds(): List<Long> {
        if (bypassMerge) return listOf(animeId)
        return getMergedAnime.awaitGroupByAnimeId(animeId)
            .sortedBy(AnimeMerge::position)
            .map(AnimeMerge::animeId)
            .ifEmpty { listOf(animeId) }
    }

    private suspend fun getMergeMembers(): List<AnimeTitle> {
        return getMergedMemberIds().mapNotNull { memberId -> getAnimeOrNull(memberId) }
    }

    private suspend fun updateMergedMemberAnime(block: suspend (AnimeTitle) -> Unit) {
        getMergeMembers().forEach { memberAnime ->
            block(memberAnime)
        }
    }

    private suspend fun getAnimeOrNull(id: Long): AnimeTitle? {
        return runCatching { animeRepository.getAnimeById(id) }.getOrNull()
    }

    private fun getSourceName(anime: AnimeTitle, memberIds: List<Long>): String? {
        return if (memberIds.size > 1) {
            context.stringResource(MR.strings.multi_lang)
        } else {
            animeSourceManager.get(anime.source)?.name
        }
    }

    private fun buildMergeSubtitle(anime: AnimeTitle): String {
        val sourceName = animeSourceManager.get(anime.source)?.name
            ?: context.stringResource(MR.strings.source_not_installed, anime.source.toString())
        val creator = listOf(anime.director, anime.studio, anime.producer, anime.writer)
            .firstOrNull { !it.isNullOrBlank() }
        return buildString {
            append(sourceName)
            if (!creator.isNullOrBlank() && !creator.equals(sourceName, ignoreCase = true)) {
                append(" • ")
                append(creator)
            }
        }
    }
}

@Immutable
sealed class AnimeEpisodeListEntry {
    @Immutable
    data class MemberHeader(
        val animeId: Long,
        val title: String,
    ) : AnimeEpisodeListEntry()

    @Immutable
    data class Item(
        val episode: AnimeEpisode,
    ) : AnimeEpisodeListEntry()
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

private fun List<AnimeEpisode>.sortEpisodes(
    anime: AnimeTitle,
    memberIds: List<Long> = map(AnimeEpisode::animeId).distinct(),
): List<AnimeEpisode> {
    if (memberIds.size > 1) {
        return sortedForMergedDisplay(anime, memberIds)
    }

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
