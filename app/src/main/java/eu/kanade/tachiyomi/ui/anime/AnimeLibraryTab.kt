package eu.kanade.tachiyomi.ui.anime

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.library.components.LibraryTabs
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.anime.browse.globalsearch.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

data object AnimeLibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestOpenSettingsSheet()
    }

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AnimeLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            topBar = { scrollBehavior ->
                AnimeLibraryToolbar(
                    title = state.getToolbarTitle(
                        defaultTitle = stringResource(MR.strings.label_library),
                        defaultCategoryTitle = stringResource(MR.strings.label_default),
                    ),
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    onClickFilter = screenModel::showSettingsDialog,
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            val errorMessage = state.errorMessage
            when {
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                errorMessage != null -> EmptyScreen(
                    message = errorMessage,
                    modifier = Modifier.padding(contentPadding),
                )
                state.searchQuery.isNullOrEmpty() && state.isLibraryEmpty -> EmptyScreen(
                    stringRes = MR.strings.information_empty_library,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> AnimeLibraryContent(
                    state = state,
                    contentPadding = contentPadding,
                    onChangeCurrentPage = screenModel::updateActivePageIndex,
                    getDisplayMode = screenModel::getDisplayMode,
                    getColumnsForOrientation = screenModel::getColumnsForOrientation,
                    onOpenAnime = { navigator.push(AnimeScreen(it)) },
                    onOpenEpisode = { animeId, episodeId ->
                        context.startActivity(
                            VideoPlayerActivity.newIntent(
                                context = context,
                                animeId = animeId,
                                episodeId = episodeId,
                            ),
                        )
                    },
                    onGlobalSearchClicked = {
                        navigator.push(AnimeGlobalSearchScreen(state.searchQuery.orEmpty()))
                    },
                )
            }
        }

        if (state.dialog == AnimeLibraryScreenModel.Dialog.SettingsSheet) {
            AnimeLibrarySettingsDialog(
                screenModel = screenModel,
                onDismissRequest = screenModel::closeDialog,
            )
        }

        BackHandler(enabled = state.searchQuery != null || state.dialog != null) {
            when {
                state.dialog != null -> screenModel.closeDialog()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            requestSettingsSheetEvent.receiveAsFlow().collect {
                screenModel.showSettingsDialog()
            }
        }
    }

    private val requestSettingsSheetEvent = Channel<Unit>()

    private suspend fun requestOpenSettingsSheet() {
        requestSettingsSheetEvent.send(Unit)
    }
}

@Composable
private fun AnimeLibraryToolbar(
    title: eu.kanade.presentation.library.components.LibraryToolbarTitle,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onClickFilter: () -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior?,
) {
    SearchToolbar(
        titleContent = {
            androidx.compose.foundation.layout.Row {
                Text(
                    text = title.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, false),
                )
                title.numberOfManga?.let {
                    Badge(
                        text = "$it",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        actions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_filter),
                        icon = Icons.Outlined.FilterList,
                        onClick = onClickFilter,
                    ),
                ),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun AnimeLibraryContent(
    state: AnimeLibraryScreenModel.State,
    contentPadding: PaddingValues,
    onChangeCurrentPage: (Int) -> Unit,
    getDisplayMode: () -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long) -> Unit,
    onGlobalSearchClicked: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val pagerState = rememberPagerState(state.coercedActivePageIndex) { state.pages.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.padding(
            top = contentPadding.calculateTopPadding(),
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
        ),
    ) {
        val activePage = state.pages.getOrNull(pagerState.currentPage)
        val primaryTabs = remember(state.pages) {
            state.pages.map { it.primaryTab }.distinctBy { it.id }
        }
        val secondaryTabs = remember(state.pages, activePage?.primaryTab?.id) {
            activePage?.primaryTab?.id
                ?.let { primaryTabId ->
                    state.pages.filter { it.primaryTab.id == primaryTabId }
                        .mapNotNull { it.secondaryTab }
                        .distinctBy { it.id }
                }
                .orEmpty()
        }

        if (state.showCategoryTabs && state.pages.isNotEmpty()) {
            if (primaryTabs.size > 1 || secondaryTabs.isNotEmpty()) {
                LibraryTabs(
                    tabs = primaryTabs,
                    selectedTabId = activePage?.primaryTab?.id,
                    getItemCountForTab = state::getItemCountForPrimaryTab,
                    onTabItemClick = { selectedTab ->
                        val targetPageIndex = state.pages.indexOfFirst { it.primaryTab.id == selectedTab.id }
                        if (targetPageIndex >= 0) {
                            scope.launch { pagerState.animateScrollToPage(targetPageIndex) }
                        }
                    },
                )
            }

            if (secondaryTabs.isNotEmpty()) {
                LibraryTabs(
                    tabs = secondaryTabs,
                    selectedTabId = activePage?.secondaryTab?.id,
                    getItemCountForTab = { tab ->
                        state.pages.firstOrNull {
                            it.primaryTab.id == activePage?.primaryTab?.id && it.secondaryTab?.id == tab.id
                        }?.let(state::getItemCountForPage)
                    },
                    onTabItemClick = { selectedTab ->
                        val targetPageIndex = state.pages.indexOfFirst {
                            it.primaryTab.id == activePage?.primaryTab?.id && it.secondaryTab?.id == selectedTab.id
                        }
                        if (targetPageIndex >= 0) {
                            scope.launch { pagerState.animateScrollToPage(targetPageIndex) }
                        }
                    },
                )
            }
        }

        HorizontalPager(
            modifier = Modifier.fillMaxSize(),
            state = pagerState,
        ) { page ->
            val libraryPage = state.pages.getOrNull(page)
            val items = libraryPage?.let(state::getItemsForPage).orEmpty()
            if (libraryPage == null || items.isEmpty()) {
                AnimeLibraryPagerEmptyScreen(
                    searchQuery = state.searchQuery,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
                return@HorizontalPager
            }

            val displayMode by getDisplayMode()
                val columns by if (displayMode != LibraryDisplayMode.List) {
                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    remember(isLandscape) { getColumnsForOrientation(isLandscape) }
                } else {
                    remember { androidx.compose.runtime.mutableIntStateOf(0) }
                }

            when (displayMode) {
                LibraryDisplayMode.List -> AnimeLibraryList(
                    items = items,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    searchQuery = state.searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    onOpenAnime = onOpenAnime,
                    onOpenEpisode = onOpenEpisode,
                )
                LibraryDisplayMode.ComfortableGrid -> AnimeLibraryComfortableGrid(
                    items = items,
                    columns = columns,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    searchQuery = state.searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    onOpenAnime = onOpenAnime,
                    onOpenEpisode = onOpenEpisode,
                )
                LibraryDisplayMode.CompactGrid,
                LibraryDisplayMode.CoverOnlyGrid,
                -> AnimeLibraryCompactGrid(
                    items = items,
                    showTitle = displayMode == LibraryDisplayMode.CompactGrid,
                    columns = columns,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    searchQuery = state.searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                    onOpenAnime = onOpenAnime,
                    onOpenEpisode = onOpenEpisode,
                )
            }
        }

        LaunchedEffect(pagerState.currentPage) {
            onChangeCurrentPage(pagerState.currentPage)
        }
    }
}

@Composable
private fun AnimeLibraryList(
    items: List<AnimeLibraryScreenModel.AnimeLibraryItem>,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long) -> Unit,
) {
    ScrollbarLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        item {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        items(items, key = { it.animeId }) { item ->
            MangaListItem(
                title = item.title,
                coverData = item.coverData,
                badge = {
                    if (item.unwatchedCount > 0) {
                        Badge(text = item.unwatchedCount.toString())
                    }
                },
                onLongClick = { onOpenAnime(item.animeId) },
                onClick = { onOpenAnime(item.animeId) },
                onClickContinueReading = item.primaryEpisodeId?.let { episodeId ->
                    { onOpenEpisode(item.animeId, episodeId) }
                },
            )
        }
    }
}

@Composable
private fun AnimeLibraryComfortableGrid(
    items: List<AnimeLibraryScreenModel.AnimeLibraryItem>,
    columns: Int,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long) -> Unit,
) {
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GlobalSearchItem(searchQuery = searchQuery, onClick = onGlobalSearchClicked)
            }
        }

        items(items, key = { it.animeId }) { item ->
            MangaComfortableGridItem(
                title = item.title,
                coverData = item.coverData,
                coverBadgeStart = {
                    if (item.unwatchedCount > 0) {
                        Badge(text = item.unwatchedCount.toString())
                    }
                },
                coverBadgeEnd = {
                },
                onLongClick = { onOpenAnime(item.animeId) },
                onClick = { onOpenAnime(item.animeId) },
                onClickContinueReading = item.primaryEpisodeId?.let { episodeId ->
                    { onOpenEpisode(item.animeId, episodeId) }
                },
            )
        }
    }
}

@Composable
private fun AnimeLibraryCompactGrid(
    items: List<AnimeLibraryScreenModel.AnimeLibraryItem>,
    showTitle: Boolean,
    columns: Int,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long) -> Unit,
) {
    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GlobalSearchItem(searchQuery = searchQuery, onClick = onGlobalSearchClicked)
            }
        }

        items(items, key = { it.animeId }) { item ->
            MangaCompactGridItem(
                title = item.title.takeIf { showTitle },
                coverData = item.coverData,
                coverBadgeStart = {
                    if (item.unwatchedCount > 0) {
                        Badge(text = item.unwatchedCount.toString())
                    }
                },
                coverBadgeEnd = {
                },
                onLongClick = { onOpenAnime(item.animeId) },
                onClick = { onOpenAnime(item.animeId) },
                onClickContinueReading = item.primaryEpisodeId?.let { episodeId ->
                    { onOpenEpisode(item.animeId, episodeId) }
                },
            )
        }
    }
}

@Composable
private fun AnimeLibraryPagerEmptyScreen(
    searchQuery: String?,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    ScrollbarLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(8.dp),
    ) {
        item {
            if (!searchQuery.isNullOrEmpty()) {
                GlobalSearchItem(
                    searchQuery = searchQuery,
                    onClick = onGlobalSearchClicked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            EmptyScreen(
                stringRes = if (!searchQuery.isNullOrEmpty()) MR.strings.no_results_found else MR.strings.information_no_manga_group,
            )
        }
    }
}

@Composable
private fun AnimeLibrarySettingsDialog(
    screenModel: AnimeLibraryScreenModel,
    onDismissRequest: () -> Unit,
) {
    val displayMode by screenModel.getDisplayMode()
    val state by screenModel.state.collectAsState()
    val groupType = state.groupType
    val showCategoryTabs = state.showCategoryTabs
    val showItemCount = state.showItemCount

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        ScrollbarLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SettingsChipRow(MR.strings.action_display_mode) {
                    listOf(
                        MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
                        MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
                        MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
                        MR.strings.action_display_list to LibraryDisplayMode.List,
                    ).forEach { (titleRes, mode) ->
                        FilterChip(
                            selected = displayMode == mode,
                            onClick = { screenModel.getDisplayMode().value = mode },
                            label = { Text(stringResource(titleRes)) },
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(MR.strings.tabs_header),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                CheckboxItem(
                    label = stringResource(
                        when (groupType) {
                            LibraryGroupType.Category -> MR.strings.action_display_show_tabs
                            LibraryGroupType.Extension -> MR.strings.action_display_show_extension_tabs
                            LibraryGroupType.ExtensionCategory,
                            LibraryGroupType.CategoryExtension,
                            -> MR.strings.action_display_show_group_tabs
                        },
                    ),
                    checked = showCategoryTabs,
                    onClick = { screenModel.setShowCategoryTabs(!showCategoryTabs) },
                )
            }

            item {
                CheckboxItem(
                    label = stringResource(MR.strings.action_display_show_number_of_items),
                    checked = showItemCount,
                    onClick = { screenModel.setShowItemCount(!showItemCount) },
                )
            }

            item {
                Text(
                    text = stringResource(MR.strings.action_group_category),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            items(
                listOf(
                    MR.strings.action_group_category to LibraryGroupType.Category,
                    MR.strings.action_group_extension to LibraryGroupType.Extension,
                    MR.strings.action_group_extension_category to LibraryGroupType.ExtensionCategory,
                    MR.strings.action_group_category_extension to LibraryGroupType.CategoryExtension,
                ),
            ) { (titleRes, mode) ->
                RadioItem(
                    label = stringResource(titleRes),
                    selected = groupType == mode,
                    onClick = { screenModel.setGroup(mode) },
                )
            }
        }
    }
}
