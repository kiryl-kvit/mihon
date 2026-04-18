package eu.kanade.tachiyomi.ui.anime.browse.globalsearch

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.InLibraryBadge
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.ui.anime.browse.AnimeBrowseSourceScreen
import eu.kanade.tachiyomi.ui.anime.pushSourceAnimeScreen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeGlobalSearchScreen(
    private val searchQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val getMergedAnime = remember { Injekt.get<GetMergedAnime>() }
        val screenModel = rememberScreenModel { AnimeGlobalSearchScreenModel(initialQuery = searchQuery) }
        val state by screenModel.state.collectAsState()

        AnimeGlobalSearchScreenContent(
            state = state,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            getAnime = screenModel::getAnimeState,
            onClickSource = { source ->
                navigator.push(AnimeBrowseSourceScreen(source.id, state.searchQuery))
            },
            onClickItem = { anime ->
                scope.launch {
                    navigator.pushSourceAnimeScreen(anime.id, getMergedAnime)
                }
            },
            onLongClickItem = { anime ->
                scope.launch {
                    navigator.pushSourceAnimeScreen(anime.id, getMergedAnime)
                }
            },
        )
    }
}

@Composable
private fun AnimeGlobalSearchScreenContent(
    state: AnimeGlobalSearchScreenModel.State,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onToggleResults: () -> Unit,
    getAnime: @Composable (AnimeTitle) -> State<AnimeTitle>,
    onClickSource: (AnimeCatalogueSource) -> Unit,
    onClickItem: (AnimeTitle) -> Unit,
    onLongClickItem: (AnimeTitle) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AnimeGlobalSearchToolbar(
                searchQuery = state.searchQuery,
                progress = state.progress,
                total = state.total,
                navigateUp = navigateUp,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                sourceFilter = state.sourceFilter,
                onChangeSearchFilter = onChangeSearchFilter,
                onlyShowHasResults = state.onlyShowHasResults,
                onToggleResults = onToggleResults,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        AnimeGlobalSearchContent(
            items = state.filteredItems,
            contentPadding = paddingValues,
            getAnime = getAnime,
            onClickSource = onClickSource,
            onClickItem = onClickItem,
            onLongClickItem = onLongClickItem,
        )
    }
}

@Composable
private fun AnimeGlobalSearchToolbar(
    searchQuery: String?,
    progress: Int,
    total: Int,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    sourceFilter: SourceFilter,
    onChangeSearchFilter: (SourceFilter) -> Unit,
    onlyShowHasResults: Boolean,
    onToggleResults: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Box {
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                onClickCloseSearch = navigateUp,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
            if (progress in 1..<total) {
                LinearProgressIndicator(
                    progress = { progress / total.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            FilterChip(
                selected = sourceFilter == SourceFilter.PinnedOnly,
                onClick = { onChangeSearchFilter(SourceFilter.PinnedOnly) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = { Text(text = stringResource(MR.strings.pinned_sources)) },
            )
            FilterChip(
                selected = sourceFilter == SourceFilter.All,
                onClick = { onChangeSearchFilter(SourceFilter.All) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = { Text(text = stringResource(MR.strings.all)) },
            )

            VerticalDivider()

            FilterChip(
                selected = onlyShowHasResults,
                onClick = onToggleResults,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = { Text(text = stringResource(MR.strings.has_results)) },
            )
        }

        HorizontalDivider()
    }
}

@Composable
private fun AnimeGlobalSearchContent(
    items: Map<AnimeCatalogueSource, SearchItemResult>,
    contentPadding: PaddingValues,
    getAnime: @Composable (AnimeTitle) -> State<AnimeTitle>,
    onClickSource: (AnimeCatalogueSource) -> Unit,
    onClickItem: (AnimeTitle) -> Unit,
    onLongClickItem: (AnimeTitle) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyScreen(
            stringRes = MR.strings.anime_no_global_search_results,
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    LazyColumn(contentPadding = contentPadding) {
        items.forEach { (source, result) ->
            item(key = source.id) {
                GlobalSearchResultItem(
                    title = source.name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { onClickSource(source) },
                ) {
                    when (result) {
                        SearchItemResult.Loading -> GlobalSearchLoadingResultItem()
                        is SearchItemResult.Success -> {
                            AnimeGlobalSearchCardRow(
                                titles = result.result,
                                getAnime = getAnime,
                                onClick = onClickItem,
                                onLongClick = onLongClickItem,
                            )
                        }
                        is SearchItemResult.Error -> {
                            GlobalSearchErrorResultItem(message = stringResource(MR.strings.unknown_error))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeGlobalSearchCardRow(
    titles: List<AnimeTitle>,
    getAnime: @Composable (AnimeTitle) -> State<AnimeTitle>,
    onClick: (AnimeTitle) -> Unit,
    onLongClick: (AnimeTitle) -> Unit,
) {
    if (titles.isEmpty()) {
        Text(
            text = stringResource(MR.strings.anime_no_global_search_results),
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
        )
        return
    }

    LazyRow(
        contentPadding = PaddingValues(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        items(
            items = titles,
            key = { anime -> anime.id.takeIf { it > 0L } ?: "${anime.source}-${anime.url}" },
        ) { initialAnime ->
            val anime by getAnime(initialAnime)
            Box(modifier = Modifier.width(96.dp)) {
                MangaComfortableGridItem(
                    title = anime.displayTitle,
                    titleMaxLines = 3,
                    coverData = anime.toMangaCover(),
                    coverBadgeStart = { InLibraryBadge(enabled = anime.favorite) },
                    coverAlpha = if (anime.favorite) {
                        CommonMangaItemDefaults.BrowseFavoriteCoverAlpha
                    } else {
                        1f
                    },
                    onClick = { onClick(anime) },
                    onLongClick = { onLongClick(anime) },
                )
            }
        }
    }
}
