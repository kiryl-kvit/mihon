package eu.kanade.tachiyomi.ui.library

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.chapter.isDownloaded
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import mihon.core.common.utils.mutate
import mihon.feature.profiles.core.ProfileAwareStore
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.effectiveLibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.UpdateMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

class LibraryScreenModel(
    private val context: Context,
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracksPerManga: GetTracksPerManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    private val updateMergedManga: UpdateMergedManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val profileStore: ProfileAwareStore = Injekt.get(),
) : StateScreenModel<LibraryScreenModel.State>(State()) {

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
                        selection = emptySet(),
                        dialog = null,
                    )
                }
                lastSelectionPageId = null
            }
            .launchIn(screenModelScope)
        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                getCategories.subscribe(),
                getFavoritesFlow(),
                combine(getTracksPerManga.subscribe(), getTrackingFiltersFlow(), ::Pair),
                getLibraryItemPreferencesFlow(),
            ) { searchQuery, categories, favorites, (tracksMap, trackingFilters), itemPreferences ->
                val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0) }
                val filteredFavorites = favorites
                    .applyFilters(tracksMap, trackingFilters, itemPreferences)
                    .let { if (searchQuery == null) it else it.filter { m -> m.matches(searchQuery) } }

                LibraryData(
                    isInitialized = true,
                    showSystemCategory = showSystemCategory,
                    categories = categories,
                    favorites = filteredFavorites,
                    tracksMap = tracksMap,
                    loggedInTrackerIds = trackingFilters.keys,
                )
            }
                .distinctUntilChanged()
                .collectLatest { libraryData ->
                    mutableState.update { state ->
                        state.copy(libraryData = libraryData)
                    }
                }
        }

        screenModelScope.launchIO {
            observeGroupedLibraryPages(
                libraryData = state
                    .dropWhile { !it.libraryData.isInitialized }
                    .map { it.libraryData }
                    .distinctUntilChanged(),
                groupType = libraryPreferences.groupType.changes(),
                sortingMode = libraryPreferences.sortingMode.changes(),
                randomSortSeed = libraryPreferences.randomSortSeed.changes(),
                applyGrouping = { data, groupType ->
                    data.favorites.applyGrouping(data.categories, data.showSystemCategory, groupType)
                },
                applySort = { pages, data, groupType, sortingMode, randomSortSeed ->
                    pages.applySort(
                        favoritesById = data.favoritesById,
                        trackMap = data.tracksMap,
                        loggedInTrackerIds = data.loggedInTrackerIds,
                        groupType = groupType,
                        globalSort = sortingMode,
                        randomSortSeed = randomSortSeed,
                    )
                },
            ).collectLatest { (groupType, groupedFavorites) ->
                mutableState.update { state ->
                    state.copy(
                        isLoading = false,
                        groupedFavorites = groupedFavorites,
                        groupType = groupType,
                    )
                }
            }
        }

        combine(
            libraryPreferences.categoryTabs.changes(),
            libraryPreferences.categoryNumberOfItems.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(screenModelScope)

        combine(
            getLibraryItemPreferencesFlow(),
            getTrackingFiltersFlow(),
        ) { prefs, trackFilters ->
            listOf(
                prefs.globalFilterDownloaded,
                prefs.filterDownloaded,
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
                prefs.filterCompleted,
                prefs.filterIntervalCustom,
                *trackFilters.values.toTypedArray(),
            )
                .any { it != TriState.DISABLED }
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)
    }

    private fun List<LibraryItem>.applyFilters(
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        preferences: ItemPreferences,
    ): List<LibraryItem> {
        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutsideReleasePeriod = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterUnread = preferences.filterUnread
        val filterStarted = preferences.filterStarted
        val filterBookmarked = preferences.filterBookmarked
        val filterCompleted = preferences.filterCompleted
        val filterIntervalCustom = preferences.filterIntervalCustom

        val isNotLoggedInAnyTrack = trackingFilter.isEmpty()

        val excludedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
        val includedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        val filterFnDownloaded: (LibraryItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.libraryManga.manga.isLocal() ||
                    it.downloadCount > 0 ||
                    downloadManager.getDownloadCount(it.libraryManga.manga) > 0
            }
        }

        val filterFnUnread: (LibraryItem) -> Boolean = {
            applyFilter(filterUnread) { it.libraryManga.unreadCount > 0 }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = {
            applyFilter(filterStarted) { it.libraryManga.hasStarted }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = {
            applyFilter(filterBookmarked) { it.libraryManga.hasBookmarks }
        }

        val filterFnCompleted: (LibraryItem) -> Boolean = {
            applyFilter(filterCompleted) { it.libraryManga.manga.status.toInt() == SManga.COMPLETED }
        }

        val filterFnIntervalCustom: (LibraryItem) -> Boolean = {
            if (skipOutsideReleasePeriod) {
                applyFilter(filterIntervalCustom) { it.libraryManga.manga.fetchInterval < 0 }
            } else {
                true
            }
        }

        val filterFnTracking: (LibraryItem) -> Boolean = tracking@{ item ->
            if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) return@tracking true

            val mangaTracks = trackMap[item.id].orEmpty().map { it.trackerId }

            val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.fastAny { it in excludedTracks }
            val isIncluded = includedTracks.isEmpty() || mangaTracks.fastAny { it in includedTracks }

            !isExcluded && isIncluded
        }

        return fastFilter {
            filterFnDownloaded(it) &&
                filterFnUnread(it) &&
                filterFnStarted(it) &&
                filterFnBookmarked(it) &&
                filterFnCompleted(it) &&
                filterFnIntervalCustom(it) &&
                filterFnTracking(it)
        }
    }

    private fun List<LibraryItem>.applyGrouping(
        categories: List<Category>,
        showSystemCategory: Boolean,
        groupType: LibraryGroupType,
    ): List<LibraryPage> {
        val visibleCategories = categories.filter { showSystemCategory || !it.isSystemCategory }
        val categoryTabs = visibleCategories.associate { category ->
            category.id to LibraryPageTab(
                id = "category:${category.id}",
                title = category.name,
                category = category,
            )
        }
        val sourceNames = map { it.libraryManga.displaySourceId }
            .distinct()
            .associateWith { sourceId ->
                if (sourceId == LibraryManga.MULTI_SOURCE_ID) {
                    context.stringResource(MR.strings.multi_lang)
                } else {
                    sourceManager.getOrStub(sourceId).getNameForMangaInfo()
                }
            }
        val sourceIds = sourceNames.keys.sortedWith { sourceId1, sourceId2 ->
            sourceNames.getValue(sourceId1)
                .compareToWithCollator(sourceNames.getValue(sourceId2))
                .takeIf { it != 0 }
                ?: sourceId1.compareTo(sourceId2)
        }
        val sourceTabs = sourceIds.associateWith { sourceId ->
            LibraryPageTab(
                id = "source:$sourceId",
                title = sourceNames.getValue(sourceId),
            )
        }

        return when (groupType) {
            LibraryGroupType.Category -> {
                visibleCategories.map { category ->
                    LibraryPage(
                        id = "category:${category.id}",
                        primaryTab = categoryTabs.getValue(category.id),
                        category = category,
                        itemIds = fastFilter { category.id in it.libraryManga.categories }
                            .fastMap(LibraryItem::id),
                    )
                }
            }
            LibraryGroupType.Extension -> {
                sourceIds.map { sourceId ->
                    LibraryPage(
                        id = "source:$sourceId",
                        primaryTab = sourceTabs.getValue(sourceId),
                        sourceId = sourceId,
                        itemIds = fastFilter { it.libraryManga.displaySourceId == sourceId }
                            .fastMap(LibraryItem::id),
                    )
                }
            }
            LibraryGroupType.ExtensionCategory -> {
                buildList {
                    sourceIds.forEach { sourceId ->
                        val sourceItems = this@applyGrouping.fastFilter { it.libraryManga.displaySourceId == sourceId }
                        visibleCategories.forEach { category ->
                            val itemIds = sourceItems.fastFilter { category.id in it.libraryManga.categories }
                                .fastMap(LibraryItem::id)
                            if (itemIds.isNotEmpty()) {
                                add(
                                    LibraryPage(
                                        id = "source:$sourceId:category:${category.id}",
                                        primaryTab = sourceTabs.getValue(sourceId),
                                        secondaryTab = categoryTabs.getValue(category.id),
                                        category = category,
                                        sourceId = sourceId,
                                        itemIds = itemIds,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            LibraryGroupType.CategoryExtension -> {
                buildList {
                    visibleCategories.forEach { category ->
                        val categoryItems = this@applyGrouping.fastFilter { category.id in it.libraryManga.categories }
                        if (categoryItems.isEmpty()) {
                            add(
                                LibraryPage(
                                    id = "category:${category.id}",
                                    primaryTab = categoryTabs.getValue(category.id),
                                    category = category,
                                ),
                            )
                        } else {
                            val categorySourceIds = categoryItems.map { it.libraryManga.displaySourceId }
                                .distinct()
                                .sortedWith { sourceId1, sourceId2 ->
                                    sourceNames.getValue(sourceId1)
                                        .compareToWithCollator(sourceNames.getValue(sourceId2))
                                        .takeIf { it != 0 }
                                        ?: sourceId1.compareTo(sourceId2)
                                }
                            categorySourceIds.forEach { sourceId ->
                                add(
                                    LibraryPage(
                                        id = "category:${category.id}:source:$sourceId",
                                        primaryTab = categoryTabs.getValue(category.id),
                                        secondaryTab = sourceTabs.getValue(sourceId),
                                        category = category,
                                        sourceId = sourceId,
                                        itemIds = categoryItems.fastFilter {
                                            it.libraryManga.displaySourceId == sourceId
                                        }
                                            .fastMap(LibraryItem::id),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun List<LibraryPage>.applySort(
        favoritesById: Map<Long, LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
        groupType: LibraryGroupType,
        globalSort: LibrarySort,
        randomSortSeed: Int,
    ): List<LibraryPage> {
        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { manga1, manga2 ->
            val title1 = manga1.libraryManga.manga.presentationTitle().lowercase()
            val title2 = manga2.libraryManga.manga.presentationTitle().lowercase()
            title1.compareToWithCollator(title2)
        }

        val defaultTrackerScoreSortValue = -1.0
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { e -> e.id }
            trackMap.mapValues { entry ->
                when {
                    entry.value.isEmpty() -> null
                    else ->
                        entry.value
                            .mapNotNull { trackerMap[it.trackerId]?.get10PointScore(it) }
                            .average()
                }
            }
        }

        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { manga1, manga2 ->
            when (this.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(manga1, manga2)
                }
                LibrarySort.Type.LastRead -> {
                    manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
                }
                LibrarySort.Type.LastUpdate -> {
                    manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
                }
                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                    manga1.libraryManga.unreadCount == 0L -> if (this.isAscending) 1 else -1
                    manga2.libraryManga.unreadCount == 0L -> if (this.isAscending) -1 else 1
                    else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> {
                    manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
                }
                LibrarySort.Type.LatestChapter -> {
                    manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
                }
                LibrarySort.Type.ChapterFetchDate -> {
                    manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
                }
                LibrarySort.Type.TrackerMean -> {
                    val item1Score = trackerScores[manga1.id] ?: defaultTrackerScoreSortValue
                    val item2Score = trackerScores[manga2.id] ?: defaultTrackerScoreSortValue
                    item1Score.compareTo(item2Score)
                }
                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
            }
        }

        return map { page ->
            val sort = if (groupType == LibraryGroupType.Category) {
                page.category.effectiveLibrarySort(globalSort)
            } else {
                globalSort
            }
            if (sort.type == LibrarySort.Type.Random) {
                return@map page.copy(
                    itemIds = page.itemIds.shuffled(Random(randomSortSeed)),
                )
            }

            val manga = page.itemIds.mapNotNull { favoritesById[it] }

            val comparator = sort.comparator()
                .let { if (sort.isAscending) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            page.copy(itemIds = manga.sortedWith(comparator).map { it.id })
        }
    }

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge.changes(),
            libraryPreferences.unreadBadge.changes(),
            libraryPreferences.localBadge.changes(),
            libraryPreferences.languageBadge.changes(),
            libraryPreferences.autoUpdateMangaRestrictions.changes(),

            libraryPreferences.downloadedOnly.changes(),
            libraryPreferences.filterDownloaded.changes(),
            libraryPreferences.filterUnread.changes(),
            libraryPreferences.filterStarted.changes(),
            libraryPreferences.filterBookmarked.changes(),
            libraryPreferences.filterCompleted.changes(),
            libraryPreferences.filterIntervalCustom.changes(),
        ) {
            ItemPreferences(
                downloadBadge = it[0] as Boolean,
                unreadBadge = it[1] as Boolean,
                localBadge = it[2] as Boolean,
                languageBadge = it[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (it[4] as Set<*>),
                globalFilterDownloaded = it[5] as Boolean,
                filterDownloaded = it[6] as TriState,
                filterUnread = it[7] as TriState,
                filterStarted = it[8] as TriState,
                filterBookmarked = it[9] as TriState,
                filterCompleted = it[10] as TriState,
                filterIntervalCustom = it[11] as TriState,
            )
        }
    }

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
        ) { libraryManga, preferences, _ ->
            val multiSourceName = context.stringResource(MR.strings.multi_lang)
            libraryManga.map { manga ->
                val sourceName = if (manga.displaySourceId == LibraryManga.MULTI_SOURCE_ID) {
                    multiSourceName
                } else {
                    sourceManager.getOrStub(manga.displaySourceId).getNameForMangaInfo()
                }
                LibraryItem(
                    libraryManga = manga,
                    downloadCount = if (preferences.downloadBadge) {
                        manga.memberMangas.sumOf { memberManga ->
                            downloadManager.getDownloadCount(memberManga).toLong()
                        }
                    } else {
                        0
                    },
                    unreadCount = if (preferences.unreadBadge) {
                        manga.unreadCount
                    } else {
                        0
                    },
                    isLocal = if (preferences.localBadge) {
                        manga.sourceIds.size == 1 && manga.manga.isLocal()
                    } else {
                        false
                    },
                    sourceLanguage = if (preferences.languageBadge) {
                        if (manga.displaySourceId == LibraryManga.MULTI_SOURCE_ID) {
                            LibraryManga.MULTI_SOURCE_ID.toString()
                        } else {
                            sourceManager.getOrStub(manga.displaySourceId).lang
                        }
                    } else {
                        ""
                    },
                    sourceName = sourceName,
                )
            }
        }
    }

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: LibraryManga): Chapter? {
        val chapters = getMangaWithChapters.awaitChapters(manga.id, applyScanlatorFilter = true)
        val mangaById = chapters.map(Chapter::mangaId).distinct().associateWith { chapterMangaId ->
            getManga.await(chapterMangaId) ?: manga.manga
        }
        return chapters.getNextUnread(manga.manga, downloadManager, mangaById)
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(action: DownloadAction) {
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(10)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(25)
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(null)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters()
        }
        clearSelection()
    }

    private fun downloadNextChapters(amount: Int?) {
        screenModelScope.launchNonCancellable {
            val mangas = getSelectedActionManga()
            mangas.forEach { manga ->
                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        val chapterManga = getManga.await(chapter.mangaId) ?: return@fastFilterNot true
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            chapter.isDownloaded(chapterManga, downloadCache)
                    }
                    .let { if (amount != null) it.take(amount) else it }

                chapters.groupBy { it.mangaId }
                    .forEach { (chapterMangaId, mangaChapters) ->
                        val chapterManga = getManga.await(chapterMangaId) ?: return@forEach
                        downloadManager.downloadChapters(chapterManga, mangaChapters)
                    }
            }
        }
    }

    private fun downloadBookmarkedChapters() {
        screenModelScope.launchNonCancellable {
            val mangas = getSelectedActionManga()
            mangas.forEach { manga ->
                val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        screenModelScope.launchNonCancellable {
            val selection = getSelectedActionManga()
            selection.forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        screenModelScope.launchNonCancellable {
            val distinctMangas = mangas.distinctBy(Manga::id)

            if (deleteFromLibrary) {
                val removedMergesByTargetId = distinctMangas.mapNotNull { manga ->
                    getMergedManga.awaitTargetId(manga.id)?.let { targetId -> targetId to manga.id }
                }
                    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                removedMergesByTargetId.forEach { (targetId, mangaIds) ->
                    updateMergedManga.awaitRemoveMembers(targetId, mangaIds)
                }

                val toDelete = distinctMangas.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                distinctMangas.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            mangaList.forEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(manga.id, categoryIds)
            }
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)
    }

    fun getRandomLibraryItemForCurrentPage(): LibraryItem? {
        val state = state.value
        return state.getItemsForPageId(state.activePage?.id).randomOrNull()
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    private var lastSelectionPageId: String? = null

    fun clearSelection() {
        lastSelectionPageId = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    fun toggleSelection(page: LibraryPage, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { set ->
                if (!set.remove(manga.id)) set.add(manga.id)
            }
            lastSelectionPageId = page.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same group as the given manga
     */
    fun toggleRangeSelection(page: LibraryPage, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                if (lastSelectionPageId != page.id) {
                    list.add(manga.id)
                    return@mutate
                }

                val items = state.getItemsForPageId(page.id).fastMap { it.id }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga.id)

                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> lastMangaIndex..curMangaIndex
                    curMangaIndex < lastMangaIndex -> curMangaIndex..lastMangaIndex
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                selectionRange.mapNotNull { items[it] }.let(list::addAll)
            }
            lastSelectionPageId = page.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionPageId = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                state.getItemsForPageId(state.activePage?.id).map { it.id }.let(list::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection() {
        lastSelectionPageId = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val itemIds = state.getItemsForPageId(state.activePage?.id).fastMap { it.id }
                val (toRemove, toAdd) = itemIds.partition { it in list }
                list.removeAll(toRemove)
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateActivePageIndex(index: Int) {
        val newIndex = mutableState.updateAndGet { state ->
            state.copy(activePageIndex = index)
        }
            .coercedActivePageIndex

        libraryPreferences.lastUsedCategory.set(newIndex)
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            val mangaList = getSelectedActionManga()

            // Hide the default category because it has a different behavior than the ones from db.
            val categories = state.value.libraryData.categories.filter { it.id != 0L }

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(mangaList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(mangaList)
            val preselected = categories
                .map {
                    when (it) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }
                .toImmutableList()
            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        screenModelScope.launchIO {
            val mangaList = getSelectedActionManga()
            val containsLocalManga = mangaList.any(Manga::isLocal)
            val containsMergedManga = state.value.selectedLibraryManga.any(LibraryManga::isMerged)
            mutableState.update {
                it.copy(
                    dialog = Dialog.DeleteManga(
                        manga = mangaList,
                        containsLocalManga = containsLocalManga,
                        containsMergedManga = containsMergedManga,
                    ),
                )
            }
        }
    }

    fun canMergeSelection(): Boolean {
        val selection = state.value.selectedLibraryManga
        if (selection.size < 2) return false

        val mergedSelections = selection.count { it.isMerged }
        return mergedSelections <= 1
    }

    fun openMergeDialog() {
        screenModelScope.launchIO {
            val dialog = buildMergeDialog(state.value.selectedLibraryManga) ?: return@launchIO
            mutableState.update {
                it.copy(
                    dialog = dialog,
                )
            }
        }
    }

    fun reorderMergeSelection(fromIndex: Int, toIndex: Int) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.MergeManga ?: return@update state
            if (fromIndex !in dialog.entries.indices || toIndex !in dialog.entries.indices) return@update state
            val reordered = dialog.entries.toMutableList().apply {
                val item = removeAt(fromIndex)
                add(toIndex, item)
            }
            state.copy(dialog = dialog.copy(entries = reordered.toImmutableList()))
        }
    }

    fun setMergeTarget(mangaId: Long) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.MergeManga ?: return@update state
            if (dialog.targetLocked || dialog.entries.none { it.id == mangaId }) return@update state
            state.copy(dialog = dialog.copy(targetId = mangaId))
        }
    }

    fun confirmMergeSelection() {
        val dialog = state.value.dialog as? Dialog.MergeManga ?: return
        screenModelScope.launchNonCancellable {
            val targetId = dialog.targetId.takeIf { targetId -> dialog.entries.any { it.id == targetId } }
                ?: dialog.entries.firstOrNull()?.id
                ?: return@launchNonCancellable
            val mergedIds = orderedMergeIds(dialog.entries)

            if (mergedIds.size > 1) {
                updateMergedManga.awaitMerge(targetId, mergedIds)
            }
        }
        clearSelection()
        closeDialog()
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    private suspend fun getSelectedActionManga(): List<Manga> {
        return state.value.selectedLibraryManga
            .flatMap(LibraryManga::memberMangas)
            .distinctBy(Manga::id)
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog
        data class ChangeCategory(
            val manga: List<Manga>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class MergeManga(
            val entries: ImmutableList<MergeEntry>,
            val targetId: Long,
            val targetLocked: Boolean,
        ) : Dialog
        data class DeleteManga(
            val manga: List<Manga>,
            val containsLocalManga: Boolean,
            val containsMergedManga: Boolean,
        ) : Dialog
    }

    @Immutable
    data class MergeEntry(
        val id: Long,
        val manga: Manga,
        val isFromExistingMerge: Boolean,
    ) {
        val title: String
            get() = manga.presentationTitle()

        val subtitle: String
            get() = buildString {
                val sourceName = Injekt.get<SourceManager>().getOrStub(manga.source).getNameForMangaInfo()
                val creator = manga.author?.takeIf { it.isNotBlank() }
                    ?: manga.artist?.takeIf { it.isNotBlank() }
                append(sourceName)
                if (creator != null && !creator.equals(sourceName, ignoreCase = true)) {
                    append(" • ")
                    append(creator)
                }
            }
    }

    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
    ) {
        val hasActiveFilters: Boolean =
            globalFilterDownloaded ||
                filterDownloaded != TriState.DISABLED ||
                filterUnread != TriState.DISABLED ||
                filterStarted != TriState.DISABLED ||
                filterBookmarked != TriState.DISABLED ||
                filterCompleted != TriState.DISABLED ||
                filterIntervalCustom != TriState.DISABLED
    }

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val showSystemCategory: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
        val tracksMap: Map</* Manga */ Long, List<Track>> = emptyMap(),
        val loggedInTrackerIds: Set<Long> = emptySet(),
    ) {
        val favoritesById by lazy { favorites.associateBy { it.id } }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set</* Manga */ Long> = setOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        val libraryData: LibraryData = LibraryData(),
        val groupType: LibraryGroupType = LibraryGroupType.Category,
        private val activePageIndex: Int = 0,
        private val groupedFavorites: List<LibraryPage> = emptyList(),
    ) {
        val displayedPages: List<LibraryPage> = groupedFavorites

        val coercedActivePageIndex = activePageIndex.coerceIn(
            minimumValue = 0,
            maximumValue = displayedPages.lastIndex.coerceAtLeast(0),
        )

        val activePage: LibraryPage? = displayedPages.getOrNull(coercedActivePageIndex)

        val activeSortCategory: Category? = activePage?.category
            ?.takeIf { groupType == LibraryGroupType.Category }

        val isLibraryEmpty = libraryData.favorites.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedLibraryManga by lazy { selection.mapNotNull { libraryData.favoritesById[it]?.libraryManga } }

        val selectedManga by lazy { selectedLibraryManga.map(LibraryManga::manga) }

        val selectedContainsLocal by lazy { selectedLibraryManga.any { LocalSource.ID in it.sourceIds } }

        fun getItemsForPageId(pageId: String?): List<LibraryItem> {
            if (pageId == null) return emptyList()
            val page = displayedPages.find { it.id == pageId } ?: return emptyList()
            return getItemsForPage(page)
        }

        fun getItemsForPage(page: LibraryPage): List<LibraryItem> {
            return page.itemIds.mapNotNull { libraryData.favoritesById[it] }
        }

        fun getItemCountForPage(page: LibraryPage): Int? {
            return if (showMangaCount || !searchQuery.isNullOrEmpty()) page.itemIds.size else null
        }

        fun getItemCountForPrimaryTab(tab: LibraryPageTab): Int? {
            if (!showMangaCount && searchQuery.isNullOrEmpty()) return null
            return displayedPages
                .filter { it.primaryTab.id == tab.id }
                .flatMap(LibraryPage::itemIds)
                .distinct()
                .size
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val currentPage = displayedPages.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val title = if (showCategoryTabs) defaultTitle else currentPage.displayTitle(defaultCategoryTitle)
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getItemCountForPage(currentPage)
                // Whole library count
                else -> libraryData.favorites.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }
}

internal fun observeGroupedLibraryPages(
    libraryData: Flow<LibraryScreenModel.LibraryData>,
    groupType: Flow<LibraryGroupType>,
    sortingMode: Flow<LibrarySort>,
    randomSortSeed: Flow<Int>,
    applyGrouping: (LibraryScreenModel.LibraryData, LibraryGroupType) -> List<LibraryPage>,
    applySort: (
        pages: List<LibraryPage>,
        data: LibraryScreenModel.LibraryData,
        groupType: LibraryGroupType,
        sortingMode: LibrarySort,
        randomSortSeed: Int,
    ) -> List<LibraryPage>,
): Flow<Pair<LibraryGroupType, List<LibraryPage>>> {
    return combine(
        libraryData,
        groupType,
        sortingMode,
        randomSortSeed,
    ) { data, groupType, sortingMode, randomSortSeed ->
        val pages = applyGrouping(data, groupType)
        groupType to applySort(pages, data, groupType, sortingMode, randomSortSeed)
    }
}

internal fun buildMergeDialog(selection: List<LibraryManga>): LibraryScreenModel.Dialog.MergeManga? {
    if (selection.size < 2) return null

    val mergedSelections = selection.filter { it.isMerged }
    if (mergedSelections.size > 1) return null

    val existingMerge = mergedSelections.firstOrNull()
    val newEntries = selection
        .filterNot(LibraryManga::isMerged)
        .flatMap { libraryManga ->
            libraryManga.memberMangas.map { memberManga ->
                LibraryScreenModel.MergeEntry(
                    id = memberManga.id,
                    manga = memberManga,
                    isFromExistingMerge = false,
                )
            }
        }
    val existingEntries = selection
        .filter(LibraryManga::isMerged)
        .flatMap { libraryManga ->
            libraryManga.memberMangas.map { memberManga ->
                LibraryScreenModel.MergeEntry(
                    id = memberManga.id,
                    manga = memberManga,
                    isFromExistingMerge = true,
                )
            }
        }
    val entries = (newEntries + existingEntries)
        .distinctBy(LibraryScreenModel.MergeEntry::id)
        .toImmutableList()

    if (entries.size < 2) return null

    return LibraryScreenModel.Dialog.MergeManga(
        entries = entries,
        targetId = existingMerge?.manga?.id ?: entries.first().id,
        targetLocked = false,
    )
}

internal fun orderedMergeIds(entries: List<LibraryScreenModel.MergeEntry>): List<Long> {
    return entries.map(LibraryScreenModel.MergeEntry::id).distinct()
}
