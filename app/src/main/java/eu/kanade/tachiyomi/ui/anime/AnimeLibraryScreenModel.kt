package eu.kanade.tachiyomi.ui.anime

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.ui.library.LibraryPage
import eu.kanade.tachiyomi.ui.library.LibraryPageTab
import eu.kanade.tachiyomi.ui.library.displayTitle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.feature.profiles.core.ProfileAwareStore
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.anime.service.sortedForReading
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.effectiveLibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeLibraryScreenModel(
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val animeHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = runCatching { Injekt.get<GetMergedAnime>() }
        .getOrElse { GetMergedAnime(NoOpLibraryMergedAnimeRepository) },
    private val updateMergedAnime: UpdateMergedAnime = runCatching { Injekt.get<UpdateMergedAnime>() }
        .getOrElse { UpdateMergedAnime(NoOpLibraryMergedAnimeRepository) },
    private val categoryRepository: tachiyomi.domain.category.repository.CategoryRepository = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val setSortModeForCategory: SetSortModeForCategory = Injekt.get(),
    private val profileStore: ProfileAwareStore = Injekt.get(),
    private val application: Application = Injekt.get(),
) : StateScreenModel<AnimeLibraryScreenModel.State>(State()) {

    init {
        mutableState.update { state ->
            state.copy(activePageIndex = libraryPreferences.lastUsedCategory.get())
        }

        profileStore.currentProfileIdFlow
            .drop(1)
            .onEach {
                mutableState.update { state ->
                    state.copy(
                        activePageIndex = libraryPreferences.lastUsedCategory.get(),
                        dialog = null,
                        searchQuery = null,
                        selection = setOf(),
                    )
                }
                lastSelectionPageId = null
            }
            .launchIn(screenModelScope)

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                animeRepository.getFavoritesAsFlow(),
                getMergedAnime.subscribeAll(),
                getCategories.subscribe(),
                libraryPreferences.groupType.changes(),
                libraryPreferences.sortingMode.changes(),
                libraryPreferences.randomSortSeed.changes(),
                libraryPreferences.categoryTabs.changes(),
                libraryPreferences.categoryNumberOfItems.changes(),
                libraryPreferences.unreadBadge.changes(),
                libraryPreferences.languageBadge.changes(),
                libraryPreferences.showContinueReadingButton.changes(),
                libraryPreferences.animeFilterUnwatched.changes(),
                libraryPreferences.animeFilterStarted.changes(),
            ) { values ->
                val favorites = values[1] as List<AnimeTitle>
                LibrarySnapshot(
                    searchQuery = values[0] as String?,
                    favorites = favorites,
                    merges = values[2] as List<AnimeMerge>,
                    categories = values[3] as List<Category>,
                    groupType = values[4] as LibraryGroupType,
                    sortMode = values[5] as LibrarySort,
                    randomSortSeed = values[6] as Int,
                    showCategoryTabs = values[7] as Boolean,
                    showItemCount = values[8] as Boolean,
                    showUnwatchedBadge = values[9] as Boolean,
                    showLanguageBadge = values[10] as Boolean,
                    showContinueWatchingButton = values[11] as Boolean,
                    sourceNamesBySourceId = favorites
                        .map(AnimeTitle::source)
                        .distinct()
                        .associateWith { sourceId ->
                            animeSourceManager.get(sourceId)?.name
                                ?: application.stringResource(
                                    tachiyomi.i18n.MR.strings.source_not_installed,
                                    sourceId.toString(),
                                )
                        },
                    filterUnwatched = values[12] as TriState,
                    filterStarted = values[13] as TriState,
                )
            }
                .distinctUntilChanged()
                .collectLatest { snapshot ->
                    val state = buildState(snapshot)
                    mutableState.value = state
                }
        }
    }

    private suspend fun buildState(snapshot: LibrarySnapshot): State {
        val previousState = mutableState.value
        return try {
            val animeIds = snapshot.favorites.map(AnimeTitle::id)
            val episodes = if (animeIds.isEmpty()) {
                emptyList()
            } else {
                animeEpisodeRepository.getEpisodesByAnimeIdsAsFlow(animeIds).first()
            }
            val playbackStates = animeIds.flatMap { animeId ->
                animePlaybackStateRepository.getByAnimeIdAsFlow(animeId).first()
            }
            val categoryIdsByAnimeId = categoryRepository.getAnimeCategoryIds(animeIds)

            val animeItems = buildAnimeItems(
                snapshot = snapshot,
                favorites = snapshot.favorites,
                episodes = episodes,
                playbackStates = playbackStates,
                categoryIdsByAnimeId = categoryIdsByAnimeId,
            )
            val filteredItems = animeItems.applyFilters(snapshot)
            val pages = buildPages(
                items = filteredItems,
                categories = snapshot.categories,
                showSystemCategory = animeItems.any { it.isUncategorized },
                groupType = snapshot.groupType,
                globalSort = snapshot.sortMode,
                randomSortSeed = snapshot.randomSortSeed,
            )

            State(
                isLoading = false,
                searchQuery = snapshot.searchQuery,
                showCategoryTabs = snapshot.showCategoryTabs,
                showItemCount = snapshot.showItemCount,
                showContinueWatchingButton = snapshot.showContinueWatchingButton,
                dialog = previousState.dialog,
                groupType = snapshot.groupType,
                categories = snapshot.categories,
                allLibraryItems = animeItems,
                libraryItems = filteredItems,
                pages = pages,
                activePageIndex = previousState.activePageIndex,
                selection = previousState.selection.intersect(filteredItems.map(AnimeLibraryItem::animeId).toSet()),
                hasActiveFilters = snapshot.hasActiveFilters,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            State(
                isLoading = false,
                errorMessage = withUIContext { application.stringResource(tachiyomi.i18n.MR.strings.unknown_error) },
                searchQuery = snapshot.searchQuery,
                showCategoryTabs = snapshot.showCategoryTabs,
                showItemCount = snapshot.showItemCount,
                showContinueWatchingButton = snapshot.showContinueWatchingButton,
                dialog = previousState.dialog,
                groupType = snapshot.groupType,
                categories = snapshot.categories,
                activePageIndex = previousState.activePageIndex,
                selection = previousState.selection,
                hasActiveFilters = snapshot.hasActiveFilters,
            )
        }
    }

    private fun List<AnimeLibraryItem>.applyFilters(snapshot: LibrarySnapshot): List<AnimeLibraryItem> {
        val query = snapshot.searchQuery?.trim()?.takeIf { it.isNotEmpty() }
        val filterUnwatched = snapshot.filterUnwatched
        val filterStarted = snapshot.filterStarted

        return filter { item ->
            (query == null || item.matches(query)) &&
                applyFilter(filterUnwatched) { item.hasUnwatched } &&
                applyFilter(filterStarted) { item.hasStarted }
        }
    }

    private fun buildAnimeItems(
        snapshot: LibrarySnapshot,
        favorites: List<AnimeTitle>,
        episodes: List<AnimeEpisode>,
        playbackStates: List<AnimePlaybackState>,
        categoryIdsByAnimeId: Map<Long, List<Long>>,
    ): List<AnimeLibraryItem> {
        val episodesByAnimeId = episodes.groupBy(AnimeEpisode::animeId)
        val playbackStateByEpisodeId = playbackStates.associateBy(AnimePlaybackState::episodeId)

        val itemsById = favorites.associate { anime ->
            val animeEpisodes = episodesByAnimeId[anime.id].orEmpty()
            val unwatchedCount = animeEpisodes.count { !it.completed }
            val hasUnwatched = animeEpisodes.any { !it.completed }
            val readingEpisodes = animeEpisodes.sortedForReading(anime)
            val inProgressPlayback = readingEpisodes.latestInProgressPlayback(playbackStateByEpisodeId)
            val hasStarted = inProgressPlayback != null || animeEpisodes.any { it.watched || it.completed }
            val primaryEpisode = readingEpisodes.selectPrimaryEpisode(playbackStateByEpisodeId)

            anime.id to AnimeLibraryItem(
                animeId = anime.id,
                anime = anime,
                memberAnimes = listOf(anime),
                episodes = animeEpisodes,
                playbackStates = animeEpisodes.mapNotNull { episode -> playbackStateByEpisodeId[episode.id] },
                title = anime.displayTitle,
                coverData = anime.toMangaCover(),
                sourceId = anime.source,
                displaySourceId = anime.source,
                sourceIds = setOf(anime.source),
                sourceName = snapshot.sourceNamesBySourceId.getValue(anime.source),
                sourceNames = setOf(snapshot.sourceNamesBySourceId.getValue(anime.source)),
                sourceLanguage = if (snapshot.showLanguageBadge) {
                    animeSourceManager.get(anime.source)?.lang.orEmpty()
                } else {
                    ""
                },
                primaryEpisodeAnimeId = primaryEpisode?.animeId,
                primaryEpisodeId = primaryEpisode?.id,
                hasUnwatched = hasUnwatched,
                unwatchedCount = unwatchedCount.toLong(),
                unwatchedBadgeCount = if (snapshot.showUnwatchedBadge) unwatchedCount.toLong() else 0L,
                hasStarted = hasStarted,
                hasInProgress = inProgressPlayback != null,
                progressFraction = inProgressPlayback?.progressFraction(),
                favoriteModifiedAt = anime.favoriteModifiedAt ?: anime.dateAdded,
                dateAdded = anime.dateAdded,
                lastUpdate = anime.lastUpdate,
                showContinueWatching = snapshot.showContinueWatchingButton,
                categoryIds = categoryIdsByAnimeId[anime.id].orEmpty(),
            )
        }

        val mergesByTargetId = snapshot.merges.groupBy(AnimeMerge::targetId)
        val mergeByAnimeId = snapshot.merges.associateBy(AnimeMerge::animeId)
        val collapsedItems = mutableListOf<AnimeLibraryItem>()
        val consumedIds = mutableSetOf<Long>()

        favorites.forEach { anime ->
            if (!consumedIds.add(anime.id)) return@forEach

            val targetId = mergeByAnimeId[anime.id]?.targetId
            val members = targetId
                ?.let { mergesByTargetId[it] }
                .orEmpty()
                .sortedBy(AnimeMerge::position)
                .mapNotNull { itemsById[it.animeId] }

            if (members.size > 1) {
                collapsedItems += mergeAnimeItem(
                    targetId = targetId ?: anime.id,
                    members = members,
                    showUnwatchedBadge = snapshot.showUnwatchedBadge,
                    showLanguageBadge = snapshot.showLanguageBadge,
                )
                consumedIds += members.map(AnimeLibraryItem::animeId)
            } else {
                collapsedItems += itemsById.getValue(anime.id)
            }
        }

        return collapsedItems
    }

    private fun mergeAnimeItem(
        targetId: Long,
        members: List<AnimeLibraryItem>,
        showUnwatchedBadge: Boolean,
        showLanguageBadge: Boolean,
    ): AnimeLibraryItem {
        val target = members.firstOrNull { it.animeId == targetId } ?: members.first()
        val sourceIds = members.flatMap { it.sourceIds }.toSet()
        val displaySourceId = if (sourceIds.size > 1) LibraryManga.MULTI_SOURCE_ID else sourceIds.first()
        val displaySourceName = if (displaySourceId == LibraryManga.MULTI_SOURCE_ID) {
            application.stringResource(MR.strings.multi_lang)
        } else {
            members.firstOrNull { displaySourceId in it.sourceIds }?.sourceName.orEmpty()
        }
        val playbackStateByEpisodeId = members.associatePlaybackStateByEpisodeId()
        val readingEpisodes = members.flatMap { it.episodes }
            .sortedForReading(target.anime, members.map(AnimeLibraryItem::animeId))
        val primaryEpisode = readingEpisodes.selectPrimaryEpisode(playbackStateByEpisodeId)
        val unwatchedCount = members.sumOf(AnimeLibraryItem::unwatchedCount)

        return target.copy(
            memberAnimes = members.flatMap(AnimeLibraryItem::memberAnimes),
            episodes = members.flatMap(AnimeLibraryItem::episodes),
            playbackStates = members.flatMap(AnimeLibraryItem::playbackStates),
            displaySourceId = displaySourceId,
            sourceIds = sourceIds,
            sourceName = displaySourceName,
            sourceNames = members.flatMap(AnimeLibraryItem::sourceNames).toSet(),
            sourceLanguage = when {
                !showLanguageBadge -> ""
                displaySourceId == LibraryManga.MULTI_SOURCE_ID -> LibraryManga.MULTI_SOURCE_ID.toString()
                else -> members.firstOrNull { displaySourceId in it.sourceIds }?.sourceLanguage.orEmpty()
            },
            primaryEpisodeAnimeId = primaryEpisode?.animeId,
            primaryEpisodeId = primaryEpisode?.id,
            hasUnwatched = members.any(AnimeLibraryItem::hasUnwatched),
            unwatchedCount = unwatchedCount,
            unwatchedBadgeCount = if (showUnwatchedBadge) unwatchedCount else 0L,
            hasStarted = members.any(AnimeLibraryItem::hasStarted),
            hasInProgress = primaryEpisode?.let { episode ->
                playbackStateByEpisodeId[episode.id]?.let(::isInProgressPlayback)
            } == true,
            progressFraction = primaryEpisode?.let { episode ->
                playbackStateByEpisodeId[episode.id]?.progressFraction()
            },
            favoriteModifiedAt = members.maxOfOrNull(AnimeLibraryItem::favoriteModifiedAt) ?: target.favoriteModifiedAt,
            lastUpdate = members.maxOfOrNull(AnimeLibraryItem::lastUpdate) ?: target.lastUpdate,
            categoryIds = members.flatMap(AnimeLibraryItem::categoryIds).distinct(),
        )
    }

    private fun buildPages(
        items: List<AnimeLibraryItem>,
        categories: List<Category>,
        showSystemCategory: Boolean,
        groupType: LibraryGroupType,
        globalSort: LibrarySort,
        randomSortSeed: Int,
    ): List<LibraryPage> {
        val visibleCategories = categories.filter { showSystemCategory || !it.isSystemCategory }
        val categoryTabs = visibleCategories.associate { category ->
            category.id to LibraryPageTab(
                id = "category:${category.id}",
                title = category.name,
                category = category,
            )
        }
        val sourceNames = items.map(AnimeLibraryItem::displaySourceId)
            .distinct()
            .associateWith { sourceId ->
                items.firstOrNull { it.displaySourceId == sourceId }?.sourceName
                    ?: if (sourceId == LibraryManga.MULTI_SOURCE_ID) {
                        application.stringResource(MR.strings.multi_lang)
                    } else {
                        application.stringResource(tachiyomi.i18n.MR.strings.source_not_installed, sourceId.toString())
                    }
            }
        val sourceTabs = sourceNames.keys.sortedWith { sourceId1, sourceId2 ->
            sourceNames.getValue(sourceId1)
                .compareToWithCollator(sourceNames.getValue(sourceId2))
                .takeIf { it != 0 }
                ?: sourceId1.compareTo(sourceId2)
        }.associateWith { sourceId ->
            LibraryPageTab(
                id = "source:$sourceId",
                title = sourceNames.getValue(sourceId),
            )
        }

        val pages = when (groupType) {
            LibraryGroupType.Category -> {
                visibleCategories.map { category ->
                    LibraryPage(
                        id = "category:${category.id}",
                        primaryTab = categoryTabs.getValue(category.id),
                        category = category,
                        itemIds = items.filter { it.matchesCategory(category.id) }.map(AnimeLibraryItem::animeId),
                    )
                }
            }
            LibraryGroupType.Extension -> {
                sourceTabs.map { (sourceId, tab) ->
                    LibraryPage(
                        id = tab.id,
                        primaryTab = tab,
                        sourceId = sourceId,
                        itemIds = items.filter { it.displaySourceId == sourceId }.map(AnimeLibraryItem::animeId),
                    )
                }
            }
            LibraryGroupType.ExtensionCategory -> {
                sourceTabs.flatMap { (sourceId, sourceTab) ->
                    visibleCategories.mapNotNull { category ->
                        val itemIds = items.filter { it.displaySourceId == sourceId && it.matchesCategory(category.id) }
                            .map(AnimeLibraryItem::animeId)
                        itemIds.takeIf { it.isNotEmpty() }?.let {
                            LibraryPage(
                                id = "${sourceTab.id}:${category.id}",
                                primaryTab = sourceTab,
                                secondaryTab = categoryTabs.getValue(category.id),
                                category = category,
                                sourceId = sourceId,
                                itemIds = itemIds,
                            )
                        }
                    }
                }
            }
            LibraryGroupType.CategoryExtension -> {
                visibleCategories.flatMap { category ->
                    val categoryItems = items.filter { it.matchesCategory(category.id) }
                    if (categoryItems.isEmpty()) {
                        listOf(
                            LibraryPage(
                                id = "category:${category.id}",
                                primaryTab = categoryTabs.getValue(category.id),
                                category = category,
                            ),
                        )
                    } else {
                        val categorySourceIds = categoryItems.map(AnimeLibraryItem::displaySourceId)
                            .distinct()
                            .sortedWith { sourceId1, sourceId2 ->
                                sourceNames.getValue(sourceId1)
                                    .compareToWithCollator(sourceNames.getValue(sourceId2))
                                    .takeIf { it != 0 }
                                    ?: sourceId1.compareTo(sourceId2)
                            }
                        categorySourceIds.map { sourceId ->
                            LibraryPage(
                                id = "category:${category.id}:source:$sourceId",
                                primaryTab = categoryTabs.getValue(category.id),
                                secondaryTab = sourceTabs.getValue(sourceId),
                                category = category,
                                sourceId = sourceId,
                                itemIds = categoryItems
                                    .filter { it.displaySourceId == sourceId }
                                    .map(AnimeLibraryItem::animeId),
                            )
                        }
                    }
                }
            }
        }

        return pages.map { page ->
            page.copy(
                itemIds = sortItemsForPage(
                    page = page,
                    items = items,
                    groupType = groupType,
                    globalSort = globalSort,
                    randomSortSeed = randomSortSeed,
                ).map(AnimeLibraryItem::animeId),
            )
        }
    }

    private fun sortItemsForPage(
        page: LibraryPage,
        items: List<AnimeLibraryItem>,
        groupType: LibraryGroupType,
        globalSort: LibrarySort,
        randomSortSeed: Int,
    ): List<AnimeLibraryItem> {
        val pageItems = items.filter { it.animeId in page.itemIds }
        val sort = page.category
            ?.takeIf { groupType == LibraryGroupType.Category }
            .effectiveLibrarySort(globalSort)

        val comparator = when (sort.type) {
            LibrarySort.Type.Alphabetical -> compareBy<AnimeLibraryItem> { it.title.lowercase() }
            LibrarySort.Type.LastUpdate -> compareBy<AnimeLibraryItem> { it.lastUpdate }
            LibrarySort.Type.UnreadCount -> compareBy<AnimeLibraryItem> { it.unwatchedCount }
            LibrarySort.Type.DateAdded -> compareBy<AnimeLibraryItem> { it.dateAdded }
            LibrarySort.Type.Random -> compareBy<AnimeLibraryItem> { (it.animeId xor randomSortSeed.toLong()) }
            LibrarySort.Type.LastRead,
            LibrarySort.Type.TotalChapters,
            LibrarySort.Type.LatestChapter,
            LibrarySort.Type.ChapterFetchDate,
            LibrarySort.Type.TrackerMean,
            -> compareBy<AnimeLibraryItem> { it.favoriteModifiedAt }
            else -> compareBy<AnimeLibraryItem> { it.favoriteModifiedAt }
        }

        val sorted = pageItems.sortedWith(comparator.thenBy { it.title.lowercase() })
        return if (sort.direction == LibrarySort.Direction.Descending) sorted.reversed() else sorted
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    private var lastSelectionPageId: String? = null

    fun clearSelection() {
        lastSelectionPageId = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    fun toggleSelection(page: LibraryPage, item: AnimeLibraryItem) {
        mutableState.update { state ->
            val newSelection = state.selection.toMutableSet().apply {
                if (!remove(item.animeId)) add(item.animeId)
            }
            lastSelectionPageId = page.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    fun toggleRangeSelection(page: LibraryPage, item: AnimeLibraryItem) {
        mutableState.update { state ->
            val newSelection = state.selection.toMutableSet().apply {
                val lastSelected = lastOrNull()
                if (lastSelectionPageId != page.id) {
                    add(item.animeId)
                    return@apply
                }

                val items = state.getItemsForPageId(page.id).map(AnimeLibraryItem::animeId)
                val lastItemIndex = items.indexOf(lastSelected)
                val currentItemIndex = items.indexOf(item.animeId)

                val selectionRange = when {
                    lastItemIndex < currentItemIndex -> lastItemIndex..currentItemIndex
                    currentItemIndex < lastItemIndex -> currentItemIndex..lastItemIndex
                    else -> return@apply
                }
                selectionRange.mapNotNull { items.getOrNull(it) }.let(::addAll)
            }
            lastSelectionPageId = page.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionPageId = null
        mutableState.update { state ->
            val newSelection = state.selection.toMutableSet().apply {
                state.getItemsForPageId(state.activePage?.id)
                    .map(AnimeLibraryItem::animeId)
                    .let(::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            val animeIds = getSelectedActionAnimeIds()
            if (animeIds.isEmpty()) return@launchIO

            val categories = state.value.availableCategories
            val categoryIdsByAnime = animeIds.map { animeId ->
                getAnimeCategories.await(animeId)
                    .map(Category::id)
                    .ifEmpty { listOf(Category.UNCATEGORIZED_ID) }
            }
            val common = categoryIdsByAnime
                .reduceOrNull { categoryIds1, categoryIds2 -> categoryIds1.intersect(categoryIds2.toSet()).toList() }
                .orEmpty()
                .filterNot { it == Category.UNCATEGORIZED_ID }
                .toSet()
            val mixed = categoryIdsByAnime
                .flatten()
                .filterNot { it == Category.UNCATEGORIZED_ID || it in common }
                .toSet()
            val preselected = categories
                .map {
                    when (it.id) {
                        in common -> CheckboxState.State.Checked(it)
                        in mixed -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }
                .toImmutableList()

            mutableState.update {
                it.copy(dialog = Dialog.ChangeCategory(animeIds = animeIds, initialSelection = preselected))
            }
        }
    }

    fun openRemoveAnimeDialog() {
        screenModelScope.launchIO {
            val animeIds = getSelectedActionAnimeIds()
            if (animeIds.isEmpty()) return@launchIO

            mutableState.update {
                it.copy(dialog = Dialog.RemoveAnime(animeIds))
            }
        }
    }

    fun canMergeSelection(): Boolean {
        val selection = state.value.selectedLibraryAnime
        if (selection.size < 2) return false

        val mergedSelections = selection.count(AnimeLibraryItem::isMerged)
        return mergedSelections <= 1
    }

    fun openMergeDialog() {
        screenModelScope.launchIO {
            val selection = state.value.selectedLibraryAnime
            if (selection.size < 2) return@launchIO

            val mergedSelections = selection.filter(AnimeLibraryItem::isMerged)
            if (mergedSelections.size > 1) return@launchIO

            val entries = selection
                .flatMap { item ->
                    item.memberAnimes.map { memberAnime ->
                        MergeEntry(
                            id = memberAnime.id,
                            anime = memberAnime,
                            isFromExistingMerge = item.isMerged,
                            subtitle = buildMergeSubtitle(memberAnime),
                        )
                    }
                }
                .distinctBy(MergeEntry::id)
                .toImmutableList()
            if (entries.size < 2) return@launchIO

            val existingMerge = mergedSelections.firstOrNull()
            mutableState.update {
                it.copy(
                    dialog = Dialog.MergeAnime(
                        entries = entries,
                        targetId = existingMerge?.animeId ?: entries.first().id,
                        targetLocked = false,
                    ),
                )
            }
        }
    }

    fun reorderMergeSelection(fromIndex: Int, toIndex: Int) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.MergeAnime ?: return@update state
            if (fromIndex !in dialog.entries.indices || toIndex !in dialog.entries.indices) return@update state
            val reordered = dialog.entries.toMutableList().apply {
                val item = removeAt(fromIndex)
                add(toIndex, item)
            }
            state.copy(dialog = dialog.copy(entries = reordered.toImmutableList()))
        }
    }

    fun setMergeTarget(animeId: Long) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.MergeAnime ?: return@update state
            if (dialog.targetLocked || dialog.entries.none { it.id == animeId }) return@update state
            state.copy(dialog = dialog.copy(targetId = animeId))
        }
    }

    fun confirmMergeSelection() {
        val dialog = state.value.dialog as? Dialog.MergeAnime ?: return
        screenModelScope.launchNonCancellable {
            val targetId = dialog.targetId.takeIf { targetAnimeId -> dialog.entries.any { it.id == targetAnimeId } }
                ?: dialog.entries.firstOrNull()?.id
                ?: return@launchNonCancellable
            val mergedIds = orderedMergeIds(dialog.entries)

            if (mergedIds.size > 1) {
                updateMergedAnime.awaitMerge(targetId, mergedIds)
            }
        }
        clearSelection()
        closeDialog()
    }

    fun setAnimeCategories(animeIds: List<Long>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchIO {
            animeIds.distinct().forEach { animeId ->
                val categoryIds = getAnimeCategories.await(animeId)
                    .map(Category::id)
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setAnimeCategories.await(animeId, categoryIds)
            }
        }
    }

    fun removeAnime(animeIds: List<Long>) {
        screenModelScope.launchIO {
            val distinctAnimeIds = animeIds.distinct()
            val removedMergesByTargetId = distinctAnimeIds.mapNotNull { animeId ->
                getMergedAnime.awaitTargetId(animeId)?.let { targetId -> targetId to animeId }
            }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            removedMergesByTargetId.forEach { (targetId, memberIds) ->
                updateMergedAnime.awaitRemoveMembers(targetId, memberIds)
            }

            distinctAnimeIds.forEach { animeId ->
                animeRepository.update(
                    AnimeTitleUpdate(
                        id = animeId,
                        favorite = false,
                        dateAdded = 0L,
                    ),
                )
            }
        }
    }

    fun markWatchedSelection(watched: Boolean) {
        val selectedAnime = state.value.selectedLibraryAnime
        if (selectedAnime.isEmpty()) return

        screenModelScope.launchNonCancellable {
            val animeIds = selectedAnime.flatMap(AnimeLibraryItem::memberAnimeIds).distinct()
            val episodeUpdates = animeIds.flatMap { animeId ->
                animeEpisodeRepository.getEpisodesByAnimeId(animeId)
                    .filter { episode ->
                        when (watched) {
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
            }
            if (episodeUpdates.isNotEmpty()) {
                animeEpisodeRepository.updateAll(episodeUpdates)
            }

            animeIds.forEach { animeId ->
                animePlaybackStateRepository.getByAnimeIdAsFlow(animeId)
                    .first()
                    .forEach { playbackState ->
                        animePlaybackStateRepository.upsert(
                            playbackState.copy(
                                positionMs = if (watched) playbackState.positionMs else 0L,
                                completed = watched,
                            ),
                        )
                    }
            }
        }
        clearSelection()
    }

    fun invertSelection() {
        lastSelectionPageId = null
        mutableState.update { state ->
            val newSelection = state.selection.toMutableSet().apply {
                val itemIds = state.getItemsForPageId(state.activePage?.id).map(AnimeLibraryItem::animeId)
                val (toRemove, toAdd) = itemIds.partition { it in this }
                removeAll(toRemove)
                addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun updateActivePageIndex(index: Int) {
        mutableState.update { state -> state.copy(activePageIndex = index) }
        val newIndex = state.value.coercedActivePageIndex

        libraryPreferences.lastUsedCategory.set(newIndex)
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(screenModelScope)
    }

    fun getSortMode(): PreferenceMutableState<LibrarySort> {
        return libraryPreferences.sortingMode.asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)
    }

    fun getFilterUnwatched(): PreferenceMutableState<TriState> {
        return libraryPreferences.animeFilterUnwatched.asState(screenModelScope)
    }

    fun getFilterStarted(): PreferenceMutableState<TriState> {
        return libraryPreferences.animeFilterStarted.asState(screenModelScope)
    }

    fun getShowUnwatchedBadge(): PreferenceMutableState<Boolean> {
        return libraryPreferences.unreadBadge.asState(screenModelScope)
    }

    fun getShowLanguageBadge(): PreferenceMutableState<Boolean> {
        return libraryPreferences.languageBadge.asState(screenModelScope)
    }

    fun getRandomLibraryItemForCurrentPage(): AnimeLibraryItem? {
        val currentState = state.value
        return currentState.getItemsForPageId(currentState.activePage?.id).randomOrNull()
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setGroup(type: LibraryGroupType) {
        libraryPreferences.groupType.set(type)
    }

    fun setSort(category: Category?, mode: LibrarySort.Type, direction: LibrarySort.Direction) {
        screenModelScope.launchIO {
            val targetCategory = category
                ?.takeIf { !it.isSystemCategory }
                ?.takeIf { libraryPreferences.groupType.get() == LibraryGroupType.Category }
            setSortModeForCategory.await(targetCategory, mode, direction)
        }
    }

    fun setShowCategoryTabs(enabled: Boolean) {
        libraryPreferences.categoryTabs.set(enabled)
    }

    fun setShowItemCount(enabled: Boolean) {
        libraryPreferences.categoryNumberOfItems.set(enabled)
    }

    fun setShowUnwatchedBadge(enabled: Boolean) {
        libraryPreferences.unreadBadge.set(enabled)
    }

    fun setShowLanguageBadge(enabled: Boolean) {
        libraryPreferences.languageBadge.set(enabled)
    }

    fun setShowContinueWatchingButton(enabled: Boolean) {
        libraryPreferences.showContinueReadingButton.set(enabled)
    }

    fun toggleFilter(preference: (LibraryPreferences) -> Preference<TriState>) {
        preference(libraryPreferences).getAndSet { it.next() }
    }

    private fun buildMergeSubtitle(anime: AnimeTitle): String {
        val sourceName = animeSourceManager.get(anime.source)?.name
            ?: application.stringResource(MR.strings.source_not_installed, anime.source.toString())
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

    private fun getSelectedActionAnimeIds(): List<Long> {
        return state.value.selectedLibraryAnime
            .flatMap(AnimeLibraryItem::memberAnimeIds)
            .distinct()
    }

    @Immutable
    data class AnimeLibraryItem(
        val animeId: Long,
        val anime: AnimeTitle,
        val memberAnimes: List<AnimeTitle>,
        val episodes: List<AnimeEpisode>,
        val playbackStates: List<AnimePlaybackState>,
        val title: String,
        val coverData: tachiyomi.domain.manga.model.MangaCover,
        val sourceId: Long,
        val displaySourceId: Long,
        val sourceIds: Set<Long>,
        val sourceName: String,
        val sourceNames: Set<String>,
        val sourceLanguage: String,
        val primaryEpisodeAnimeId: Long?,
        val primaryEpisodeId: Long?,
        val hasUnwatched: Boolean,
        val unwatchedCount: Long,
        val unwatchedBadgeCount: Long,
        val hasStarted: Boolean,
        val hasInProgress: Boolean,
        val progressFraction: Float?,
        val favoriteModifiedAt: Long,
        val dateAdded: Long,
        val lastUpdate: Long,
        val showContinueWatching: Boolean,
        val categoryIds: List<Long>,
    ) {
        val memberAnimeIds: List<Long>
            get() = memberAnimes.map(AnimeTitle::id)

        val isMerged: Boolean
            get() = memberAnimes.size > 1

        val isUncategorized: Boolean
            get() = categoryIds.isEmpty()

        val effectiveCategoryIds: List<Long>
            get() = categoryIds.ifEmpty { listOf(Category.UNCATEGORIZED_ID) }

        fun matchesCategory(categoryId: Long): Boolean {
            return when {
                categoryId == Category.UNCATEGORIZED_ID -> isUncategorized
                else -> categoryId in categoryIds
            }
        }

        fun matches(constraint: String): Boolean {
            if (constraint.startsWith("id:", true)) {
                return animeId == constraint.substringAfter("id:").toLongOrNull()
            } else if (constraint.startsWith("src:", true)) {
                val querySource = constraint.substringAfter("src:")
                return if (querySource.equals(MULTI_SOURCE_ID_ALIAS, ignoreCase = true)) {
                    displaySourceId == LibraryManga.MULTI_SOURCE_ID
                } else {
                    querySource.toLongOrNull() in sourceIds
                }
            }

            return memberAnimes.any { anime ->
                anime.displayTitle.contains(constraint, ignoreCase = true) ||
                    anime.title.contains(constraint, ignoreCase = true) ||
                    (anime.originalTitle?.contains(constraint, ignoreCase = true) ?: false) ||
                    (anime.studio?.contains(constraint, ignoreCase = true) ?: false) ||
                    (anime.producer?.contains(constraint, ignoreCase = true) ?: false) ||
                    (anime.director?.contains(constraint, ignoreCase = true) ?: false) ||
                    (anime.writer?.contains(constraint, ignoreCase = true) ?: false) ||
                    (anime.description?.contains(constraint, ignoreCase = true) ?: false)
            } || constraint.split(",").map { it.trim() }.all { subconstraint ->
                checkNegatableConstraint(subconstraint) {
                    sourceNames.any { sourceName -> sourceName.contains(it, ignoreCase = true) } ||
                        memberAnimes.any { anime ->
                            anime.genre?.any { genreEntry ->
                                genreEntry.equals(it, ignoreCase = true)
                            } == true
                        }
                }
            }
        }

        private fun checkNegatableConstraint(
            constraint: String,
            predicate: (String) -> Boolean,
        ): Boolean {
            return if (constraint.startsWith("-")) {
                !predicate(constraint.substringAfter("-").trimStart())
            } else {
                predicate(constraint)
            }
        }
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog

        data class ChangeCategory(
            val animeIds: List<Long>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog

        data class MergeAnime(
            val entries: ImmutableList<MergeEntry>,
            val targetId: Long,
            val targetLocked: Boolean,
        ) : Dialog

        data class RemoveAnime(
            val animeIds: List<Long>,
        ) : Dialog
    }

    @Immutable
    data class MergeEntry(
        val id: Long,
        val anime: AnimeTitle,
        val isFromExistingMerge: Boolean,
        val subtitle: String,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val searchQuery: String? = null,
        val showCategoryTabs: Boolean = false,
        val showItemCount: Boolean = false,
        val showContinueWatchingButton: Boolean = false,
        val dialog: Dialog? = null,
        val groupType: LibraryGroupType = LibraryGroupType.Category,
        val categories: List<Category> = emptyList(),
        val allLibraryItems: List<AnimeLibraryItem> = emptyList(),
        val libraryItems: List<AnimeLibraryItem> = emptyList(),
        val pages: List<LibraryPage> = emptyList(),
        val activePageIndex: Int = 0,
        val selection: Set<Long> = emptySet(),
        val hasActiveFilters: Boolean = false,
    ) {
        val coercedActivePageIndex = activePageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))

        val activePage: LibraryPage?
            get() = pages.getOrNull(coercedActivePageIndex)

        val activeSortCategory: Category?
            get() = activePage?.category
                ?.takeIf { groupType == LibraryGroupType.Category }

        val selectionMode: Boolean
            get() = selection.isNotEmpty()

        val selectedLibraryAnime: List<AnimeLibraryItem>
            get() = allLibraryItems.filter { it.animeId in selection }

        val availableCategories: List<Category>
            get() = categories.asSequence()
                .filterNot(Category::isSystemCategory)
                .sortedBy(Category::order)
                .toList()

        val isLibraryEmpty: Boolean
            get() = allLibraryItems.isEmpty()

        fun getItemsForPage(page: LibraryPage): List<AnimeLibraryItem> {
            return page.itemIds.mapNotNull { animeId -> libraryItems.firstOrNull { it.animeId == animeId } }
        }

        fun getItemsForPageId(pageId: String?): List<AnimeLibraryItem> {
            val page = pages.firstOrNull { it.id == pageId } ?: return emptyList()
            return getItemsForPage(page)
        }

        fun getItemCountForPage(page: LibraryPage): Int? {
            return if (showItemCount || !searchQuery.isNullOrEmpty()) page.itemIds.size else null
        }

        fun getItemCountForPrimaryTab(tab: LibraryPageTab): Int? {
            if (!showItemCount && searchQuery.isNullOrEmpty()) return null
            return pages
                .filter { it.primaryTab.id == tab.id }
                .flatMap(LibraryPage::itemIds)
                .distinct()
                .size
        }

        fun getToolbarTitle(defaultTitle: String, defaultCategoryTitle: String): LibraryToolbarTitle {
            val currentPage = activePage ?: return LibraryToolbarTitle(defaultTitle)
            val title = if (showCategoryTabs) defaultTitle else currentPage.displayTitle(defaultCategoryTitle)
            val count = when {
                !showItemCount -> null
                !showCategoryTabs -> getItemCountForPage(currentPage)
                else -> libraryItems.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }

    private data class LibrarySnapshot(
        val searchQuery: String?,
        val favorites: List<AnimeTitle>,
        val merges: List<AnimeMerge>,
        val categories: List<Category>,
        val groupType: LibraryGroupType,
        val sortMode: LibrarySort,
        val randomSortSeed: Int,
        val showCategoryTabs: Boolean,
        val showItemCount: Boolean,
        val showUnwatchedBadge: Boolean,
        val showLanguageBadge: Boolean,
        val showContinueWatchingButton: Boolean,
        val sourceNamesBySourceId: Map<Long, String>,
        val filterUnwatched: TriState,
        val filterStarted: TriState,
    ) {
        val hasActiveFilters: Boolean
            get() = filterUnwatched != TriState.DISABLED || filterStarted != TriState.DISABLED
    }
}

private fun List<AnimeEpisode>.latestInProgressPlayback(
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
): AnimePlaybackState? {
    return asSequence()
        .mapNotNull { episode -> playbackStateByEpisodeId[episode.id] }
        .filter(::isInProgressPlayback)
        .maxByOrNull(AnimePlaybackState::lastWatchedAt)
}

private fun List<AnimeEpisode>.selectPrimaryEpisode(
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
): AnimeEpisode? {
    val inProgressEpisode = latestInProgressPlayback(playbackStateByEpisodeId)
        ?.let { playbackState -> firstOrNull { episode -> episode.id == playbackState.episodeId } }
    if (inProgressEpisode != null) {
        return inProgressEpisode
    }

    return firstOrNull { !it.completed } ?: firstOrNull()
}

private fun List<AnimeLibraryScreenModel.AnimeLibraryItem>.associatePlaybackStateByEpisodeId():
    Map<Long, AnimePlaybackState> {
    return flatMap(AnimeLibraryScreenModel.AnimeLibraryItem::playbackStates)
        .associateBy(AnimePlaybackState::episodeId)
}

private fun orderedMergeIds(entries: List<AnimeLibraryScreenModel.MergeEntry>): List<Long> {
    return entries.map(AnimeLibraryScreenModel.MergeEntry::id).distinct()
}

private object NoOpLibraryMergedAnimeRepository : MergedAnimeRepository {
    override suspend fun getAll(): List<AnimeMerge> = emptyList()
    override fun subscribeAll() = kotlinx.coroutines.flow.flowOf(emptyList<AnimeMerge>())
    override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> = emptyList()
    override fun subscribeGroupByAnimeId(animeId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<AnimeMerge>())
    override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> = emptyList()
    override suspend fun getTargetId(animeId: Long): Long? = null
    override fun subscribeTargetId(animeId: Long) = kotlinx.coroutines.flow.flowOf(null)
    override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) = Unit
    override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) = Unit
    override suspend fun deleteGroup(targetAnimeId: Long) = Unit
}

private fun isInProgressPlayback(playbackState: AnimePlaybackState): Boolean {
    return !playbackState.completed && playbackState.positionMs > 0L && playbackState.durationMs > 0L
}

private fun AnimePlaybackState.progressFraction(): Float {
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

private const val MULTI_SOURCE_ID_ALIAS = "multi"
