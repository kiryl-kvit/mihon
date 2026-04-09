package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.model.FeedListingMode
import eu.kanade.domain.source.model.SourceFeed
import eu.kanade.domain.source.model.SourceFeedPreset
import eu.kanade.domain.source.model.applySnapshot
import eu.kanade.domain.source.model.snapshot
import eu.kanade.domain.source.model.toListing
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.browse.components.BrowseMangaPreviewSheet
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.manga.DuplicateMangaDialog
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
import mihon.feature.profiles.core.ProfileManager
import mihon.presentation.core.util.collectAsLazyPagingItems
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.Source
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun Screen.feedsTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val profileManager = remember { Injekt.get<ProfileManager>() }
    val activeProfile by profileManager.activeProfile.collectAsState()
    val screenModel = rememberScreenModel { FeedsScreenModel() }
    val state by screenModel.state.collectAsState()
    val singleEnabledFeed = state.enabledFeeds.singleOrNull()
    val singleEnabledFeedSource = singleEnabledFeed?.let { screenModel.sourceFor(it.sourceId) }
    val singleEnabledFeedPreset = singleEnabledFeed?.let(screenModel::presetFor)

    return TabContent(
        titleRes = MR.strings.browse_feeds,
        tabLabel = if (singleEnabledFeedSource != null && singleEnabledFeedPreset != null) {
            {
                SingleFeedTabLabel(
                    source = singleEnabledFeedSource,
                    preset = singleEnabledFeedPreset,
                )
            }
        } else {
            null
        },
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
                activeProfileId = activeProfile?.id,
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
private fun SingleFeedTabLabel(
    source: Source,
    preset: SourceFeedPreset,
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SourceIcon(
            source = source,
            modifier = Modifier
                .size(18.dp)
                .clip(MaterialTheme.shapes.extraSmall),
        )
        Text(
            text = preset.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun FeedsTabContent(
    activeProfileId: Long?,
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
        val browseContentStateHolder = rememberSaveableStateHolder()
        key(activeProfileId, activeFeed.id) {
            val browseModel = rememberActiveFeedScreenModel(
                activeProfileId = activeProfileId,
                activeFeedId = activeFeed.id,
                sourceId = activeSource.id,
                listingQuery = activePreset.toListing().requestQuery,
                initialFilterSnapshot = activePreset.filters,
            )
            val browseModelState by browseModel.state.collectAsState()
            val mangaList = browseModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
            val isRefreshing = mangaList.itemCount > 0 && mangaList.loadState.refresh is LoadState.Loading
            val source = browseModel.source as CatalogueSource
            val activeIndex = remember(state.enabledFeeds, activeFeed.id) {
                state.enabledFeeds.indexOfFirst { it.id == activeFeed.id }
            }
            val hasPreviousFeed = activeIndex > 0
            val hasNextFeed = activeIndex in 0 until state.enabledFeeds.lastIndex

            LaunchedEffect(activeProfileId) {
                screenModel.closeDialog()
                browseModel.dismissDialog()
            }

            LaunchedEffect(activeFeed.id, activePreset.id) {
                val savedListing = activePreset.toListing()
                val currentFilters = browseModelState.filters.snapshot()
                val shouldApplyPreset = when (activePreset.listingMode) {
                    FeedListingMode.Popular -> browseModelState.listing != BrowseSourceScreenModel.Listing.Popular
                    FeedListingMode.Latest -> browseModelState.listing != BrowseSourceScreenModel.Listing.Latest
                    FeedListingMode.Search -> {
                        val listing = browseModelState.listing as? BrowseSourceScreenModel.Listing.Search
                        listing?.query != activePreset.query || currentFilters != savedListing.filters
                    }
                }

                if (!shouldApplyPreset) return@LaunchedEffect

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

            Column(
                modifier = Modifier.pointerInput(Unit) {},
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val feedContentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding(),
                    )
                    FeedBrowseContent(
                        stateHolder = browseContentStateHolder,
                        activeProfileId = activeProfileId,
                        activeFeedId = activeFeed.id,
                    ) {
                        PullRefresh(
                            refreshing = isRefreshing,
                            enabled = true,
                            onRefresh = mangaList::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            BrowseSourceContent(
                                source = browseModel.source,
                                mangaList = mangaList,
                                columns = browseModel.getColumnsPreference(LocalConfiguration.current.orientation),
                                displayMode = browseModel.displayMode,
                                snackbarHostState = snackbarHostState,
                                contentPadding = feedContentPadding,
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
                                        if (browseModel.onMangaLongClick(manga)) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                if (state.enabledFeeds.size > 1) {
                    FeedNavigationBar(
                        feeds = state.enabledFeeds,
                        selectedFeedId = activeFeed.id,
                        screenModel = screenModel,
                        canGoPrevious = hasPreviousFeed,
                        canGoNext = hasNextFeed,
                        onPreviousClick = {
                            state.enabledFeeds.getOrNull(activeIndex - 1)?.let { screenModel.selectFeed(it.id) }
                        },
                        onNextClick = {
                            state.enabledFeeds.getOrNull(activeIndex + 1)?.let { screenModel.selectFeed(it.id) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                when (val dialog = browseModelState.dialog) {
                    is BrowseSourceScreenModel.Dialog.MangaPreview -> {
                        BrowseMangaPreviewSheet(
                            mangaId = dialog.mangaId,
                            previewSize = browseModel.mangaPreviewSizeUi(),
                            onLibraryAction = browseModel::onMangaLibraryAction,
                            onDismissRequest = browseModel::dismissDialog,
                        )
                    }
                    is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                        DuplicateMangaDialog(
                            duplicates = dialog.duplicates,
                            onDismissRequest = browseModel::dismissDialog,
                            onConfirm = { browseModel.addFavorite(dialog.manga) },
                            onOpenManga = { navigator.push(MangaScreen(it.id)) },
                            onMigrate = {
                                browseModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it))
                            },
                        )
                    }
                    is BrowseSourceScreenModel.Dialog.Migrate -> {
                        with(BrowseSourceScreen(activeSource.id, activePreset.toListing().requestQuery)) {
                            MigrateMangaDialog(
                                current = dialog.current,
                                target = dialog.target,
                                onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                                onDismissRequest = browseModel::dismissDialog,
                            )
                        }
                    }
                    is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                        RemoveMangaDialog(
                            onDismissRequest = browseModel::dismissDialog,
                            onConfirm = { browseModel.changeMangaFavorite(dialog.manga) },
                            mangaToRemove = dialog.manga,
                        )
                    }
                    is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                        ChangeCategoryDialog(
                            initialSelection = dialog.initialSelection,
                            onDismissRequest = browseModel::dismissDialog,
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
private fun FeedBrowseContent(
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    activeProfileId: Long?,
    activeFeedId: String,
    content: @Composable () -> Unit,
) {
    stateHolder.SaveableStateProvider(key = "feed-content-${activeProfileId ?: "none"}-$activeFeedId") {
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun FeedNavigationBar(
    feeds: List<SourceFeed>,
    selectedFeedId: String,
    screenModel: FeedsScreenModel,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipListState = rememberLazyListState()

    LaunchedEffect(selectedFeedId, feeds) {
        val selectedIndex = feeds.indexOfFirst { it.id == selectedFeedId }
        if (selectedIndex >= 0) {
            chipListState.animateScrollToItem(selectedIndex)
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = MaterialTheme.padding.small),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.padding.small,
                    end = MaterialTheme.padding.small,
                    top = MaterialTheme.padding.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousClick, enabled = canGoPrevious) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(MR.strings.transition_previous),
                )
            }
            LazyRow(
                modifier = Modifier
                    .weight(1f),
                state = chipListState,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(feeds.size, key = { feeds[it].id }) { index ->
                    val feed = feeds[index]
                    val source = screenModel.sourceFor(feed.sourceId) ?: return@items
                    val preset = screenModel.presetFor(feed) ?: return@items
                    FeedChip(
                        source = source,
                        preset = preset,
                        selected = selectedFeedId == feed.id,
                        onClick = { screenModel.selectFeed(feed.id) },
                    )
                }
            }
            IconButton(onClick = onNextClick, enabled = canGoNext) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(MR.strings.transition_next),
                )
            }
        }
    }
}

@Composable
private fun FeedChip(
    source: Source,
    preset: SourceFeedPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            labelColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        border = null,
        leadingIcon = {
            SourceIcon(
                source = source,
                modifier = Modifier
                    .size(FilterChipDefaults.IconSize)
                    .clip(MaterialTheme.shapes.extraSmall),
            )
        },
        trailingIcon = {
            val icon = when (preset.listingMode) {
                FeedListingMode.Popular -> Icons.Outlined.Favorite
                FeedListingMode.Latest -> Icons.Outlined.NewReleases
                FeedListingMode.Search -> null
            }
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        label = {
            Text(
                text = preset.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        },
    )
}

@Composable
private fun rememberActiveFeedScreenModel(
    activeProfileId: Long?,
    activeFeedId: String,
    sourceId: Long,
    listingQuery: String?,
    initialFilterSnapshot: List<eu.kanade.domain.source.model.FilterStateNode>,
): BrowseSourceScreenModel {
    val profileKey = activeProfileId?.toString() ?: "none"
    return object : Screen {
        override val key: ScreenKey = "feed-screen-model-$profileKey-$activeFeedId"

        @Composable
        override fun Content() {
            error("Not used")
        }
    }.rememberScreenModel(tag = "$profileKey:$activeFeedId") {
        BrowseSourceScreenModel(
            sourceId = sourceId,
            listingQuery = listingQuery,
            initialFilterSnapshot = initialFilterSnapshot,
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
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState, topSmallPaddingValues) { from, to ->
        val fromIndex = state.feeds.indexOfFirst { it.id == from.key }
        val toIndex = state.feeds.indexOfFirst { it.id == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        screenModel.reorderFeed(fromIndex, toIndex)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        ScrollbarLazyColumn(
            state = listState,
            contentPadding = topSmallPaddingValues,
        ) {
            items(
                items = state.feeds,
                key = { it.id },
            ) { feed ->
                val source = screenModel.sourceFor(feed.sourceId) ?: return@items
                val preset = screenModel.presetFor(feed) ?: return@items
                ReorderableItem(reorderableState, feed.id, enabled = state.feeds.size > 1) {
                    ManageFeedItem(
                        feed = feed,
                        source = source,
                        preset = preset,
                        onToggleEnabled = { screenModel.toggleFeed(feed.id, it) },
                        onDelete = { screenModel.removeFeed(feed.id) },
                        modifier = Modifier.animateItemFastScroll(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.ManageFeedItem(
    feed: SourceFeed,
    source: Source,
    preset: SourceFeedPreset,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MaterialTheme.padding.small),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                SourceIcon(
                    source = source,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                )
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = preset.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = feed.enabled,
            onCheckedChange = onToggleEnabled,
        )
        Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
            )
        }
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = null,
            modifier = Modifier
                .draggableHandle()
                .padding(MaterialTheme.padding.small),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
