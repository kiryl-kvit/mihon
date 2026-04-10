package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.runtime.Immutable
import androidx.paging.PagingSource
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.model.FilterStateNode
import eu.kanade.domain.source.model.SourceFeedAnchor
import eu.kanade.domain.source.model.SourceFeedTimeline
import eu.kanade.domain.source.model.applySnapshot
import eu.kanade.domain.source.service.BrowseFeedService
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ChronologicalFeedScreenModel(
    private val feedId: String,
    private val sourceId: Long,
    private val listingQuery: String?,
    private val initialFilterSnapshot: List<FilterStateNode>,
    private val browseFeedService: BrowseFeedService = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
) : StateScreenModel<ChronologicalFeedScreenModel.State>(initialState(browseFeedService, feedId)) {

    private val source = sourceManager.getOrStub(sourceId) as CatalogueSource

    init {
        screenModelScope.launchIO {
            browseFeedService.timeline(feedId).collectLatest { timeline ->
                mutableState.update {
                    it.copy(
                        mangaIds = timeline.mangaIds,
                        nextPageKey = timeline.nextPageKey,
                        hasLoaded = it.hasLoaded || timeline.mangaIds.isNotEmpty(),
                    )
                }
            }
        }

        screenModelScope.launchIO {
            browseFeedService.anchor(feedId).collectLatest { anchor ->
                mutableState.update { it.copy(savedAnchor = anchor) }
            }
        }

        if (state.value.mangaIds.isEmpty()) {
            refresh()
        }
    }

    fun refresh(manual: Boolean = false) {
        if (state.value.isRefreshing) return
        screenModelScope.launchIO {
            refreshInternal(manual = manual)
        }
    }

    fun loadMore() {
        val currentState = state.value
        if (currentState.isRefreshing || currentState.isAppending || currentState.nextPageKey == null) return

        screenModelScope.launchIO {
            appendInternal()
        }
    }

    fun saveAnchor(mangaId: Long?, scrollOffset: Int) {
        browseFeedService.saveAnchor(
            feedId = feedId,
            anchor = SourceFeedAnchor(
                mangaId = mangaId,
                scrollOffset = scrollOffset,
            ),
        )
    }

    fun consumeNewItemsIndicator() {
        mutableState.update {
            if (it.newItemsAvailableCount == 0) {
                it
            } else {
                it.copy(newItemsAvailableCount = 0)
            }
        }
    }

    suspend fun subscribeManga(mangaId: Long): Flow<Manga> {
        return getManga.subscribe(mangaId)
    }

    private suspend fun refreshInternal(manual: Boolean) {
        val currentState = state.value
        val existingIds = currentState.mangaIds
        val existingIdSet = existingIds.toHashSet()
        val prependedIds = mutableListOf<Long>()
        var nextPageKey: Long? = currentState.nextPageKey
        var currentPageKey: Long? = null
        var pageCount = 0
        var error: Throwable? = null

        mutableState.update {
            it.copy(
                isRefreshing = true,
                isManualRefresh = manual,
                newItemsAvailableCount = if (manual) 0 else it.newItemsAvailableCount,
                error = null,
            )
        }

        while (pageCount < MAX_REFRESH_PAGES) {
            val page = try {
                loadPage(currentPageKey)
            } catch (e: Throwable) {
                error = e
                break
            }

            pageCount++
            val pageIds = page.data.map(Manga::id)

            if (existingIds.isEmpty()) {
                prependedIds += pageIds
                nextPageKey = page.nextKey
                break
            }

            val overlapIndex = pageIds.indexOfFirst { it in existingIdSet }
            if (overlapIndex >= 0) {
                prependedIds += pageIds.take(overlapIndex)
                break
            }

            prependedIds += pageIds.filterNot(existingIdSet::contains)

            currentPageKey = page.nextKey
            if (currentPageKey == null) {
                break
            }
        }

        val mergedIds = if (existingIds.isEmpty()) {
            prependedIds
        } else {
            (prependedIds + existingIds).distinct()
        }

        persistTimeline(
            mangaIds = mergedIds,
            nextPageKey = nextPageKey,
        )

        mutableState.update {
            it.copy(
                mangaIds = mergedIds,
                nextPageKey = nextPageKey,
                isRefreshing = false,
                isManualRefresh = false,
                newItemsAvailableCount = if (manual && error == null) prependedIds.size else 0,
                hasLoaded = true,
                error = error,
            )
        }
    }

    private suspend fun appendInternal() {
        val currentState = state.value
        var currentPageKey = currentState.nextPageKey ?: return
        val currentIds = currentState.mangaIds.toMutableList()
        val currentIdSet = currentIds.toHashSet()
        var nextPageKey = currentState.nextPageKey
        var error: Throwable? = null
        var pagesScanned = 0

        mutableState.update {
            it.copy(
                isAppending = true,
                error = null,
            )
        }

        while (pagesScanned < MAX_APPEND_PAGE_SCANS) {
            val page = try {
                loadPage(currentPageKey)
            } catch (e: Throwable) {
                error = e
                break
            }

            pagesScanned++

            val newIds = page.data.map(Manga::id).filter(currentIdSet::add)
            nextPageKey = page.nextKey

            if (newIds.isNotEmpty()) {
                currentIds += newIds
                break
            }

            currentPageKey = page.nextKey ?: break
        }

        persistTimeline(
            mangaIds = currentIds,
            nextPageKey = nextPageKey,
        )

        mutableState.update {
            it.copy(
                mangaIds = currentIds,
                nextPageKey = nextPageKey,
                isAppending = false,
                hasLoaded = true,
                error = error,
            )
        }
    }

    private suspend fun loadPage(pageKey: Long?): PagingSource.LoadResult.Page<Long, Manga> {
        val pagingSource = getRemoteManga(
            sourceId = sourceId,
            query = listingQuery.orEmpty(),
            filterList = filters(),
        )

        val params: PagingSource.LoadParams<Long> = if (pageKey == null) {
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = PAGE_SIZE,
                placeholdersEnabled = false,
            )
        } else {
            PagingSource.LoadParams.Append(
                key = pageKey,
                loadSize = PAGE_SIZE,
                placeholdersEnabled = false,
            )
        }

        return when (val result = pagingSource.load(params)) {
            is PagingSource.LoadResult.Page -> result
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> error("Invalid paging state for feed $feedId")
        }
    }

    private fun filters(): FilterList {
        return source.getFilterList().applySnapshot(initialFilterSnapshot)
    }

    private fun persistTimeline(mangaIds: List<Long>, nextPageKey: Long?) {
        browseFeedService.saveTimeline(
            feedId = feedId,
            timeline = SourceFeedTimeline(
                mangaIds = mangaIds,
                nextPageKey = nextPageKey,
            ),
        )
    }

    @Immutable
    data class State(
        val mangaIds: List<Long> = emptyList(),
        val nextPageKey: Long? = null,
        val savedAnchor: SourceFeedAnchor = SourceFeedAnchor(),
        val isRefreshing: Boolean = false,
        val isManualRefresh: Boolean = false,
        val isAppending: Boolean = false,
        val newItemsAvailableCount: Int = 0,
        val hasLoaded: Boolean = false,
        val error: Throwable? = null,
    )

    companion object {
        private const val PAGE_SIZE = 25
        private const val MAX_REFRESH_PAGES = 10
        private const val MAX_APPEND_PAGE_SCANS = 10

        private fun initialState(
            browseFeedService: BrowseFeedService,
            feedId: String,
        ): State {
            val timeline = browseFeedService.timelineSnapshot(feedId)
            return State(
                mangaIds = timeline.mangaIds,
                nextPageKey = timeline.nextPageKey,
                savedAnchor = browseFeedService.anchorSnapshot(feedId),
                hasLoaded = timeline.mangaIds.isNotEmpty(),
            )
        }
    }
}
