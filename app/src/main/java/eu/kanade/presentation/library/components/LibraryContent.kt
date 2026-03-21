package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibraryPage
import eu.kanade.tachiyomi.ui.library.LibraryPageTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.presentation.core.components.material.PullRefresh
import kotlin.time.Duration.Companion.seconds

@Composable
fun LibraryContent(
    pages: List<LibraryPage>,
    searchQuery: String?,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    currentPage: Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (LibraryPage, LibraryManga) -> Unit,
    onToggleRangeSelection: (LibraryPage, LibraryManga) -> Unit,
    onRefresh: () -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForPage: (LibraryPage) -> Int?,
    getItemCountForPrimaryTab: (LibraryPageTab) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForPage: (LibraryPage) -> List<LibraryItem>,
) {
    Column(
        modifier = Modifier.padding(
            top = contentPadding.calculateTopPadding(),
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
        ),
    ) {
        val pagerState = rememberPagerState(currentPage) { pages.size }

        val scope = rememberCoroutineScope()
        var isRefreshing by remember(pagerState.currentPage) { mutableStateOf(false) }

        val primaryTabs = remember(pages) {
            pages.map(LibraryPage::primaryTab).distinctBy(LibraryPageTab::id)
        }
        val activePage = pages.getOrNull(pagerState.currentPage)
        val secondaryTabs = remember(pages, activePage?.primaryTab?.id) {
            activePage?.primaryTab?.id
                ?.let { primaryTabId ->
                    pages.filter { it.primaryTab.id == primaryTabId }
                        .mapNotNull(LibraryPage::secondaryTab)
                        .distinctBy(LibraryPageTab::id)
                }
                .orEmpty()
        }

        if (showPageTabs && pages.isNotEmpty()) {
            LaunchedEffect(pages) {
                if (pages.size <= pagerState.currentPage) {
                    pagerState.scrollToPage(pages.size - 1)
                }
            }

            if (primaryTabs.size > 1 || secondaryTabs.isNotEmpty()) {
                LibraryTabs(
                    tabs = primaryTabs,
                    selectedTabId = activePage?.primaryTab?.id,
                    getItemCountForTab = getItemCountForPrimaryTab,
                    onTabItemClick = { selectedTab ->
                        val targetPageIndex = pages.indexOfFirst { it.primaryTab.id == selectedTab.id }
                        if (targetPageIndex < 0) return@LibraryTabs
                        scope.launch {
                            pagerState.animateScrollToPage(targetPageIndex)
                        }
                    },
                )
            }

            if (secondaryTabs.isNotEmpty()) {
                LibraryTabs(
                    tabs = secondaryTabs,
                    selectedTabId = activePage?.secondaryTab?.id,
                    getItemCountForTab = { tab ->
                        pages.firstOrNull {
                            it.primaryTab.id == activePage?.primaryTab?.id && it.secondaryTab?.id == tab.id
                        }?.let(getItemCountForPage)
                    },
                    onTabItemClick = { selectedTab ->
                        val targetPageIndex = pages.indexOfFirst {
                            it.primaryTab.id == activePage?.primaryTab?.id && it.secondaryTab?.id == selectedTab.id
                        }
                        if (targetPageIndex < 0) return@LibraryTabs
                        scope.launch {
                            pagerState.animateScrollToPage(targetPageIndex)
                        }
                    },
                )
            }
        }

        PullRefresh(
            refreshing = isRefreshing,
            enabled = selection.isEmpty(),
            onRefresh = {
                val started = onRefresh()
                if (!started) return@PullRefresh
                scope.launch {
                    // Fake refresh status but hide it after a second as it's a long running task
                    isRefreshing = true
                    delay(1.seconds)
                    isRefreshing = false
                }
            },
        ) {
            LibraryPager(
                state = pagerState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                hasActiveFilters = hasActiveFilters,
                selection = selection,
                searchQuery = searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                getPageForIndex = { page -> pages[page] },
                getDisplayMode = getDisplayMode,
                getColumnsForOrientation = getColumnsForOrientation,
                getItemsForPage = getItemsForPage,
                onClickManga = { page, manga ->
                    if (selection.isNotEmpty()) {
                        onToggleSelection(page, manga)
                    } else {
                        onClickManga(manga.manga.id)
                    }
                },
                onLongClickManga = onToggleRangeSelection,
                onClickContinueReading = onContinueReadingClicked,
            )
        }

        LaunchedEffect(pagerState.currentPage) {
            onChangeCurrentPage(pagerState.currentPage)
        }
    }
}
