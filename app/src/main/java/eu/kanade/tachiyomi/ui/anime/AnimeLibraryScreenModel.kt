package eu.kanade.tachiyomi.ui.anime

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.ui.library.LibraryPage
import eu.kanade.tachiyomi.ui.library.LibraryPageTab
import eu.kanade.tachiyomi.ui.library.displayTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.feature.profiles.core.ProfileAwareStore
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.effectiveLibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeLibraryScreenModel(
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val animeHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val categoryRepository: tachiyomi.domain.category.repository.CategoryRepository = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
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
                    )
                }
            }
            .launchIn(screenModelScope)

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                animeRepository.getFavoritesAsFlow(),
                getCategories.subscribe(),
                libraryPreferences.groupType.changes(),
                libraryPreferences.sortingMode.changes(),
                libraryPreferences.randomSortSeed.changes(),
                libraryPreferences.categoryTabs.changes(),
                libraryPreferences.categoryNumberOfItems.changes(),
            ) { values ->
                LibrarySnapshot(
                    searchQuery = values[0] as String?,
                    favorites = values[1] as List<AnimeTitle>,
                    categories = values[2] as List<Category>,
                    groupType = values[3] as LibraryGroupType,
                    sortMode = values[4] as LibrarySort,
                    randomSortSeed = values[5] as Int,
                    showCategoryTabs = values[6] as Boolean,
                    showItemCount = values[7] as Boolean,
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
        return try {
            val favorites = snapshot.filteredFavorites()
            val animeIds = favorites.map(AnimeTitle::id)
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
                favorites = favorites,
                episodes = episodes,
                playbackStates = playbackStates,
                categoryIdsByAnimeId = categoryIdsByAnimeId,
            )
            val pages = buildPages(
                items = animeItems,
                categories = snapshot.categories,
                groupType = snapshot.groupType,
                globalSort = snapshot.sortMode,
                randomSortSeed = snapshot.randomSortSeed,
            )

            State(
                isLoading = false,
                searchQuery = snapshot.searchQuery,
                showCategoryTabs = snapshot.showCategoryTabs,
                showItemCount = snapshot.showItemCount,
                groupType = snapshot.groupType,
                libraryItems = animeItems,
                pages = pages,
                activePageIndex = mutableState.value.activePageIndex,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            State(
                isLoading = false,
                errorMessage = withUIContext { application.stringResource(tachiyomi.i18n.MR.strings.unknown_error) },
                searchQuery = snapshot.searchQuery,
                showCategoryTabs = snapshot.showCategoryTabs,
                showItemCount = snapshot.showItemCount,
                groupType = snapshot.groupType,
                activePageIndex = mutableState.value.activePageIndex,
            )
        }
    }

    private fun buildAnimeItems(
        favorites: List<AnimeTitle>,
        episodes: List<AnimeEpisode>,
        playbackStates: List<AnimePlaybackState>,
        categoryIdsByAnimeId: Map<Long, List<Long>>,
    ): List<AnimeLibraryItem> {
        val episodesByAnimeId = episodes.groupBy(AnimeEpisode::animeId)
        val playbackStateByEpisodeId = playbackStates.associateBy(AnimePlaybackState::episodeId)

        return favorites.map { anime ->
            val animeEpisodes = episodesByAnimeId[anime.id].orEmpty()
            val unwatchedCount = animeEpisodes.count { !it.completed }
            val inProgressPlayback = animeEpisodes
                .asSequence()
                .mapNotNull { episode -> playbackStateByEpisodeId[episode.id] }
                .filter { !it.completed && it.positionMs > 0L && it.durationMs > 0L }
                .maxByOrNull(AnimePlaybackState::lastWatchedAt)
            val primaryEpisode = inProgressPlayback
                ?.let { playbackState -> animeEpisodes.firstOrNull { it.id == playbackState.episodeId } }
                ?: animeEpisodes.firstOrNull { !it.completed }
                ?: animeEpisodes.firstOrNull()
            val source = animeSourceManager.get(anime.source)

            AnimeLibraryItem(
                animeId = anime.id,
                title = anime.displayTitle,
                coverData = anime.toMangaCover(),
                sourceId = anime.source,
                sourceName = source?.name ?: application.stringResource(tachiyomi.i18n.MR.strings.source_not_installed, anime.source.toString()),
                sourceLanguage = source?.lang.orEmpty(),
                primaryEpisodeId = primaryEpisode?.id,
                unwatchedCount = unwatchedCount.toLong(),
                hasInProgress = inProgressPlayback != null,
                progressFraction = inProgressPlayback?.progressFraction(),
                favoriteModifiedAt = anime.favoriteModifiedAt ?: anime.dateAdded,
                dateAdded = anime.dateAdded,
                lastUpdate = anime.lastUpdate,
                categoryIds = categoryIdsByAnimeId[anime.id].orEmpty(),
            )
        }
    }

    private fun buildPages(
        items: List<AnimeLibraryItem>,
        categories: List<Category>,
        groupType: LibraryGroupType,
        globalSort: LibrarySort,
        randomSortSeed: Int,
    ): List<LibraryPage> {
        val categoryTabs = categories.associate { category ->
            category.id to LibraryPageTab(
                id = "category:${category.id}",
                title = category.name,
                category = category,
            )
        }
        val sourceNames = items.map(AnimeLibraryItem::sourceId)
            .distinct()
            .associateWith { sourceId ->
                items.firstOrNull { it.sourceId == sourceId }?.sourceName
                    ?: application.stringResource(tachiyomi.i18n.MR.strings.source_not_installed, sourceId.toString())
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
                categories.map { category ->
                    LibraryPage(
                        id = "category:${category.id}",
                        primaryTab = categoryTabs.getValue(category.id),
                        category = category,
                        itemIds = items.filter { category.id in it.effectiveCategoryIds }.map(AnimeLibraryItem::animeId),
                    )
                }
            }
            LibraryGroupType.Extension -> {
                sourceTabs.map { (sourceId, tab) ->
                    LibraryPage(
                        id = tab.id,
                        primaryTab = tab,
                        sourceId = sourceId,
                        itemIds = items.filter { it.sourceId == sourceId }.map(AnimeLibraryItem::animeId),
                    )
                }
            }
            LibraryGroupType.ExtensionCategory -> {
                sourceTabs.flatMap { (sourceId, sourceTab) ->
                    categories.map { category ->
                        LibraryPage(
                            id = "${sourceTab.id}:${category.id}",
                            primaryTab = sourceTab,
                            secondaryTab = categoryTabs.getValue(category.id),
                            category = category,
                            sourceId = sourceId,
                            itemIds = items.filter { it.sourceId == sourceId && category.id in it.effectiveCategoryIds }
                                .map(AnimeLibraryItem::animeId),
                        )
                    }
                }
            }
            LibraryGroupType.CategoryExtension -> {
                categories.flatMap { category ->
                    sourceTabs.map { (sourceId, sourceTab) ->
                        LibraryPage(
                            id = "category:${category.id}:$sourceId",
                            primaryTab = categoryTabs.getValue(category.id),
                            secondaryTab = sourceTab,
                            category = category,
                            sourceId = sourceId,
                            itemIds = items.filter { category.id in it.effectiveCategoryIds && it.sourceId == sourceId }
                                .map(AnimeLibraryItem::animeId),
                        )
                    }
                }
            }
        }

        return pages
            .map { page ->
                page.copy(itemIds = sortItemsForPage(page, items, globalSort, randomSortSeed).map(AnimeLibraryItem::animeId))
            }
            .filter { page -> page.itemIds.isNotEmpty() }
    }

    private fun sortItemsForPage(
        page: LibraryPage,
        items: List<AnimeLibraryItem>,
        globalSort: LibrarySort,
        randomSortSeed: Int,
    ): List<AnimeLibraryItem> {
        val pageItems = items.filter { it.animeId in page.itemIds }
        val sort = page.category.effectiveLibrarySort(globalSort)

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

    fun updateActivePageIndex(index: Int) {
        mutableState.update { state -> state.copy(activePageIndex = index) }
        val newIndex = state.value.coercedActivePageIndex

        libraryPreferences.lastUsedCategory.set(newIndex)
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)
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

    fun setShowCategoryTabs(enabled: Boolean) {
        libraryPreferences.categoryTabs.set(enabled)
    }

    fun setShowItemCount(enabled: Boolean) {
        libraryPreferences.categoryNumberOfItems.set(enabled)
    }

    @Immutable
    data class AnimeLibraryItem(
        val animeId: Long,
        val title: String,
        val coverData: tachiyomi.domain.manga.model.MangaCover,
        val sourceId: Long,
        val sourceName: String,
        val sourceLanguage: String,
        val primaryEpisodeId: Long?,
        val unwatchedCount: Long,
        val hasInProgress: Boolean,
        val progressFraction: Float?,
        val favoriteModifiedAt: Long,
        val dateAdded: Long,
        val lastUpdate: Long,
        val categoryIds: List<Long>,
    ) {
        val effectiveCategoryIds: List<Long>
            get() = categoryIds.ifEmpty { listOf(Category.UNCATEGORIZED_ID) }
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val searchQuery: String? = null,
        val showCategoryTabs: Boolean = false,
        val showItemCount: Boolean = false,
        val dialog: Dialog? = null,
        val groupType: LibraryGroupType = LibraryGroupType.Category,
        val libraryItems: List<AnimeLibraryItem> = emptyList(),
        val pages: List<LibraryPage> = emptyList(),
        val activePageIndex: Int = 0,
    ) {
        val coercedActivePageIndex = activePageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))

        val activePage: LibraryPage?
            get() = pages.getOrNull(coercedActivePageIndex)

        val isLibraryEmpty: Boolean
            get() = libraryItems.isEmpty()

        val hasActiveFilters: Boolean
            get() = false

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

        fun getToolbarTitle(defaultTitle: String, defaultCategoryTitle: String): eu.kanade.presentation.library.components.LibraryToolbarTitle {
            val currentPage = activePage ?: return eu.kanade.presentation.library.components.LibraryToolbarTitle(defaultTitle)
            val title = if (showCategoryTabs) defaultTitle else currentPage.displayTitle(defaultCategoryTitle)
            val count = when {
                !showItemCount -> null
                !showCategoryTabs -> getItemCountForPage(currentPage)
                else -> libraryItems.size
            }
            return eu.kanade.presentation.library.components.LibraryToolbarTitle(title, count)
        }
    }

    private data class LibrarySnapshot(
        val searchQuery: String?,
        val favorites: List<AnimeTitle>,
        val categories: List<Category>,
        val groupType: LibraryGroupType,
        val sortMode: LibrarySort,
        val randomSortSeed: Int,
        val showCategoryTabs: Boolean,
        val showItemCount: Boolean,
    ) {
        fun filteredFavorites(): List<AnimeTitle> {
            val query = searchQuery?.trim()?.takeIf { it.isNotEmpty() } ?: return favorites
            return favorites.filter { anime ->
                anime.displayTitle.contains(query, ignoreCase = true) ||
                    anime.title.contains(query, ignoreCase = true) ||
                    anime.description?.contains(query, ignoreCase = true) == true ||
                    anime.genre?.any { genre -> genre.contains(query, ignoreCase = true) } == true
            }
        }
    }
}

private fun AnimePlaybackState.progressFraction(): Float {
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
