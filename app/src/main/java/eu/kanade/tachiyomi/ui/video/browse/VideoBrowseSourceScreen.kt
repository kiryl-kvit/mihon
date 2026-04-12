package eu.kanade.tachiyomi.ui.video.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons.AutoMirrored
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifVideoSourcesLoaded
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.video.VideoBrowseSourceContent
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.video.VideoScreen
import kotlinx.collections.immutable.persistentListOf
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.i18n.MR
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

data class VideoBrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifVideoSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { VideoBrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val source = screenModel.source
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (source == null) {
            Scaffold(
                topBar = {
                    AppBar(
                        title = stringResource(MR.strings.browse),
                        navigateUp = navigateUp,
                        scrollBehavior = it,
                    )
                },
            ) { paddingValues ->
                EmptyScreen(
                    message = stringResource(MR.strings.source_not_installed, sourceId.toString()),
                    modifier = Modifier.padding(paddingValues),
                )
            }
            return
        }

        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    VideoBrowseSourceToolbar(
                        title = source.name,
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onSearch = screenModel::search,
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == VideoBrowseSourceScreenModel.Listing.Popular,
                            onClick = {
                                screenModel.resetFilters()
                                screenModel.setListing(VideoBrowseSourceScreenModel.Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = { Text(text = stringResource(MR.strings.popular)) },
                        )
                        if (source.supportsLatest) {
                            FilterChip(
                                selected = state.listing == VideoBrowseSourceScreenModel.Listing.Latest,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(VideoBrowseSourceScreenModel.Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = { Text(text = stringResource(MR.strings.latest)) },
                            )
                        }
                        if (state.filters.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is VideoBrowseSourceScreenModel.Listing.Search,
                                onClick = screenModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = { Text(text = stringResource(MR.strings.action_filter)) },
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            VideoBrowseSourceContent(
                videoList = screenModel.videoPagerFlowFlow.collectAsLazyPagingItems(),
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onVideoClick = { video ->
                    if (video.id > 0L) {
                        navigator.push(VideoScreen(video.id))
                    }
                },
            )
        }

        when (state.dialog) {
            VideoBrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    filters = state.filters,
                    presets = emptyList(),
                    onReset = screenModel::resetFilters,
                    onApplyPreset = {},
                    onEditPreset = {},
                    onDeletePreset = {},
                    canDeletePreset = { false },
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun VideoBrowseSourceToolbar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            AppBarActions(
                actions = persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_display_mode),
                        icon = if (displayMode == LibraryDisplayMode.List) {
                            AutoMirrored.Filled.ViewList
                        } else {
                            Icons.Filled.ViewModule
                        },
                        onClick = { selectingDisplayMode = true },
                    ),
                ),
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
        },
    )
}
