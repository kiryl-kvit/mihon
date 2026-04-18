package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
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
import eu.kanade.domain.anime.model.toMangaCover
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
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun AnimeBrowseSourceContent(
    animeList: LazyPagingItems<StateFlow<AnimeTitle>>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onAnimeClick: (AnimeTitle) -> Unit,
    onAnimeLongClick: (AnimeTitle) -> Unit,
    onWebViewClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    val errorState = animeList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: animeList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state -> with(context) { state.error.formattedMessage } }

    LaunchedEffect(errorState) {
        if (animeList.itemCount > 0 && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> animeList.retry()
            }
        }
    }

    if (animeList.itemCount == 0 && animeList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (animeList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = persistentListOf<EmptyScreenAction>().builder()
                .apply {
                    add(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = animeList::refresh,
                        ),
                    )
                    onWebViewClick?.let {
                        add(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_open_in_web_view,
                                icon = Icons.Outlined.Public,
                                onClick = it,
                            ),
                        )
                    }
                    onSettingsClick?.let {
                        add(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_settings,
                                icon = Icons.Outlined.Settings,
                                onClick = it,
                            ),
                        )
                    }
                }
                .build(),
        )
        return
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> AnimeBrowseComfortableGrid(
            animeList = animeList,
            columns = columns,
            contentPadding = contentPadding,
            onAnimeClick = onAnimeClick,
            onAnimeLongClick = onAnimeLongClick,
        )
        LibraryDisplayMode.List -> AnimeBrowseList(
            animeList = animeList,
            contentPadding = contentPadding,
            onAnimeClick = onAnimeClick,
            onAnimeLongClick = onAnimeLongClick,
        )
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> AnimeBrowseCompactGrid(
            animeList = animeList,
            columns = columns,
            contentPadding = contentPadding,
            onAnimeClick = onAnimeClick,
            onAnimeLongClick = onAnimeLongClick,
        )
    }
}

@Composable
private fun AnimeBrowseList(
    animeList: LazyPagingItems<StateFlow<AnimeTitle>>,
    contentPadding: PaddingValues,
    onAnimeClick: (AnimeTitle) -> Unit,
    onAnimeLongClick: (AnimeTitle) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding + PaddingValues(vertical = 8.dp)) {
        item {
            if (animeList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = animeList.itemCount,
            key = { index -> animeList[index]?.value?.id ?: "anime-browse-list-$index" },
        ) { index ->
            val anime by animeList[index]?.collectAsState() ?: return@items
            MangaListItem(
                title = anime.displayTitle,
                coverData = anime.toMangaCover(),
                coverAlpha = if (anime.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                badge = { InLibraryBadge(enabled = anime.favorite) },
                onLongClick = { onAnimeLongClick(anime) },
                onClick = { onAnimeClick(anime) },
            )
        }

        item {
            if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun AnimeBrowseComfortableGrid(
    animeList: LazyPagingItems<StateFlow<AnimeTitle>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onAnimeClick: (AnimeTitle) -> Unit,
    onAnimeLongClick: (AnimeTitle) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (animeList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = animeList.itemCount,
            key = { index -> animeList[index]?.value?.id ?: "anime-browse-comfortable-$index" },
        ) { index ->
            val anime by animeList[index]?.collectAsState() ?: return@items
            MangaComfortableGridItem(
                title = anime.displayTitle,
                coverData = anime.toMangaCover(),
                coverAlpha = if (anime.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                coverBadgeStart = { InLibraryBadge(enabled = anime.favorite) },
                onLongClick = { onAnimeLongClick(anime) },
                onClick = { onAnimeClick(anime) },
            )
        }

        if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
private fun AnimeBrowseCompactGrid(
    animeList: LazyPagingItems<StateFlow<AnimeTitle>>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onAnimeClick: (AnimeTitle) -> Unit,
    onAnimeLongClick: (AnimeTitle) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        if (animeList.loadState.prepend is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }

        items(
            count = animeList.itemCount,
            key = { index -> animeList[index]?.value?.id ?: "anime-browse-compact-$index" },
        ) { index ->
            val anime by animeList[index]?.collectAsState() ?: return@items
            MangaCompactGridItem(
                title = anime.displayTitle,
                coverData = anime.toMangaCover(),
                coverAlpha = if (anime.favorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                coverBadgeStart = { InLibraryBadge(enabled = anime.favorite) },
                onLongClick = { onAnimeLongClick(anime) },
                onClick = { onAnimeClick(anime) },
            )
        }

        if (animeList.loadState.refresh is LoadState.Loading || animeList.loadState.append is LoadState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BrowseSourceLoadingItem()
            }
        }
    }
}
