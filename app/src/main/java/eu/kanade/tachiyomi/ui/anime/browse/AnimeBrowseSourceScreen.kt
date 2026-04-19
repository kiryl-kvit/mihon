package eu.kanade.tachiyomi.ui.anime.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.AutoMirrored
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.anime.AnimeBrowseSourceContent
import eu.kanade.presentation.anime.AnimeMergeTargetPickerDialog
import eu.kanade.presentation.anime.DuplicateAnimeDialog
import eu.kanade.presentation.browse.components.BrowseLibraryActionDialog
import eu.kanade.presentation.browse.components.BrowseMergeEditorDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.AsyncAnimeCatalogueFilterSource
import eu.kanade.tachiyomi.source.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.anime.pushSourceAnimeScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AnimeBrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen() {

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { AnimeBrowseSourceScreenModel(sourceId, listingQuery) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val getMergedAnime = remember { Injekt.get<GetMergedAnime>() }
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
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val animeList = screenModel.animePagerFlow.collectAsLazyPagingItems()
        val httpSource = source as? AnimeHttpSource
        val configurableSource = source as? ConfigurableAnimeSource
        val onWebViewClick = httpSource?.let {
            {
                navigator.push(
                    WebViewScreen(
                        url = it.baseUrl,
                        initialTitle = it.name,
                        headers = it.headers.toMultimap().mapValues { values -> values.value.firstOrNull().orEmpty() },
                    ),
                )
            }
        }
        val onSettingsClick = configurableSource?.let {
            { navigator.push(AnimeSourcePreferencesScreen(sourceId, source.name)) }
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    AnimeBrowseSourceToolbar(
                        title = source.name,
                        searchQuery = state.toolbarQuery,
                        onSearchQueryChange = screenModel::setToolbarQuery,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigateUp,
                        onWebViewClick = onWebViewClick,
                        onSettingsClick = onSettingsClick,
                        onSearch = screenModel::search,
                    )

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == AnimeBrowseSourceScreenModel.Listing.Popular,
                            onClick = {
                                screenModel.resetFilters()
                                screenModel.setListing(AnimeBrowseSourceScreenModel.Listing.Popular)
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
                                selected = state.listing == AnimeBrowseSourceScreenModel.Listing.Latest,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(AnimeBrowseSourceScreenModel.Listing.Latest)
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
                        if (state.filters.isNotEmpty() || source is AsyncAnimeCatalogueFilterSource) {
                            FilterChip(
                                selected = state.listing is AnimeBrowseSourceScreenModel.Listing.Search,
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
            if (state.isWaitingForInitialFilterLoad) {
                when (val filterState = state.filterState) {
                    is BrowseFilterUiState.Error -> {
                        EmptyScreen(
                            message = filterState.throwable.message ?: stringResource(MR.strings.unknown_error),
                            modifier = Modifier.padding(paddingValues),
                        )
                    }
                    else -> LoadingScreen(Modifier.padding(paddingValues))
                }
            } else {
                AnimeBrowseSourceContent(
                    animeList = animeList,
                    columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                    displayMode = screenModel.displayMode,
                    snackbarHostState = snackbarHostState,
                    contentPadding = paddingValues,
                    onAnimeClick = { anime ->
                        scope.launch {
                            navigator.pushSourceAnimeScreen(anime.id, getMergedAnime)
                        }
                    },
                    onAnimeLongClick = { anime ->
                        scope.launch {
                            if (screenModel.onAnimeLongClick(anime)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    onWebViewClick = onWebViewClick,
                    onSettingsClick = onSettingsClick,
                )
            }
        }

        when (val dialog = state.dialog) {
            AnimeBrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    filters = state.filters,
                    isLoading = state.filterState is BrowseFilterUiState.Loading,
                    errorMessage = (state.filterState as? BrowseFilterUiState.Error)?.throwable?.message,
                    presets = emptyList(),
                    onReset = screenModel::resetFilters,
                    onApplyPreset = {},
                    onEditPreset = {},
                    onDeletePreset = {},
                    canDeletePreset = { false },
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                    onRetry = screenModel::retryFilterLoad,
                )
            }
            is AnimeBrowseSourceScreenModel.Dialog.ChangeAnimeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = screenModel::dismissDialog,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeAnimeFavorite(dialog.anime)
                        screenModel.moveAnimeToCategories(dialog.anime, include)
                    },
                )
            }
            is AnimeBrowseSourceScreenModel.Dialog.RemoveAnime -> {
                RemoveAnimeDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onConfirm = { screenModel.changeAnimeFavorite(dialog.anime) },
                )
            }
            is AnimeBrowseSourceScreenModel.Dialog.LibraryActionChooser -> {
                BrowseLibraryActionDialog(
                    mangaTitle = dialog.anime.displayTitle,
                    favorite = dialog.anime.favorite,
                    onDismissRequest = screenModel::dismissDialog,
                    onLibraryAction = {
                        screenModel.dismissDialog()
                        screenModel.confirmBrowseLibraryAction(dialog.anime)
                    },
                    onMergeIntoLibrary = { screenModel.showMergeTargetPicker(dialog.anime) },
                )
            }
            is AnimeBrowseSourceScreenModel.Dialog.DuplicateAnime -> {
                DuplicateAnimeDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = screenModel::dismissDialog,
                    onConfirm = { screenModel.addFavorite(dialog.anime) },
                    onOpenAnime = { navigator.push(AnimeScreen(it.id)) },
                    onMerge = { screenModel.openMergeEditorForDuplicate(it.anime.id) },
                )
            }
            is AnimeBrowseSourceScreenModel.Dialog.SelectMergeTarget -> {
                AnimeMergeTargetPickerDialog(
                    title = stringResource(MR.strings.action_merge_into_library),
                    query = dialog.query,
                    visibleTargets = dialog.visibleTargets,
                    onDismissRequest = screenModel::dismissDialog,
                    onQueryChange = screenModel::updateMergeTargetQuery,
                    onSelectTarget = screenModel::openMergeEditor,
                )
            }
            is AnimeBrowseSourceScreenModel.Dialog.EditMerge -> {
                BrowseMergeEditorDialog(
                    entries = dialog.entries,
                    targetId = dialog.targetId,
                    targetLocked = dialog.targetLocked,
                    removedIds = dialog.removedIds,
                    libraryRemovalIds = dialog.libraryRemovalIds,
                    confirmEnabled = dialog.enabled,
                    onDismissRequest = screenModel::dismissDialog,
                    onMove = screenModel::moveMergeEntry,
                    onSelectTarget = screenModel::setMergeTarget,
                    onToggleRemove = screenModel::toggleMergeEntryRemoval,
                    onToggleLibraryRemove = screenModel::toggleMergeEntryLibraryRemoval,
                    onConfirm = screenModel::confirmBrowseMerge,
                )
            }
            null -> Unit
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
    }
}

@Composable
private fun AnimeBrowseSourceToolbar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: (() -> Unit)?,
    onSettingsClick: (() -> Unit)?,
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
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_display_mode),
                                icon = if (displayMode == LibraryDisplayMode.List) {
                                    AutoMirrored.Filled.ViewList
                                } else {
                                    Icons.Filled.ViewModule
                                },
                                onClick = { selectingDisplayMode = true },
                            ),
                        )
                        onWebViewClick?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_web_view),
                                    onClick = it,
                                ),
                            )
                        }
                        onSettingsClick?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_settings),
                                    onClick = it,
                                ),
                            )
                        }
                    }
                    .build(),
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
