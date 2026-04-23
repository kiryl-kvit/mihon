package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.Source
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.source.local.LocalSource

@Composable
fun ChronologicalFeedBrowseContent(
    source: Source?,
    screenModel: ChronologicalFeedScreenModel,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val context = LocalContext.current
    val state by screenModel.state.collectAsState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var restoredDisplayMode by rememberSaveable { mutableStateOf<String?>(null) }

    val getErrorMessage: (Throwable) -> String = { throwable ->
        with(context) { throwable.formattedMessage }
    }
    val savedAnchor = screenModel.savedAnchorSnapshot()

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        if (state.mangaIds.isEmpty()) return@LaunchedEffect

        val result = snackbarHostState.showSnackbar(
            message = getErrorMessage(error),
            actionLabel = context.stringResource(MR.strings.action_retry),
            duration = SnackbarDuration.Indefinite,
        )
        when (result) {
            SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
            SnackbarResult.ActionPerformed -> screenModel.refresh()
        }
    }

    LaunchedEffect(displayMode, state.mangaIds, savedAnchor) {
        if (state.mangaIds.isEmpty()) return@LaunchedEffect

        val modeKey = displayMode.serialize()
        if (restoredDisplayMode == modeKey) return@LaunchedEffect

        val anchorIndex = savedAnchor.mangaId
            ?.let(state.mangaIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: 0

        when (displayMode) {
            LibraryDisplayMode.List -> {
                listState.scrollToItem(anchorIndex, savedAnchor.scrollOffset)
            }
            else -> {
                gridState.scrollToItem(anchorIndex, savedAnchor.scrollOffset)
            }
        }

        restoredDisplayMode = modeKey
    }

    LaunchedEffect(displayMode, state.mangaIds) {
        if (displayMode != LibraryDisplayMode.List) return@LaunchedEffect

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(ANCHOR_SAVE_DEBOUNCE_MILLIS)
            .collectLatest { (index, offset) ->
                screenModel.saveAnchor(
                    mangaId = state.mangaIds.getOrNull(index),
                    scrollOffset = offset,
                )
            }
    }

    LaunchedEffect(displayMode, state.mangaIds) {
        if (displayMode == LibraryDisplayMode.List) return@LaunchedEffect

        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(ANCHOR_SAVE_DEBOUNCE_MILLIS)
            .collectLatest { (index, offset) ->
                screenModel.saveAnchor(
                    mangaId = state.mangaIds.getOrNull(index),
                    scrollOffset = offset,
                )
            }
    }

    LaunchedEffect(displayMode, state.newItemsAvailableCount) {
        if (state.newItemsAvailableCount == 0) return@LaunchedEffect

        if (displayMode == LibraryDisplayMode.List) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collectLatest { firstVisibleItemIndex ->
                    if (firstVisibleItemIndex < state.newItemsAvailableCount) {
                        screenModel.consumeNewItemsIndicator()
                    }
                }
        } else {
            snapshotFlow { gridState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collectLatest { firstVisibleItemIndex ->
                    if (firstVisibleItemIndex < state.newItemsAvailableCount) {
                        screenModel.consumeNewItemsIndicator()
                    }
                }
        }
    }

    LaunchedEffect(displayMode, state.isRefreshing, state.isAppending, state.nextPageKey, state.mangaIds.size) {
        if (displayMode != LibraryDisplayMode.List) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collectLatest { lastVisibleItemIndex ->
                if (shouldLoadMore(lastVisibleItemIndex, state)) {
                    screenModel.loadMore()
                }
            }
    }

    LaunchedEffect(displayMode, state.isRefreshing, state.isAppending, state.nextPageKey, state.mangaIds.size) {
        if (displayMode == LibraryDisplayMode.List) return@LaunchedEffect

        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collectLatest { lastVisibleItemIndex ->
                if (shouldLoadMore(lastVisibleItemIndex, state)) {
                    screenModel.loadMore()
                }
            }
    }

    if (!state.hasLoaded && state.isRefreshing && state.mangaIds.isEmpty()) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (state.mangaIds.isEmpty()) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = state.error?.let(getErrorMessage) ?: stringResource(MR.strings.no_results_found),
            actions = if (source is LocalSource) {
                persistentListOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                persistentListOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = screenModel::refresh,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.action_open_in_web_view,
                        icon = Icons.Outlined.Public,
                        onClick = onWebViewClick,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.label_help,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onHelpClick,
                    ),
                )
            },
        )
        return
    }

    Box {
        when (displayMode) {
            LibraryDisplayMode.ComfortableGrid -> {
                ChronologicalFeedComfortableGrid(
                    screenModel = screenModel,
                    mangaIds = state.mangaIds,
                    columns = columns,
                    contentPadding = contentPadding,
                    gridState = gridState,
                    isAppending = state.isAppending,
                    onMangaClick = onMangaClick,
                    onMangaLongClick = onMangaLongClick,
                )
            }
            LibraryDisplayMode.List -> {
                ChronologicalFeedList(
                    screenModel = screenModel,
                    mangaIds = state.mangaIds,
                    contentPadding = contentPadding,
                    listState = listState,
                    isAppending = state.isAppending,
                    onMangaClick = onMangaClick,
                    onMangaLongClick = onMangaLongClick,
                )
            }
            LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                ChronologicalFeedCompactGrid(
                    screenModel = screenModel,
                    mangaIds = state.mangaIds,
                    columns = columns,
                    contentPadding = contentPadding,
                    gridState = gridState,
                    isAppending = state.isAppending,
                    onMangaClick = onMangaClick,
                    onMangaLongClick = onMangaLongClick,
                )
            }
        }

        if (state.newItemsAvailableCount > 0 && !state.isRefreshing) {
            NewItemsChip(
                count = state.newItemsAvailableCount,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .align(androidx.compose.ui.Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun NewItemsChip(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = null,
            )
            Text(
                style = MaterialTheme.typography.labelLarge,
                text = pluralStringResource(MR.plurals.browse_feed_new_items, count, count),
            )
        }
    }
}

@Composable
private fun ChronologicalFeedList(
    screenModel: ChronologicalFeedScreenModel,
    mangaIds: List<Long>,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isAppending: Boolean,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
    ) {
        items(
            count = mangaIds.size,
            key = { index -> mangaIds[index] },
        ) { index ->
            ChronologicalFeedMangaListItem(
                mangaId = mangaIds[index],
                screenModel = screenModel,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }

        if (isAppending) {
            item {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun ChronologicalFeedCompactGrid(
    screenModel: ChronologicalFeedScreenModel,
    mangaIds: List<Long>,
    columns: GridCells,
    contentPadding: PaddingValues,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    isAppending: Boolean,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(
            count = mangaIds.size,
            key = { index -> mangaIds[index] },
        ) { index ->
            ChronologicalFeedMangaCompactGridItem(
                mangaId = mangaIds[index],
                screenModel = screenModel,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }

        if (isAppending) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun ChronologicalFeedComfortableGrid(
    screenModel: ChronologicalFeedScreenModel,
    mangaIds: List<Long>,
    columns: GridCells,
    contentPadding: PaddingValues,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    isAppending: Boolean,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(
            count = mangaIds.size,
            key = { index -> mangaIds[index] },
        ) { index ->
            ChronologicalFeedMangaComfortableGridItem(
                mangaId = mangaIds[index],
                screenModel = screenModel,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }

        if (isAppending) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun ChronologicalFeedMangaListItem(
    mangaId: Long,
    screenModel: ChronologicalFeedScreenModel,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val manga = rememberChronologicalManga(mangaId, screenModel) ?: return

    MangaListItem(
        title = manga.title,
        coverData = manga.toCoverData(),
        coverAlpha = manga.browseCoverAlpha(),
        badge = { InLibraryBadge(enabled = manga.favorite) },
        onLongClick = { onMangaLongClick(manga) },
        onClick = { onMangaClick(manga) },
    )
}

@Composable
private fun ChronologicalFeedMangaCompactGridItem(
    mangaId: Long,
    screenModel: ChronologicalFeedScreenModel,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val manga = rememberChronologicalManga(mangaId, screenModel) ?: return

    MangaCompactGridItem(
        title = manga.title,
        coverData = manga.toCoverData(),
        coverAlpha = manga.browseCoverAlpha(),
        coverBadgeStart = { InLibraryBadge(enabled = manga.favorite) },
        onLongClick = { onMangaLongClick(manga) },
        onClick = { onMangaClick(manga) },
    )
}

@Composable
private fun ChronologicalFeedMangaComfortableGridItem(
    mangaId: Long,
    screenModel: ChronologicalFeedScreenModel,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val manga = rememberChronologicalManga(mangaId, screenModel) ?: return

    MangaComfortableGridItem(
        title = manga.title,
        coverData = manga.toCoverData(),
        coverAlpha = manga.browseCoverAlpha(),
        coverBadgeStart = { InLibraryBadge(enabled = manga.favorite) },
        onLongClick = { onMangaLongClick(manga) },
        onClick = { onMangaClick(manga) },
    )
}

@Composable
private fun rememberChronologicalManga(
    mangaId: Long,
    screenModel: ChronologicalFeedScreenModel,
): Manga? {
    var manga by remember(mangaId) { mutableStateOf<Manga?>(null) }

    LaunchedEffect(mangaId, screenModel) {
        screenModel.subscribeManga(mangaId).collectLatest {
            manga = it
        }
    }

    return manga
}

private fun Manga.toCoverData(): MangaCover {
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    )
}

private fun Manga.browseCoverAlpha(): Float {
    return if (favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f
}

private fun shouldLoadMore(
    lastVisibleItemIndex: Int,
    state: ChronologicalFeedScreenModel.State,
): Boolean {
    if (lastVisibleItemIndex < 0) return false
    if (state.isRefreshing || state.isAppending || state.nextPageKey == null) return false

    return lastVisibleItemIndex >= state.mangaIds.lastIndex - LOAD_MORE_VISIBLE_THRESHOLD
}

private const val ANCHOR_SAVE_DEBOUNCE_MILLIS = 150L
private const val LOAD_MORE_VISIBLE_THRESHOLD = 3
