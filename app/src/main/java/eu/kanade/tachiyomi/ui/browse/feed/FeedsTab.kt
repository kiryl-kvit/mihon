package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.model.FeedListingMode
import eu.kanade.domain.source.model.SourceFeedPreset
import eu.kanade.domain.source.model.applySnapshot
import eu.kanade.domain.source.model.toListing
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.collections.immutable.persistentListOf
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun Screen.feedsTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { FeedsScreenModel() }
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.browse_feeds,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_add),
                icon = Icons.Outlined.Add,
                onClick = screenModel::showCreateDialog,
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.browse_manage_feeds),
                onClick = screenModel::showManageDialog,
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            FeedsTabContent(
                state = state,
                screenModel = screenModel,
                navigator = navigator,
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
            )
        },
    )
}

@Composable
private fun FeedsTabContent(
    state: FeedsScreenModel.State,
    screenModel: FeedsScreenModel,
    navigator: Navigator,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    if (!state.sourcesLoaded) {
        LoadingScreen()
        return
    }

    val activeFeed = screenModel.activeFeed()
    val activeSource = activeFeed?.let { screenModel.sourceFor(it.sourceId) }
    val activePreset = activeFeed?.let(screenModel::presetFor)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    if (state.enabledFeeds.isEmpty()) {
        EmptyScreen(
            message = stringResource(MR.strings.browse_feeds_empty),
            modifier = Modifier.padding(contentPadding),
        )
    } else if (activeFeed != null && activeSource != null && activePreset != null) {
        val browseModel = rememberActiveFeedScreenModel(
            activeFeedId = activeFeed.id,
            sourceId = activeSource.id,
            listingQuery = activePreset.toListing().requestQuery,
        )
        val browseModelState by browseModel.state.collectAsState()
        val source = browseModel.source as CatalogueSource

        LaunchedEffect(activeFeed.id, activePreset.id) {
            val filters = source.getFilterList().applySnapshot(activePreset.filters)
            when (activePreset.listingMode) {
                FeedListingMode.Popular -> {
                    browseModel.resetFilters()
                    browseModel.setListing(BrowseSourceScreenModel.Listing.Popular)
                }
                FeedListingMode.Latest -> {
                    browseModel.resetFilters()
                    browseModel.setListing(BrowseSourceScreenModel.Listing.Latest)
                }
                FeedListingMode.Search -> {
                    browseModel.setFilters(filters)
                    browseModel.search(
                        query = activePreset.query,
                        filters = filters,
                    )
                }
            }
        }

        Column {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {},
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(
                            start = MaterialTheme.padding.small,
                            end = MaterialTheme.padding.small,
                            top = contentPadding.calculateTopPadding() + MaterialTheme.padding.small,
                            bottom = MaterialTheme.padding.small,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    state.enabledFeeds.forEach { feed ->
                        val preset = screenModel.presetFor(feed) ?: return@forEach
                        val sourceItem = screenModel.sourceFor(feed.sourceId) ?: return@forEach
                        FilterChip(
                            selected = state.selectedFeedId == feed.id,
                            onClick = { screenModel.selectFeed(feed.id) },
                            leadingIcon = {
                                val icon = when (preset.listingMode) {
                                    FeedListingMode.Popular -> Icons.Outlined.Favorite
                                    FeedListingMode.Latest -> Icons.Outlined.NewReleases
                                    FeedListingMode.Search -> null
                                }
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            },
                            label = { Text(text = "${sourceItem.name}: ${preset.name}") },
                        )
                    }
                }
                HorizontalDivider()
            }
            BrowseSourceContent(
                source = browseModel.source,
                mangaList = browseModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                columns = browseModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = browseModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                onWebViewClick = {
                    val httpSource = browseModel.source as? HttpSource ?: return@BrowseSourceContent
                    navigator.push(
                        WebViewScreen(
                            url = httpSource.baseUrl,
                            initialTitle = httpSource.name,
                            sourceId = httpSource.id,
                        ),
                    )
                },
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) },
                onMangaClick = { navigator.push(MangaScreen(it.id, true)) },
                onMangaLongClick = { manga ->
                    scope.launchIO {
                        val duplicates = browseModel.getDuplicateLibraryManga(manga)
                        when {
                            manga.favorite -> browseModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                            duplicates.isNotEmpty() -> browseModel.setDialog(
                                BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                            )
                            else -> browseModel.addFavorite(manga)
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            )

            when (val dialog = browseModelState.dialog) {
                is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                    DuplicateMangaDialog(
                        duplicates = dialog.duplicates,
                        onDismissRequest = { browseModel.setDialog(null) },
                        onConfirm = { browseModel.addFavorite(dialog.manga) },
                        onOpenManga = { navigator.push(MangaScreen(it.id)) },
                        onMigrate = { browseModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                    )
                }
                is BrowseSourceScreenModel.Dialog.Migrate -> {
                    with(BrowseSourceScreen(activeSource.id, activePreset.toListing().requestQuery)) {
                        MigrateMangaDialog(
                            current = dialog.current,
                            target = dialog.target,
                            onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                            onDismissRequest = { browseModel.setDialog(null) },
                        )
                    }
                }
                is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                    RemoveMangaDialog(
                        onDismissRequest = { browseModel.setDialog(null) },
                        onConfirm = { browseModel.changeMangaFavorite(dialog.manga) },
                        mangaToRemove = dialog.manga,
                    )
                }
                is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                    ChangeCategoryDialog(
                        initialSelection = dialog.initialSelection,
                        onDismissRequest = { browseModel.setDialog(null) },
                        onEditCategories = { navigator.push(CategoryScreen()) },
                        onConfirm = { include, _ ->
                            browseModel.changeMangaFavorite(dialog.manga)
                            browseModel.moveMangaToCategories(dialog.manga, include)
                        },
                    )
                }
                else -> Unit
            }
        }
    }

    when (val dialog = state.dialog) {
        FeedsScreenModel.Dialog.SelectSource -> {
            FeedSourcePickerDialog(
                sources = state.sources,
                onDismissRequest = screenModel::closeDialog,
                onSelectSource = screenModel::selectSource,
            )
        }
        is FeedsScreenModel.Dialog.SelectPreset -> {
            val source = screenModel.sourceFor(dialog.sourceId)
            if (source != null) {
                FeedPresetPickerDialog(
                    source = source,
                    presets = screenModel.presetsFor(source),
                    onDismissRequest = screenModel::closeDialog,
                    onSelectPreset = { preset -> screenModel.createFeed(source.id, preset.id) },
                )
            }
        }
        FeedsScreenModel.Dialog.ManageFeeds -> {
            ManageFeedsDialog(
                state = state,
                screenModel = screenModel,
                onDismissRequest = screenModel::closeDialog,
            )
        }
        null -> Unit
    }
}

@Composable
private fun rememberActiveFeedScreenModel(
    activeFeedId: String,
    sourceId: Long,
    listingQuery: String,
): BrowseSourceScreenModel {
    return object : Screen {
        override val key: ScreenKey = "feed-screen-model-$activeFeedId"

        @Composable
        override fun Content() {
            error("Not used")
        }
    }.rememberScreenModel(tag = activeFeedId) {
        BrowseSourceScreenModel(
            sourceId = sourceId,
            listingQuery = listingQuery,
        )
    }
}

@Composable
private fun FeedSourcePickerDialog(
    sources: List<Source>,
    onDismissRequest: () -> Unit,
    onSelectSource: (Source) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        ScrollbarLazyColumn(contentPadding = topSmallPaddingValues) {
            items(
                items = sources,
                key = { "feed-source-${it.id}" },
            ) { source ->
                BaseSourceItem(
                    source = source,
                    modifier = Modifier.animateItemFastScroll(),
                    onClickItem = { onSelectSource(source) },
                )
            }
        }
    }
}

@Composable
private fun FeedPresetPickerDialog(
    source: Source,
    presets: List<SourceFeedPreset>,
    onDismissRequest: () -> Unit,
    onSelectPreset: (SourceFeedPreset) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.padding.small)) {
            Text(
                text = source.name,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            presets.forEach { preset ->
                FeedPresetItem(
                    source = source,
                    preset = preset,
                    onClick = { onSelectPreset(preset) },
                )
            }
        }
    }
}

@Composable
private fun FeedPresetItem(
    source: Source,
    preset: SourceFeedPreset,
    onClick: () -> Unit,
) {
    BaseSourceItem(
        source = source,
        showLanguageInContent = false,
        onClickItem = onClick,
        content = { _, _ ->
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = preset.name,
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                )
            }
        },
    )
}

@Composable
private fun ManageFeedsDialog(
    state: FeedsScreenModel.State,
    screenModel: FeedsScreenModel,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        ScrollbarLazyColumn(contentPadding = topSmallPaddingValues) {
            items(
                items = state.feeds,
                key = { "manage-feed-${it.id}" },
            ) { feed ->
                val source = screenModel.sourceFor(feed.sourceId) ?: return@items
                val preset = screenModel.presetFor(feed) ?: return@items
                Row(
                    modifier = Modifier.animateItemFastScroll(),
                ) {
                    SwitchPreferenceWidget(
                        modifier = Modifier.weight(1f),
                        title = "${source.name}: ${preset.name}",
                        checked = feed.enabled,
                        onCheckedChanged = { screenModel.toggleFeed(feed.id, it) },
                    )
                    IconButton(
                        onClick = { screenModel.removeFeed(feed.id) },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
            }
        }
    }
}
