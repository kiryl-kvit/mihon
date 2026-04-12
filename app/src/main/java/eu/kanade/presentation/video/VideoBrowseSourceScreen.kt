package eu.kanade.presentation.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.domain.video.model.toMangaCover
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.util.formattedMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun VideoBrowseSourceContent(
    videoList: LazyPagingItems<StateFlow<VideoTitle>>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onVideoClick: (VideoTitle) -> Unit,
) {
    val context = LocalContext.current

    val errorState = videoList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: videoList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state -> with(context) { state.error.formattedMessage } }

    LaunchedEffect(errorState) {
        if (videoList.itemCount > 0 && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> videoList.retry()
            }
        }
    }

    if (videoList.itemCount == 0 && videoList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (videoList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = persistentListOf(
                EmptyScreenAction(
                    stringRes = MR.strings.action_retry,
                    icon = Icons.Outlined.Refresh,
                    onClick = videoList::refresh,
                ),
            ),
        )
        return
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> VideoBrowseComfortableGrid(
            videoList = videoList,
            columns = columns,
            contentPadding = contentPadding,
            onVideoClick = onVideoClick,
        )
        LibraryDisplayMode.List -> VideoBrowseList(
            videoList = videoList,
            contentPadding = contentPadding,
            onVideoClick = onVideoClick,
        )
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> VideoBrowseCompactGrid(
            videoList = videoList,
            columns = columns,
            contentPadding = contentPadding,
            onVideoClick = onVideoClick,
        )
    }
}

@Composable
private fun VideoBrowseList(
    videoList: LazyPagingItems<StateFlow<VideoTitle>>,
    contentPadding: PaddingValues,
    onVideoClick: (VideoTitle) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding + PaddingValues(vertical = 8.dp)) {
        item {
            if (videoList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = videoList.itemCount,
            key = { index -> videoList[index]?.value?.id ?: "video-browse-list-$index" },
        ) { index ->
            val video by videoList[index]?.collectAsState() ?: return@items
            MangaListItem(
                title = video.displayTitle,
                coverData = video.toMangaCover(),
                coverAlpha = if (video.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                badge = { InLibraryBadge(enabled = video.favorite) },
                onLongClick = { onVideoClick(video) },
                onClick = { onVideoClick(video) },
            )
        }

        item {
            if (videoList.loadState.refresh is LoadState.Loading || videoList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun VideoBrowseComfortableGrid(
    videoList: LazyPagingItems<StateFlow<VideoTitle>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onVideoClick: (VideoTitle) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (videoList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = videoList.itemCount,
            key = { index -> videoList[index]?.value?.id ?: "video-browse-comfortable-$index" },
        ) { index ->
            val video by videoList[index]?.collectAsState() ?: return@items
            MangaComfortableGridItem(
                title = video.displayTitle,
                coverData = video.toMangaCover(),
                coverAlpha = if (video.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                coverBadgeStart = { InLibraryBadge(enabled = video.favorite) },
                onLongClick = { onVideoClick(video) },
                onClick = { onVideoClick(video) },
            )
        }

        if (videoList.loadState.refresh is LoadState.Loading || videoList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun VideoBrowseCompactGrid(
    videoList: LazyPagingItems<StateFlow<VideoTitle>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onVideoClick: (VideoTitle) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (videoList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = videoList.itemCount,
            key = { index -> videoList[index]?.value?.id ?: "video-browse-compact-$index" },
        ) { index ->
            val video by videoList[index]?.collectAsState() ?: return@items
            MangaCompactGridItem(
                title = video.displayTitle,
                coverData = video.toMangaCover(),
                coverAlpha = if (video.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                coverBadgeStart = { InLibraryBadge(enabled = video.favorite) },
                onLongClick = { onVideoClick(video) },
                onClick = { onVideoClick(video) },
            )
        }

        if (videoList.loadState.refresh is LoadState.Loading || videoList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}
