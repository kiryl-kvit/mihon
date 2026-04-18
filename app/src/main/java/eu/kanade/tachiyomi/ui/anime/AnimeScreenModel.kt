package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.addOrRemove
import eu.kanade.domain.anime.model.episodesFiltered
import eu.kanade.presentation.anime.AnimeMergeTarget
import eu.kanade.presentation.anime.buildAnimeMergeTargets
import eu.kanade.presentation.anime.matchesQuery
import eu.kanade.presentation.anime.toMergeEditorEntry
import eu.kanade.presentation.manga.components.MergeEditorEntry
import eu.kanade.presentation.updates.UpdatesSelectionState
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.AnimeScheduleSource
import eu.kanade.tachiyomi.source.AnimeWebViewSource
import eu.kanade.tachiyomi.source.model.SAnimeScheduleEpisode
import eu.kanade.tachiyomi.util.lang.toStoredDisplayName
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.anime.model.toSAnime
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.model.DuplicateAnimeCandidate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.anime.service.groupedByMergedMember
import tachiyomi.domain.anime.service.sortedForMergedDisplay
import tachiyomi.domain.anime.service.sortedForReading
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AnimeScreenModel(
    private val context: Context,
    private val animeId: Long,
    private val fromSource: Boolean = false,
    private val bypassMerge: Boolean = false,
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val getAnimeWithEpisodes: GetAnimeWithEpisodes = Injekt.get(),
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = runCatching { Injekt.get<GetMergedAnime>() }
        .getOrElse { GetMergedAnime(NoOpScreenMergedAnimeRepository) },
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val updateMergedAnime: UpdateMergedAnime = runCatching { Injekt.get<UpdateMergedAnime>() }
        .getOrElse { UpdateMergedAnime(NoOpScreenMergedAnimeRepository) },
    private val setAnimeEpisodeFlags: SetAnimeEpisodeFlags = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val syncAnimeWithSource: SyncAnimeWithSource = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<AnimeScreenModel.State>(State.Loading) {

    private val selectionState = UpdatesSelectionState()
    private val selectedEpisodeIds = HashSet<Long>()
    private var duplicateObservationJob: Job? = null

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
        observeDuplicateCandidates()
        refresh(initial = true)
    }

    private fun observeAnime() {
        screenModelScope.launchIO {
            combine(
                getAnimeWithEpisodes.subscribe(animeId, bypassMerge = bypassMerge),
                getMergedAnime.subscribeGroupByAnimeId(animeId),
            ) { (anime, episodes), merges ->
                Triple(anime, episodes, merges.sortedBy(AnimeMerge::position))
            }
                .flatMapLatest { (anime, episodes, merges) ->
                    val mergeGroupMemberIds = merges.map(AnimeMerge::animeId).ifEmpty { listOf(anime.id) }
                    val memberIds = if (bypassMerge) {
                        listOf(anime.id)
                    } else {
                        mergeGroupMemberIds
                    }
                    combine(
                        mergedMemberAnimeFlow(memberIds),
                        mergedPlaybackStatesFlow(memberIds),
                        mergedCategoriesFlow(memberIds),
                    ) { memberAnimes, playbackStates, categories ->
                        buildSuccessState(
                            anime = anime,
                            episodes = episodes,
                            memberIds = memberIds,
                            mergeTargetId = merges.firstOrNull()?.targetId ?: anime.id,
                            mergeGroupMemberIds = mergeGroupMemberIds,
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
                .collectLatest { success ->
                    coroutineContext.ensureActive()
                    mutableState.value = success
                    if (!success.anime.favorite && success.anime.episodeFlags == 0L) {
                        setAnimeDefaultEpisodeFlags.await(success.anime)
                    }
                    prefetchScheduleIfNeeded(success)
                }
        }
    }

    private fun observeDuplicateCandidates() {
        if (!fromSource) return

        duplicateObservationJob?.cancel()
        duplicateObservationJob = screenModelScope.launchIO {
            getDuplicateLibraryAnime.subscribe(
                anime = this@AnimeScreenModel.state
                    .filter { it is State.Success }
                    .map { (it as State.Success).anime }
                    .distinctUntilChanged(),
                scope = this,
            ).collectLatest { duplicates ->
                updateSuccessState {
                    if (!it.isFromSource || it.anime.favorite) {
                        it.copy(duplicateCandidates = emptyList())
                    } else {
                        it.copy(duplicateCandidates = duplicates)
                    }
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
        toggleFavorite(checkDuplicate = true)
    }

    fun toggleFavorite(checkDuplicate: Boolean) {
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
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryAnime(currentAnime)
                    if (duplicates.isNotEmpty()) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateAnime(currentAnime, duplicates)) }
                        return@launchIO
                    }
                }
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
            val selectedCategoryIds = getDisplayedMemberIds()
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

        updateSuccessState { it.copy(dialog = Dialog.EditDisplayName(currentAnime.displayTitle)) }
    }

    fun showSettingsDialog() {
        successState ?: return
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showDuplicateDialog() {
        val currentState = successState ?: return
        if (currentState.duplicateCandidates.isEmpty()) return
        updateSuccessState {
            it.copy(
                dialog = Dialog.DuplicateAnime(
                    currentState.anime,
                    currentState.duplicateCandidates,
                ),
            )
        }
    }

    fun showMergeTargetPicker() {
        val currentState = successState ?: return
        screenModelScope.launchIO {
            val targets = getMergeTargets(currentState.mergeGroupMemberIds.toSet())
            if (targets.isEmpty()) return@launchIO
            val query = currentState.anime.displayTitle
            val visibleTargets = targets.filter { it.matchesQuery(query) }.toImmutableList()

            updateSuccessState {
                it.copy(
                    dialog = Dialog.SelectMergeTarget(
                        anime = currentState.anime,
                        query = query,
                        targets = targets,
                        visibleTargets = visibleTargets,
                    ),
                )
            }
        }
    }

    fun updateMergeTargetQuery(query: String) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.SelectMergeTarget ?: return@updateSuccessState state
            val visibleTargets = dialog.targets.filter { it.matchesQuery(query) }.toImmutableList()
            state.copy(dialog = dialog.copy(query = query, visibleTargets = visibleTargets))
        }
    }

    fun openMergeEditor(targetId: Long) {
        val dialog = successState?.dialog as? Dialog.SelectMergeTarget ?: return
        screenModelScope.launchIO {
            val target = dialog.targets.firstOrNull { it.id == targetId } ?: return@launchIO
            updateSuccessState {
                it.copy(dialog = createMergeEditorDialog(dialog.anime, target))
            }
        }
    }

    fun openMergeEditorForDuplicate(targetId: Long) {
        val dialog = successState?.dialog as? Dialog.DuplicateAnime ?: return
        val currentState = successState ?: return
        screenModelScope.launchIO {
            val target = getMergeTargets(currentState.mergeGroupMemberIds.toSet())
                .firstOrNull { it.id == targetId }
                ?: return@launchIO
            updateSuccessState {
                it.copy(dialog = createMergeEditorDialog(dialog.anime, target))
            }
        }
    }

    fun moveMergeEntry(fromIndex: Int, toIndex: Int) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            val entries = dialog.entries.toMutableList()
            if (fromIndex !in entries.indices || toIndex !in entries.indices) return@updateSuccessState state
            val entry = entries.removeAt(fromIndex)
            entries.add(toIndex, entry)
            state.copy(dialog = dialog.copy(entries = entries.toImmutableList()))
        }
    }

    fun setMergeTarget(animeId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            if (dialog.targetLocked || dialog.entries.none { it.id == animeId }) return@updateSuccessState state
            state.copy(
                dialog = dialog.copy(
                    targetId = animeId,
                    removedIds = dialog.removedIds - animeId,
                    libraryRemovalIds = dialog.libraryRemovalIds - animeId,
                ),
            )
        }
    }

    fun toggleMergeEntryRemoval(animeId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            val entry = dialog.entries.firstOrNull { it.id == animeId } ?: return@updateSuccessState state
            if (!entry.isRemovable || animeId == dialog.targetId) return@updateSuccessState state
            val removedIds = dialog.removedIds.toMutableSet().apply {
                if (!add(animeId)) remove(animeId)
            }
            state.copy(dialog = dialog.copy(removedIds = removedIds))
        }
    }

    fun toggleMergeEntryLibraryRemoval(animeId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            val entry = dialog.entries.firstOrNull { it.id == animeId } ?: return@updateSuccessState state
            if (!entry.isRemovable || animeId == dialog.targetId) return@updateSuccessState state
            val libraryRemovalIds = dialog.libraryRemovalIds.toMutableSet().apply {
                if (!add(animeId)) remove(animeId)
            }
            state.copy(dialog = dialog.copy(libraryRemovalIds = libraryRemovalIds))
        }
    }

    fun confirmMerge() {
        val dialog = successState?.dialog as? Dialog.EditMerge ?: return
        screenModelScope.launchIO {
            val targetAnime = getAnimeOrNull(dialog.targetId) ?: return@launchIO
            val localAnime = networkToLocalAnime(dialog.anime)
            ensureFavorite(localAnime, targetAnime, dialog.categoryIds)

            val orderedIds = dialog.entries
                .filterNot { it.id in (dialog.removedIds + dialog.libraryRemovalIds) }
                .map(MergeEditorEntry::id)
                .distinct()

            if (orderedIds.size > 1) {
                updateMergedAnime.awaitMerge(dialog.targetId, orderedIds)
            }
            removeMembersFromLibrary(dialog.libraryRemovalIds)
            dismissDialog()
        }
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

        val shouldLoad = when (currentState.schedule) {
            ScheduleState.Unavailable -> false
            ScheduleState.NotLoaded -> true
            ScheduleState.Empty -> force
            ScheduleState.Loading -> false
            is ScheduleState.Error -> force
            is ScheduleState.Success -> force
        }
        if (!shouldLoad) return

        val requestedMemberIds = currentState.memberIds

        mutableState.update { currentState ->
            val success = currentState as? State.Success ?: return@update currentState
            success.copy(schedule = ScheduleState.Loading)
        }

        screenModelScope.launchIO {
            val scheduleTargets = requestedMemberIds.mapIndexedNotNull { memberOrder, memberId ->
                val memberAnime = getAnimeOrNull(memberId) ?: return@mapIndexedNotNull null
                val source =
                    animeSourceManager.get(memberAnime.source) as? AnimeScheduleSource ?: return@mapIndexedNotNull null
                ScheduleMemberTarget(
                    anime = memberAnime,
                    memberId = memberId,
                    memberOrder = memberOrder,
                    source = source,
                )
            }

            if (scheduleTargets.isEmpty()) {
                mutableState.update { currentState ->
                    val success = currentState as? State.Success ?: return@update currentState
                    if (success.memberIds != requestedMemberIds) return@update currentState

                    success.copy(
                        schedule = ScheduleState.Unavailable,
                        dialog = success.dialog.takeUnless { it is Dialog.Schedule },
                    )
                }
                return@launchIO
            }

            val failures = mutableListOf<Throwable>()
            val entries = buildList {
                scheduleTargets.forEach { target ->
                    runCatching {
                        target.source.getEpisodeSchedule(target.anime.toSAnime())
                    }.onSuccess { memberEntries ->
                        addAll(
                            memberEntries.map {
                                it.toUi(
                                    memberId = target.memberId,
                                    memberOrder = target.memberOrder,
                                    memberTitle = target.anime.displayTitle,
                                )
                            },
                        )
                    }.onFailure {
                        logcat(LogPriority.ERROR, it)
                        failures += it
                    }
                }
            }.toImmutableList()

            mutableState.update { currentState ->
                val success = currentState as? State.Success ?: return@update currentState
                if (success.memberIds != requestedMemberIds) return@update currentState

                val scheduleState = when {
                    entries.isNotEmpty() -> ScheduleState.Success(entries)
                    failures.isNotEmpty() -> ScheduleState.Error(with(context) { failures.first().formattedMessage })
                    else -> ScheduleState.Empty
                }

                success.copy(
                    schedule = scheduleState,
                    dialog = success.dialog.takeUnless { dialog ->
                        dialog is Dialog.Schedule && scheduleState == ScheduleState.Empty
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
                displayName = displayName.toStoredDisplayName(currentAnime.title),
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
            getDisplayedMemberIds().forEach { memberId ->
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

        data class DuplicateAnime(
            val anime: AnimeTitle,
            val duplicates: List<DuplicateAnimeCandidate>,
        ) : Dialog

        data object Schedule : Dialog

        data object SettingsSheet : Dialog

        data class ChangeCategory(
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog

        data class EditMerge(
            val anime: AnimeTitle,
            val targetId: Long,
            val targetLocked: Boolean,
            val entries: ImmutableList<MergeEditorEntry>,
            val removedIds: Set<Long>,
            val libraryRemovalIds: Set<Long>,
            val categoryIds: List<Long>,
        ) : Dialog {
            val enabled: Boolean
                get() = entries.count { it.id !in (removedIds + libraryRemovalIds) } > 1
        }

        data class ManageMerge(
            val targetId: Long,
            val savedTargetId: Long,
            val members: ImmutableList<MergeMember>,
            val removableIds: ImmutableList<Long> = persistentListOf(),
            val libraryRemovalIds: ImmutableList<Long> = persistentListOf(),
        ) : Dialog

        data class RemoveMergedAnime(
            val members: ImmutableList<AnimeTitle>,
        ) : Dialog

        data class SelectMergeTarget(
            val anime: AnimeTitle,
            val query: String = "",
            val targets: ImmutableList<AnimeMergeTarget>,
            val visibleTargets: ImmutableList<AnimeMergeTarget>,
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
            val mergeTargetId: Long,
            val mergeGroupMemberIds: ImmutableList<Long>,
            val isFromSource: Boolean,
            val episodes: ImmutableList<AnimeEpisode>,
            val playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
            val primaryEpisodeId: Long?,
            val selection: kotlinx.collections.immutable.ImmutableSet<Long>,
            val categories: ImmutableList<Category>,
            val hasScheduleSupport: Boolean,
            val schedule: ScheduleState,
            val duplicateCandidates: List<DuplicateAnimeCandidate> = emptyList(),
            val isRefreshing: Boolean,
            val dialog: Dialog? = null,
        ) : State {
            val isPartOfMerge: Boolean
                get() = mergeGroupMemberIds.size > 1

            val isMerged: Boolean
                get() = memberIds.size > 1

            val showMergeNotice: Boolean
                get() = isPartOfMerge && !isMerged && mergeTargetId != anime.id

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
                    ScheduleState.Loading,
                    -> ScheduleSummary.Loading
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
        val memberId: Long,
        val memberOrder: Int,
        val memberTitle: String,
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
        if (!currentState.isPartOfMerge) return

        screenModelScope.launchIO {
            val members = currentState.mergeGroupMemberIds.mapNotNull { memberId ->
                getAnimeOrNull(memberId)
            }.map { memberAnime ->
                MergeMember(
                    id = memberAnime.id,
                    anime = memberAnime,
                    subtitle = buildMergeSubtitle(memberAnime),
                )
            }
                .toImmutableList()
            updateSuccessState {
                it.copy(
                    dialog = Dialog.ManageMerge(
                        targetId = currentState.mergeTargetId,
                        savedTargetId = currentState.mergeTargetId,
                        members = members,
                    ),
                )
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
                updateMergedAnime.awaitRemoveMembers(currentState.mergeTargetId, animeIds)
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
            val reorderedLibraryRemovalIds = reordered.mapNotNull { member ->
                member.id.takeIf { it in dialog.libraryRemovalIds }
            }.toImmutableList()

            currentState.copy(
                dialog = dialog.copy(
                    members = reordered.toImmutableList(),
                    removableIds = reorderedRemovalIds,
                    libraryRemovalIds = reorderedLibraryRemovalIds,
                ),
            )
        }
    }

    fun setManageMergeTarget(animeId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.ManageMerge ?: return@updateSuccessState state
            if (dialog.members.none { it.id == animeId }) return@updateSuccessState state

            val updatedRemovals = dialog.removableIds.filterNot { it == animeId }.toImmutableList()
            val updatedLibraryRemovals = dialog.libraryRemovalIds.filterNot { it == animeId }.toImmutableList()
            state.copy(
                dialog = dialog.copy(
                    targetId = animeId,
                    removableIds = updatedRemovals,
                    libraryRemovalIds = updatedLibraryRemovals,
                ),
            )
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

    fun toggleMergedMemberLibraryRemoval(animeId: Long) {
        updateSuccessState { currentState ->
            val dialog = currentState.dialog as? Dialog.ManageMerge ?: return@updateSuccessState currentState
            if (animeId == dialog.targetId || dialog.members.none { it.id == animeId }) {
                return@updateSuccessState currentState
            }

            val updatedIds = dialog.libraryRemovalIds.toMutableList().apply {
                if (animeId in this) {
                    remove(animeId)
                } else {
                    add(animeId)
                }
            }.toImmutableList()

            currentState.copy(dialog = dialog.copy(libraryRemovalIds = updatedIds))
        }
    }

    fun saveMergeOrder() {
        val dialog = successState?.dialog as? Dialog.ManageMerge ?: return
        screenModelScope.launchIO {
            saveManageMerge(dialog, dialog.removableIds + dialog.libraryRemovalIds)
            removeMembersFromLibrary(dialog.libraryRemovalIds)
            dismissDialog()
        }
    }

    fun unmergeAll() {
        val currentState = successState ?: return
        screenModelScope.launchIO {
            updateMergedAnime.awaitDeleteGroup(currentState.mergeTargetId)
            dismissDialog()
        }
    }

    fun removeMergedAnime(animes: List<AnimeTitle>) {
        val currentState = successState ?: return
        screenModelScope.launchIO {
            updateMergedAnime.awaitDeleteGroup(currentState.mergeTargetId)
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
            val members = currentState.mergeGroupMemberIds.mapNotNull { memberId ->
                getAnimeOrNull(memberId)
            }.toImmutableList()
            updateSuccessState {
                it.copy(dialog = Dialog.RemoveMergedAnime(members))
            }
        }
    }

    private suspend fun buildSuccessState(
        anime: AnimeTitle,
        episodes: List<AnimeEpisode>,
        memberIds: List<Long>,
        mergeTargetId: Long,
        mergeGroupMemberIds: List<Long>,
        memberAnimes: List<AnimeTitle>,
        playbackStates: List<AnimePlaybackState>,
        categories: List<Category>,
    ): State.Success {
        val currentSuccess = successState
        val immutableMemberIds = memberIds.toImmutableList()
        val playbackStateByEpisodeId = playbackStates.associateBy(AnimePlaybackState::episodeId)
        val filteredEpisodes = episodes
            .filterEpisodes(anime, playbackStateByEpisodeId)
        val displayedEpisodes = filteredEpisodes
            .sortEpisodes(anime, memberIds)
        val primaryEpisodeId = selectPrimaryEpisodeId(
            filteredEpisodes.sortedForReading(anime, memberIds),
            playbackStateByEpisodeId,
        )
        val hasScheduleSupport = memberAnimes.any { memberAnime ->
            animeSourceManager.get(memberAnime.source) is AnimeScheduleSource
        }
        val schedule = when {
            !hasScheduleSupport -> ScheduleState.Unavailable
            currentSuccess == null -> ScheduleState.NotLoaded
            currentSuccess.memberIds != immutableMemberIds -> ScheduleState.NotLoaded
            currentSuccess.schedule == ScheduleState.Unavailable -> ScheduleState.NotLoaded
            else -> currentSuccess.schedule
        }

        return State.Success(
            anime = anime,
            sourceName = getSourceName(anime, memberIds),
            memberIds = immutableMemberIds,
            memberTitleById = memberAnimes.associate { it.id to it.displayTitle },
            mergedMemberTitles = memberAnimes.map(AnimeTitle::displayTitle)
                .filter { it.isNotBlank() }
                .distinct()
                .toImmutableList(),
            mergeTargetId = mergeTargetId,
            mergeGroupMemberIds = mergeGroupMemberIds.toImmutableList(),
            isFromSource = fromSource,
            episodes = displayedEpisodes.toImmutableList(),
            playbackStateByEpisodeId = playbackStateByEpisodeId,
            primaryEpisodeId = primaryEpisodeId,
            selection = displayedEpisodes.asSequence()
                .map(AnimeEpisode::id)
                .filter(selectedEpisodeIds::contains)
                .toSet()
                .toImmutableSet(),
            categories = categories.toImmutableList(),
            hasScheduleSupport = hasScheduleSupport,
            schedule = schedule,
            duplicateCandidates = if (fromSource && !anime.favorite) {
                currentSuccess?.duplicateCandidates.orEmpty()
            } else {
                emptyList()
            },
            isRefreshing = currentSuccess?.isRefreshing ?: false,
            dialog = currentSuccess?.dialog,
        )
    }

    private suspend fun mergedMemberAnimeFlow(memberIds: List<Long>) =
        combine(memberIds.map { memberId -> animeRepository.getAnimeByIdAsFlow(memberId) }) { animes ->
            animes.toList()
        }

    private fun mergedPlaybackStatesFlow(memberIds: List<Long>) =
        combine(memberIds.map(animePlaybackStateRepository::getByAnimeIdAsFlow)) { playbackStates ->
            playbackStates.flatMap(List<AnimePlaybackState>::toList)
        }

    private fun mergedCategoriesFlow(memberIds: List<Long>) =
        combine(memberIds.map(getAnimeCategories::subscribe)) { categories ->
            categories.flatMap(List<Category>::toList)
                .filterNot(Category::isSystemCategory)
                .distinctBy(Category::id)
                .sortedBy(Category::order)
        }

    private suspend fun saveManageMerge(dialog: Dialog.ManageMerge, animeIdsToRemove: Collection<Long>) {
        val remainingIds = dialogRemainingIds(dialog, animeIdsToRemove)
        val targetId = resolveManageMergeTargetId(dialog.targetId, remainingIds)

        if (targetId != null && remainingIds.size > 1) {
            updateMergedAnime.awaitMerge(targetId, remainingIds)
        } else {
            updateMergedAnime.awaitDeleteGroup(dialog.savedTargetId)
        }
    }

    private suspend fun getDisplayedMemberIds(): List<Long> {
        if (bypassMerge) return listOf(animeId)
        return getMergeGroupMemberIds()
    }

    private suspend fun getMergeGroupMemberIds(): List<Long> {
        return getMergedAnime.awaitGroupByAnimeId(animeId)
            .sortedBy(AnimeMerge::position)
            .map(AnimeMerge::animeId)
            .ifEmpty { listOf(animeId) }
    }

    private suspend fun getMergeMembers(): List<AnimeTitle> {
        return getDisplayedMemberIds().mapNotNull { memberId -> getAnimeOrNull(memberId) }
    }

    private suspend fun updateMergedMemberAnime(block: suspend (AnimeTitle) -> Unit) {
        getMergeMembers().forEach { memberAnime ->
            block(memberAnime)
        }
    }

    private suspend fun getAnimeOrNull(id: Long): AnimeTitle? {
        return runCatching { animeRepository.getAnimeById(id) }.getOrNull()
    }

    private suspend fun getMergeTargets(
        excludedAnimeIds: Set<Long>,
    ): kotlinx.collections.immutable.ImmutableList<AnimeMergeTarget> {
        val libraryAnime = animeRepository.getFavorites()
        val categoryIdsByAnimeId = libraryAnime.associate { anime ->
            anime.id to getAnimeCategories.await(anime.id).map(Category::id)
        }
        return buildAnimeMergeTargets(
            libraryAnime = libraryAnime,
            merges = getMergedAnime.awaitAll(),
            categoryIdsByAnimeId = categoryIdsByAnimeId,
            sourceNameForId = ::getSourceNameForId,
            multiSourceName = context.stringResource(MR.strings.multi_lang),
            excludedAnimeIds = excludedAnimeIds,
        )
    }

    private suspend fun createMergeEditorDialog(
        anime: AnimeTitle,
        target: AnimeMergeTarget,
    ): Dialog.EditMerge {
        val localAnime = networkToLocalAnime(anime)
        val orderedMembers = if (target.isMerged) {
            val membersById = target.memberAnimes.associateBy(AnimeTitle::id)
            getMergedAnime.awaitGroupByTargetId(target.id)
                .sortedBy(AnimeMerge::position)
                .mapNotNull { merge -> membersById[merge.animeId] }
                .ifEmpty { target.memberAnimes }
        } else {
            target.memberAnimes
        }

        val entries = buildList {
            orderedMembers.forEach { member ->
                add(
                    member.toMergeEditorEntry(
                        subtitle = buildMergeSubtitle(member),
                        isRemovable = true,
                        isMember = true,
                    ),
                )
            }
            if (none { it.id == localAnime.id }) {
                add(
                    localAnime.toMergeEditorEntry(
                        subtitle = buildMergeSubtitle(localAnime) + " • New",
                    ),
                )
            }
        }.toImmutableList()

        return Dialog.EditMerge(
            anime = localAnime,
            targetId = target.id,
            targetLocked = false,
            entries = entries,
            removedIds = emptySet(),
            libraryRemovalIds = emptySet(),
            categoryIds = target.categoryIds,
        )
    }

    private suspend fun ensureFavorite(
        anime: AnimeTitle,
        targetAnime: AnimeTitle,
        categoryIds: List<Long>,
    ) {
        if (!anime.favorite) {
            setAnimeDefaultEpisodeFlags.await(anime)
            animeRepository.update(
                AnimeTitleUpdate(
                    id = anime.id,
                    favorite = true,
                    dateAdded = Instant.now().toEpochMilli(),
                ),
            )
        }

        val appliedCategoryIds = if (categoryIds.isNotEmpty()) {
            categoryIds
        } else {
            getAnimeCategories.await(targetAnime.id).map(Category::id)
        }
        setAnimeCategories.await(anime.id, appliedCategoryIds)
    }

    private suspend fun removeMembersFromLibrary(animeIds: Collection<Long>) {
        animeIds.distinct().forEach { animeId ->
            val anime = getAnimeOrNull(animeId) ?: return@forEach
            animeRepository.update(
                AnimeTitleUpdate(
                    id = anime.id,
                    favorite = false,
                    dateAdded = 0L,
                ),
            )
        }
    }

    private fun getSourceName(anime: AnimeTitle, memberIds: List<Long>): String? {
        return if (memberIds.size > 1) {
            context.stringResource(MR.strings.multi_lang)
        } else {
            animeSourceManager.get(anime.source)?.name
        }
    }

    private fun getSourceNameForId(sourceId: Long): String {
        return animeSourceManager.get(sourceId)?.name
            ?: context.stringResource(MR.strings.source_not_installed, sourceId.toString())
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

    override fun onDispose() {
        duplicateObservationJob?.cancel()
        super.onDispose()
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
        AnimeTitle.EPISODE_SORTING_ALPHABET -> compareBy<AnimeEpisode>(
            { it.name.ifBlank { it.url } },
            { it.sourceOrder },
        )
        else -> compareBy<AnimeEpisode> { it.sourceOrder }
    }
    return if (ascending) {
        sortedWith(comparator)
    } else {
        sortedWith(comparator.reversed())
    }
}

private data class ScheduleMemberTarget(
    val anime: AnimeTitle,
    val memberId: Long,
    val memberOrder: Int,
    val source: AnimeScheduleSource,
)

private fun SAnimeScheduleEpisode.toUi(
    memberId: Long,
    memberOrder: Int,
    memberTitle: String,
) = AnimeScreenModel.AnimeScheduleEpisode(
    memberId = memberId,
    memberOrder = memberOrder,
    memberTitle = memberTitle,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    airDate = airDate,
    statusText = statusText,
    isAvailable = isAvailable,
)

private fun dialogRemainingIds(
    dialog: AnimeScreenModel.Dialog.ManageMerge,
    animeIdsToRemove: Collection<Long>,
): List<Long> {
    val animeIdsToRemoveSet = animeIdsToRemove.toSet()
    return dialog.members.map { it.id }
        .filterNot(animeIdsToRemoveSet::contains)
}

private fun resolveManageMergeTargetId(targetId: Long, remainingIds: List<Long>): Long? {
    return remainingIds.firstOrNull { it == targetId } ?: remainingIds.firstOrNull()
}

private object NoOpScreenMergedAnimeRepository : MergedAnimeRepository {
    override suspend fun getAll(): List<AnimeMerge> = emptyList()
    override fun subscribeAll() = flowOf(emptyList<AnimeMerge>())
    override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> = emptyList()
    override fun subscribeGroupByAnimeId(animeId: Long) = flowOf(emptyList<AnimeMerge>())
    override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> = emptyList()
    override suspend fun getTargetId(animeId: Long): Long? = null
    override fun subscribeTargetId(animeId: Long) = flowOf<Long?>(null)
    override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) = Unit
    override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) = Unit
    override suspend fun deleteGroup(targetAnimeId: Long) = Unit
}
