package eu.kanade.tachiyomi.ui.anime

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.anime.toMergeEditorEntry
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.library.components.LibraryPageEmptyScreen
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.SharedLibraryContent
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MergeEditorDialog
import eu.kanade.presentation.manga.components.MergeEditorEntry
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.ui.anime.browse.globalsearch.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.LibraryPage
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerActivity
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.effectiveLibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BaseSortItem
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.core.common.i18n.stringResource as contextStringResource

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
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { AnimeLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        fun showRefreshMessage(started: Boolean, messageRes: StringResource) {
            scope.launch {
                val msgRes = when {
                    !started -> MR.strings.update_already_running
                    else -> messageRes
                }
                snackbarHostState.showSnackbar(context.contextStringResource(msgRes))
            }
        }

        val onClickRefresh: (AnimeLibraryScreenModel.State) -> Boolean = { state ->
            val activePage = state.activePage
            val started = AnimeLibraryUpdateJob.startNow(
                context = context,
                category = activePage?.category,
                sourceId = activePage?.sourceId,
            )
            val messageRes = when {
                activePage?.sourceId != null && activePage.category != null -> MR.strings.updating_group
                activePage?.sourceId != null -> MR.strings.updating_extension
                activePage?.category != null -> MR.strings.updating_category
                else -> MR.strings.updating_library
            }
            showRefreshMessage(started, messageRes)
            started
        }

        val onClickGlobalUpdate: () -> Boolean = {
            val started = AnimeLibraryUpdateJob.startNow(context)
            showRefreshMessage(started, MR.strings.updating_library)
            started
        }

        Scaffold(
            topBar = { scrollBehavior ->
                val title = state.getToolbarTitle(
                    defaultTitle = stringResource(MR.strings.label_library),
                    defaultCategoryTitle = stringResource(MR.strings.label_default),
                )
                LibraryToolbar(
                    hasActiveFilters = state.hasActiveFilters,
                    selectedCount = state.selection.size,
                    title = title,
                    currentGroupType = state.groupType,
                    onClickUnselectAll = screenModel::clearSelection,
                    onClickSelectAll = screenModel::selectAll,
                    onClickInvertSelection = screenModel::invertSelection,
                    onClickFilter = screenModel::showSettingsDialog,
                    onClickRefresh = { onClickRefresh(state) },
                    onClickGlobalUpdate = { onClickGlobalUpdate() },
                    onClickOpenRandomManga = {
                        scope.launch {
                            val randomItem = screenModel.getRandomLibraryItemForCurrentPage()
                            if (randomItem != null) {
                                navigator.push(AnimeScreen(randomItem.animeId))
                            } else {
                                snackbarHostState.showSnackbar(
                                    context.contextStringResource(MR.strings.information_no_entries_found),
                                )
                            }
                        }
                    },
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                )
            },
            bottomBar = {
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onMergeClicked = screenModel::openMergeDialog.takeIf { screenModel.canMergeSelection() },
                    onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                    onMarkAsReadClicked = { screenModel.markWatchedSelection(true) },
                    onMarkAsUnreadClicked = { screenModel.markWatchedSelection(false) },
                    onDownloadClicked = null,
                    onDeleteClicked = screenModel::openRemoveAnimeDialog,
                    onMigrateClicked = null,
                    markAsReadLabel = MR.strings.action_mark_as_watched,
                    markAsUnreadLabel = MR.strings.action_mark_as_unwatched,
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
                state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                    val handler = LocalUriHandler.current
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_library,
                        modifier = Modifier.padding(contentPadding),
                        actions = kotlinx.collections.immutable.persistentListOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.getting_started_guide,
                                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                                onClick = { handler.openUri(GETTING_STARTED_URL) },
                            ),
                        ),
                    )
                }
                else -> AnimeLibraryContent(
                    state = state,
                    contentPadding = contentPadding,
                    onChangeCurrentPage = screenModel::updateActivePageIndex,
                    onRefresh = { onClickRefresh(state) },
                    onToggleSelection = screenModel::toggleSelection,
                    onToggleRangeSelection = { page, item ->
                        screenModel.toggleRangeSelection(page, item)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    getDisplayMode = screenModel::getDisplayMode,
                    getColumnsForOrientation = screenModel::getColumnsForOrientation,
                    onOpenAnime = { navigator.push(AnimeScreen(it)) },
                    onOpenEpisode = { visibleAnimeId, ownerAnimeId, episodeId ->
                        context.startActivity(
                            VideoPlayerActivity.newIntent(
                                context = context,
                                animeId = visibleAnimeId,
                                ownerAnimeId = ownerAnimeId,
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

        when (val dialog = state.dialog) {
            AnimeLibraryScreenModel.Dialog.SettingsSheet -> {
                AnimeLibrarySettingsDialog(
                    screenModel = screenModel,
                    onDismissRequest = screenModel::closeDialog,
                )
            }
            is AnimeLibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = screenModel::closeDialog,
                    onEditCategories = {
                        screenModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setAnimeCategories(dialog.animeIds, include, exclude)
                    },
                )
            }
            is AnimeLibraryScreenModel.Dialog.MergeAnime -> {
                MergeLibraryAnimeDialog(
                    dialog = dialog,
                    onDismissRequest = screenModel::closeDialog,
                    onMove = screenModel::reorderMergeSelection,
                    onSelectTarget = screenModel::setMergeTarget,
                    onConfirm = screenModel::confirmMergeSelection,
                )
            }
            is AnimeLibraryScreenModel.Dialog.RemoveAnime -> {
                RemoveAnimeDialog(
                    onDismissRequest = screenModel::closeDialog,
                    onConfirm = {
                        screenModel.clearSelection()
                        screenModel.removeAnime(dialog.animeIds)
                    },
                )
            }
            null -> Unit
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null || state.dialog != null) {
            when {
                state.dialog != null -> screenModel.closeDialog()
                state.selectionMode -> screenModel.clearSelection()
                state.searchQuery != null -> screenModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode && state.dialog == null)
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
private fun MergeLibraryAnimeDialog(
    dialog: AnimeLibraryScreenModel.Dialog.MergeAnime,
    onDismissRequest: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onSelectTarget: (Long) -> Unit,
    onConfirm: () -> Unit,
) {
    MergeEditorDialog(
        title = stringResource(MR.strings.action_merge),
        entries = dialog.entries.map { it.toMergeEditorEntry() }.toPersistentList(),
        targetId = dialog.targetId,
        targetLocked = dialog.targetLocked,
        onDismissRequest = onDismissRequest,
        onMove = onMove,
        onConfirm = onConfirm,
        onSelectTarget = onSelectTarget.takeUnless { dialog.targetLocked },
    )
}

private fun AnimeLibraryScreenModel.MergeEntry.toMergeEditorEntry(): MergeEditorEntry {
    return anime.toMergeEditorEntry(
        subtitle = subtitle,
        isMember = isFromExistingMerge,
    )
}

@Composable
private fun RemoveAnimeDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.action_remove))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_from_library))
        },
    )
}

@Composable
private fun AnimeLibraryContent(
    state: AnimeLibraryScreenModel.State,
    contentPadding: PaddingValues,
    onChangeCurrentPage: (Int) -> Unit,
    onRefresh: () -> Boolean,
    onToggleSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onToggleRangeSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    getDisplayMode: () -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long, Long) -> Unit,
    onGlobalSearchClicked: () -> Unit,
) {
    SharedLibraryContent(
        pages = state.pages,
        searchQuery = state.searchQuery,
        selection = state.selection,
        contentPadding = contentPadding,
        currentPage = state.coercedActivePageIndex,
        hasActiveFilters = state.hasActiveFilters,
        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
        onChangeCurrentPage = onChangeCurrentPage,
        onRefresh = onRefresh,
        onGlobalSearchClicked = onGlobalSearchClicked,
        getItemCountForPage = state::getItemCountForPage,
        getItemCountForPrimaryTab = state::getItemCountForPrimaryTab,
    ) { pagerState, _, _ ->
        AnimeLibraryPager(
            pagerState = pagerState,
            state = state,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            getDisplayMode = getDisplayMode,
            getColumnsForOrientation = getColumnsForOrientation,
            onToggleSelection = onToggleSelection,
            onToggleRangeSelection = onToggleRangeSelection,
            onOpenAnime = onOpenAnime,
            onOpenEpisode = onOpenEpisode,
            onGlobalSearchClicked = onGlobalSearchClicked,
        )
    }
}

@Composable
private fun AnimeLibraryPager(
    pagerState: PagerState,
    state: AnimeLibraryScreenModel.State,
    contentPadding: PaddingValues,
    getDisplayMode: () -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    onToggleSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onToggleRangeSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long, Long) -> Unit,
    onGlobalSearchClicked: () -> Unit,
) {
    HorizontalPager(
        modifier = Modifier.fillMaxSize(),
        state = pagerState,
        verticalAlignment = Alignment.Top,
    ) { page ->
        if (page !in ((pagerState.currentPage - 1)..(pagerState.currentPage + 1))) {
            // Only keep nearby pages composed, matching the manga library pager behavior.
            return@HorizontalPager
        }

        val libraryPage = state.pages[page]
        val items = state.getItemsForPage(libraryPage)

        if (items.isEmpty()) {
            LibraryPageEmptyScreen(
                searchQuery = state.searchQuery,
                hasActiveFilters = state.hasActiveFilters,
                contentPadding = contentPadding,
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
                page = libraryPage,
                items = items,
                selection = state.selection,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                onToggleSelection = onToggleSelection,
                onToggleRangeSelection = onToggleRangeSelection,
                onOpenAnime = onOpenAnime,
                onOpenEpisode = onOpenEpisode,
            )
            LibraryDisplayMode.ComfortableGrid -> AnimeLibraryComfortableGrid(
                page = libraryPage,
                items = items,
                selection = state.selection,
                columns = columns,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                onToggleSelection = onToggleSelection,
                onToggleRangeSelection = onToggleRangeSelection,
                onOpenAnime = onOpenAnime,
                onOpenEpisode = onOpenEpisode,
            )
            LibraryDisplayMode.CompactGrid,
            LibraryDisplayMode.CoverOnlyGrid,
            -> AnimeLibraryCompactGrid(
                page = libraryPage,
                items = items,
                selection = state.selection,
                showTitle = displayMode == LibraryDisplayMode.CompactGrid,
                columns = columns,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                onToggleSelection = onToggleSelection,
                onToggleRangeSelection = onToggleRangeSelection,
                onOpenAnime = onOpenAnime,
                onOpenEpisode = onOpenEpisode,
            )
        }
    }
}

@Composable
private fun AnimeLibraryList(
    page: LibraryPage,
    items: List<AnimeLibraryScreenModel.AnimeLibraryItem>,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onToggleSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onToggleRangeSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long, Long) -> Unit,
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
                    if (item.unwatchedBadgeCount > 0) {
                        Badge(text = item.unwatchedBadgeCount.toString())
                    }
                    LanguageBadge(isLocal = false, sourceLanguage = item.sourceLanguage)
                },
                isSelected = item.animeId in selection,
                onLongClick = { onToggleRangeSelection(page, item) },
                onClick = {
                    if (selection.isNotEmpty()) {
                        onToggleSelection(page, item)
                    } else {
                        onOpenAnime(item.animeId)
                    }
                },
                onClickContinueReading = item.primaryEpisodeId?.takeIf {
                    item.showContinueWatching && item.unwatchedBadgeCount > 0
                }?.let { episodeId ->
                    { onOpenEpisode(item.animeId, item.primaryEpisodeAnimeId ?: item.animeId, episodeId) }
                },
                continueReadingProgress = item.progressFraction.takeIf { item.hasInProgress },
                continueReadingContentDescription = MR.strings.action_resume_watching,
            )
        }
    }
}

@Composable
private fun AnimeLibraryComfortableGrid(
    page: LibraryPage,
    items: List<AnimeLibraryScreenModel.AnimeLibraryItem>,
    selection: Set<Long>,
    columns: Int,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onToggleSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onToggleRangeSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long, Long) -> Unit,
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
                    if (item.unwatchedBadgeCount > 0) {
                        Badge(text = item.unwatchedBadgeCount.toString())
                    }
                },
                coverBadgeEnd = {
                    LanguageBadge(isLocal = false, sourceLanguage = item.sourceLanguage)
                },
                isSelected = item.animeId in selection,
                onLongClick = { onToggleRangeSelection(page, item) },
                onClick = {
                    if (selection.isNotEmpty()) {
                        onToggleSelection(page, item)
                    } else {
                        onOpenAnime(item.animeId)
                    }
                },
                onClickContinueReading = item.primaryEpisodeId?.takeIf {
                    item.showContinueWatching && item.unwatchedBadgeCount > 0
                }?.let { episodeId ->
                    { onOpenEpisode(item.animeId, item.primaryEpisodeAnimeId ?: item.animeId, episodeId) }
                },
                continueReadingProgress = item.progressFraction.takeIf { item.hasInProgress },
                continueReadingContentDescription = MR.strings.action_resume_watching,
            )
        }
    }
}

@Composable
private fun AnimeLibraryCompactGrid(
    page: LibraryPage,
    items: List<AnimeLibraryScreenModel.AnimeLibraryItem>,
    selection: Set<Long>,
    showTitle: Boolean,
    columns: Int,
    contentPadding: PaddingValues,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    onToggleSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onToggleRangeSelection: (LibraryPage, AnimeLibraryScreenModel.AnimeLibraryItem) -> Unit,
    onOpenAnime: (Long) -> Unit,
    onOpenEpisode: (Long, Long, Long) -> Unit,
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
                    if (item.unwatchedBadgeCount > 0) {
                        Badge(text = item.unwatchedBadgeCount.toString())
                    }
                },
                coverBadgeEnd = {
                    LanguageBadge(isLocal = false, sourceLanguage = item.sourceLanguage)
                },
                isSelected = item.animeId in selection,
                onLongClick = { onToggleRangeSelection(page, item) },
                onClick = {
                    if (selection.isNotEmpty()) {
                        onToggleSelection(page, item)
                    } else {
                        onOpenAnime(item.animeId)
                    }
                },
                onClickContinueReading = item.primaryEpisodeId?.takeIf {
                    item.showContinueWatching && item.unwatchedBadgeCount > 0
                }?.let { episodeId ->
                    { onOpenEpisode(item.animeId, item.primaryEpisodeAnimeId ?: item.animeId, episodeId) }
                },
                continueReadingProgress = item.progressFraction.takeIf { item.hasInProgress },
                continueReadingContentDescription = MR.strings.action_resume_watching,
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
    val configuration = LocalConfiguration.current
    val groupType = state.groupType
    val activeSortCategory = state.activeSortCategory
    val showCategoryTabs = state.showCategoryTabs
    val showItemCount = state.showItemCount
    val showContinueWatchingButton = state.showContinueWatchingButton
    val filterUnwatched by screenModel.getFilterUnwatched()
    val filterStarted by screenModel.getFilterStarted()
    val showUnwatchedBadge by screenModel.getShowUnwatchedBadge()
    val showLanguageBadge by screenModel.getShowLanguageBadge()
    val columns by remember(configuration.orientation, displayMode) {
        if (displayMode != LibraryDisplayMode.List) {
            screenModel.getColumnsForOrientation(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        } else {
            androidx.compose.runtime.mutableIntStateOf(0)
        }
    }
    val globalSort by screenModel.getSortMode()
    val currentSort = activeSortCategory.effectiveLibrarySort(globalSort)
    val sortingMode = currentSort.type
    val sortDescending = !currentSort.isAscending
    val sortOptions = listOf<Pair<dev.icerock.moko.resources.StringResource, LibrarySort.Type>>(
        MR.strings.action_sort_alpha to LibrarySort.Type.Alphabetical,
        MR.strings.action_sort_last_anime_update to LibrarySort.Type.LastUpdate,
        MR.strings.action_sort_unwatched_count to LibrarySort.Type.UnreadCount,
        MR.strings.action_sort_date_added to LibrarySort.Type.DateAdded,
        MR.strings.action_sort_random to LibrarySort.Type.Random,
    )

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
            stringResource(MR.strings.action_group),
        ),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .fillMaxWidth(),
        ) {
            ScrollbarLazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                when (page) {
                    0 -> {
                        item {
                            TriStateItem(
                                label = stringResource(MR.strings.action_filter_unwatched),
                                state = filterUnwatched,
                                onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterUnwatched) },
                            )
                        }
                        item {
                            TriStateItem(
                                label = stringResource(MR.strings.label_started),
                                state = filterStarted,
                                onClick = { screenModel.toggleFilter(LibraryPreferences::animeFilterStarted) },
                            )
                        }
                    }

                    1 -> {
                        items(sortOptions) { (titleRes, mode) ->
                            if (mode == LibrarySort.Type.Random) {
                                BaseSortItem(
                                    label = stringResource(titleRes),
                                    icon = Icons.Default.Refresh.takeIf { sortingMode == LibrarySort.Type.Random },
                                    onClick = {
                                        screenModel.setSort(
                                            category = activeSortCategory,
                                            mode = mode,
                                            direction = LibrarySort.Direction.Ascending,
                                        )
                                    },
                                )
                            } else {
                                SortItem(
                                    label = stringResource(titleRes),
                                    sortDescending = sortDescending.takeIf { sortingMode == mode },
                                    onClick = {
                                        val isTogglingDirection = sortingMode == mode
                                        val direction = when {
                                            isTogglingDirection -> if (sortDescending) {
                                                LibrarySort.Direction.Ascending
                                            } else {
                                                LibrarySort.Direction.Descending
                                            }
                                            else -> if (sortDescending) {
                                                LibrarySort.Direction.Descending
                                            } else {
                                                LibrarySort.Direction.Ascending
                                            }
                                        }
                                        screenModel.setSort(
                                            category = activeSortCategory,
                                            mode = mode,
                                            direction = direction,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    2 -> {
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

                        if (displayMode != LibraryDisplayMode.List) {
                            item {
                                SliderItem(
                                    value = columns,
                                    valueRange = 0..10,
                                    label = stringResource(MR.strings.pref_library_columns),
                                    valueString = if (columns > 0) {
                                        columns.toString()
                                    } else {
                                        stringResource(MR.strings.label_auto)
                                    },
                                    onChange = {
                                        screenModel
                                            .getColumnsForOrientation(
                                                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                                            )
                                            .value = it
                                    },
                                    pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            }
                        }

                        item {
                            HeadingItem(MR.strings.overlay_header)
                        }
                        item {
                            CheckboxItem(
                                label = stringResource(MR.strings.action_display_unwatched_badge),
                                checked = showUnwatchedBadge,
                                onClick = { screenModel.setShowUnwatchedBadge(!showUnwatchedBadge) },
                            )
                        }
                        item {
                            CheckboxItem(
                                label = stringResource(MR.strings.action_display_language_badge),
                                checked = showLanguageBadge,
                                onClick = { screenModel.setShowLanguageBadge(!showLanguageBadge) },
                            )
                        }
                        item {
                            CheckboxItem(
                                label = stringResource(MR.strings.action_display_show_continue_watching_button),
                                checked = showContinueWatchingButton,
                                onClick = { screenModel.setShowContinueWatchingButton(!showContinueWatchingButton) },
                            )
                        }
                        item {
                            HeadingItem(MR.strings.tabs_header)
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
                    }

                    3 -> {
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
        }
    }
}
