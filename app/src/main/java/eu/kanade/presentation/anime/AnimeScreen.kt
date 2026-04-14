package eu.kanade.presentation.anime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.DISALLOWED_MARKDOWN_TYPES
import eu.kanade.presentation.manga.components.MARKDOWN_INLINE_IMAGE_TAG
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.manga.components.getMarkdownLinkStyle
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.ui.anime.AnimeScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import kotlinx.collections.immutable.persistentListOf
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.shouldExpandFAB
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AnimeScreen(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEditDisplayNameClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onSearch: (String, Boolean) -> Unit,
    onTagSearch: (String) -> Unit,
    onCoverClicked: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onEpisodeSelected: (AnimeEpisode, Boolean, Boolean) -> Unit,
    onAllEpisodesSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onMarkSelectedWatched: (Boolean) -> Unit,
) {
    if (isTabletUi()) {
        AnimeScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = navigateUp,
            onRefresh = onRefresh,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditDisplayNameClicked = onEditDisplayNameClicked,
            onShareClicked = onShareClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onSearch = onSearch,
            onTagSearch = onTagSearch,
            onCoverClicked = onCoverClicked,
            onEpisodeClick = onEpisodeClick,
            onEpisodeSelected = onEpisodeSelected,
            onAllEpisodesSelected = onAllEpisodesSelected,
            onInvertSelection = onInvertSelection,
            onMarkSelectedWatched = onMarkSelectedWatched,
        )
    } else {
        AnimeScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = navigateUp,
            onRefresh = onRefresh,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEditDisplayNameClicked = onEditDisplayNameClicked,
            onShareClicked = onShareClicked,
            onWebViewClicked = onWebViewClicked,
            onWebViewLongClicked = onWebViewLongClicked,
            onSearch = onSearch,
            onTagSearch = onTagSearch,
            onCoverClicked = onCoverClicked,
            onEpisodeClick = onEpisodeClick,
            onEpisodeSelected = onEpisodeSelected,
            onAllEpisodesSelected = onAllEpisodesSelected,
            onInvertSelection = onInvertSelection,
            onMarkSelectedWatched = onMarkSelectedWatched,
        )
    }
}

@Composable
private fun AnimeScreenSmallImpl(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEditDisplayNameClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onSearch: (String, Boolean) -> Unit,
    onTagSearch: (String) -> Unit,
    onCoverClicked: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onEpisodeSelected: (AnimeEpisode, Boolean, Boolean) -> Unit,
    onAllEpisodesSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onMarkSelectedWatched: (Boolean) -> Unit,
) {
    val episodeListState = rememberLazyListState()

    BackHandler(enabled = state.isSelectionMode) {
        onAllEpisodesSelected(false)
    }

    Scaffold(
        topBar = {
            val isFirstItemVisible by remember {
                derivedStateOf { episodeListState.firstVisibleItemIndex == 0 }
            }
            val isFirstItemScrolled by remember {
                derivedStateOf { episodeListState.firstVisibleItemScrollOffset > 0 }
            }
            val titleAlpha by animateFloatAsState(
                if (!isFirstItemVisible) 1f else 0f,
                label = "anime-title-alpha",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "anime-background-alpha",
            )
            AnimeToolbar(
                title = state.anime.displayTitle,
                navigateUp = navigateUp,
                onRefresh = onRefresh,
                onEditCategoryClicked = onEditCategoryClicked,
                onEditDisplayNameClicked = onEditDisplayNameClicked,
                onShareClicked = onShareClicked,
                actionModeCounter = state.selectedCount,
                onCancelActionMode = { onAllEpisodesSelected(false) },
                onSelectAll = { onAllEpisodesSelected(true) },
                onInvertSelection = onInvertSelection,
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        bottomBar = {
            AnimeBottomActionMenu(
                visible = state.isSelectionMode,
                selectedEpisodes = state.episodes.filter { it.id in state.selection },
                onMarkSelectedWatched = onMarkSelectedWatched,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val primaryEpisode = state.primaryEpisode
            if (primaryEpisode != null && !state.isSelectionMode) {
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            text = stringResource(
                                if (state.playbackStateByEpisodeId[primaryEpisode.id]?.positionMs?.let { it > 0L } == true) {
                                    MR.strings.action_resume
                                } else {
                                    MR.strings.action_start
                                },
                            ),
                        )
                    },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = { onEpisodeClick(primaryEpisode.id) },
                    expanded = episodeListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.sourceAvailable,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            }
        },
    ) { contentPadding ->
        val topPadding = contentPadding.calculateTopPadding()
        val layoutDirection = LocalLayoutDirection.current

        PullRefresh(
            refreshing = state.isRefreshing,
            enabled = state.sourceAvailable && !state.isSelectionMode,
            onRefresh = onRefresh,
            indicatorPadding = PaddingValues(top = topPadding),
        ) {
            VerticalFastScroller(
                listState = episodeListState,
                topContentPadding = topPadding,
                endContentPadding = contentPadding.calculateEndPadding(layoutDirection),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    state = episodeListState,
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                    item {
                        AnimeInfoBox(
                            isTabletUi = false,
                            appBarPadding = topPadding,
                            anime = state.anime,
                            sourceName = state.sourceName,
                            sourceAvailable = state.sourceAvailable,
                            onSearch = onSearch,
                            onCoverClick = onCoverClicked,
                        )
                    }
                    item {
                        AnimeActionRow(
                            favorite = state.anime.favorite,
                            nextUpdateMillis = state.anime.nextUpdate,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onRefresh = onRefresh,
                            onEditCategoryClicked = onEditCategoryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                        )
                    }
                    item {
                        ExpandableAnimeDescription(
                            description = state.anime.description,
                            tags = state.anime.genre.orEmpty(),
                            onTagSearch = onTagSearch,
                        )
                    }
                    item {
                        EpisodeHeader(
                            episodeCount = state.episodes.size,
                            sourceAvailable = state.sourceAvailable,
                            sourceName = state.sourceName,
                        )
                    }
                    episodeItems(
                        episodes = state.episodes,
                        selectedEpisodeIds = state.selection,
                        selectionMode = state.isSelectionMode,
                        playbackStateByEpisodeId = state.playbackStateByEpisodeId,
                        sourceAvailable = state.sourceAvailable,
                        onEpisodeClick = onEpisodeClick,
                        onEpisodeSelected = onEpisodeSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimeScreenLargeImpl(
    state: AnimeScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEditDisplayNameClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
    onSearch: (String, Boolean) -> Unit,
    onTagSearch: (String) -> Unit,
    onCoverClicked: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onEpisodeSelected: (AnimeEpisode, Boolean, Boolean) -> Unit,
    onAllEpisodesSelected: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onMarkSelectedWatched: (Boolean) -> Unit,
) {
    val episodeListState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()

    BackHandler(enabled = state.isSelectionMode) {
        onAllEpisodesSelected(false)
    }

    Scaffold(
        topBar = {
            AnimeToolbar(
                title = state.anime.displayTitle,
                navigateUp = navigateUp,
                onRefresh = onRefresh,
                onEditCategoryClicked = onEditCategoryClicked,
                onEditDisplayNameClicked = onEditDisplayNameClicked,
                onShareClicked = onShareClicked,
                actionModeCounter = state.selectedCount,
                onCancelActionMode = { onAllEpisodesSelected(false) },
                onSelectAll = { onAllEpisodesSelected(true) },
                onInvertSelection = onInvertSelection,
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        bottomBar = {
            AnimeBottomActionMenu(
                visible = state.isSelectionMode,
                selectedEpisodes = state.episodes.filter { it.id in state.selection },
                onMarkSelectedWatched = onMarkSelectedWatched,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val primaryEpisode = state.primaryEpisode
            if (primaryEpisode != null && !state.isSelectionMode) {
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            text = stringResource(
                                if (state.playbackStateByEpisodeId[primaryEpisode.id]?.positionMs?.let { it > 0L } == true) {
                                    MR.strings.action_resume
                                } else {
                                    MR.strings.action_start
                                },
                            ),
                        )
                    },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = { onEpisodeClick(primaryEpisode.id) },
                    expanded = episodeListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.sourceAvailable,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            }
        },
    ) { contentPadding ->
        PullRefresh(
            refreshing = state.isRefreshing,
            enabled = state.sourceAvailable && !state.isSelectionMode,
            onRefresh = onRefresh,
            indicatorPadding = PaddingValues(
                start = insetPadding.calculateStartPadding(layoutDirection),
                top = contentPadding.calculateTopPadding(),
                end = insetPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            TwoPanelBox(
                modifier = Modifier.padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
                startContent = {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                    ) {
                        AnimeInfoBox(
                            isTabletUi = true,
                            appBarPadding = contentPadding.calculateTopPadding(),
                            anime = state.anime,
                            sourceName = state.sourceName,
                            sourceAvailable = state.sourceAvailable,
                            onSearch = onSearch,
                            onCoverClick = onCoverClicked,
                        )
                        AnimeActionRow(
                            favorite = state.anime.favorite,
                            nextUpdateMillis = state.anime.nextUpdate,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onRefresh = onRefresh,
                            onEditCategoryClicked = onEditCategoryClicked,
                            onWebViewClicked = onWebViewClicked,
                            onWebViewLongClicked = onWebViewLongClicked,
                        )
                        ExpandableAnimeDescription(
                            description = state.anime.description,
                            tags = state.anime.genre.orEmpty(),
                            onTagSearch = onTagSearch,
                        )
                    }
                },
                endContent = {
                    VerticalFastScroller(
                        listState = episodeListState,
                        topContentPadding = contentPadding.calculateTopPadding(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            state = episodeListState,
                            contentPadding = PaddingValues(
                                top = contentPadding.calculateTopPadding(),
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        ) {
                            item {
                                EpisodeHeader(
                                    episodeCount = state.episodes.size,
                                    sourceAvailable = state.sourceAvailable,
                                    sourceName = state.sourceName,
                                )
                            }
                            episodeItems(
                                episodes = state.episodes,
                                selectedEpisodeIds = state.selection,
                                selectionMode = state.isSelectionMode,
                                playbackStateByEpisodeId = state.playbackStateByEpisodeId,
                                sourceAvailable = state.sourceAvailable,
                                onEpisodeClick = onEpisodeClick,
                                onEpisodeSelected = onEpisodeSelected,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AnimeToolbar(
    title: String,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEditDisplayNameClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    actionModeCounter: Int,
    onCancelActionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
) {
    AppBar(
        titleContent = {
            if (actionModeCounter > 0) {
                AppBarTitle(title = actionModeCounter.toString())
            } else {
                AppBarTitle(
                    title = title,
                    modifier = Modifier.alpha(titleAlphaProvider()),
                )
            }
        },
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlphaProvider()),
        navigateUp = navigateUp,
        isActionMode = actionModeCounter > 0,
        onCancelActionMode = onCancelActionMode,
        actions = {
            if (actionModeCounter > 0) {
                AppBarActions(
                    actions = persistentListOf<AppBar.AppBarAction>(
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_all),
                            icon = Icons.Outlined.DoneAll,
                            onClick = onSelectAll,
                        ),
                        AppBar.Action(
                            title = stringResource(MR.strings.action_select_inverse),
                            icon = Icons.Outlined.Sync,
                            onClick = onInvertSelection,
                        ),
                    ),
                )
            } else {
                AppBarActions(
                    actions = persistentListOf<AppBar.AppBarAction>().builder()
                        .apply {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_retry),
                                    icon = Icons.Outlined.Refresh,
                                    onClick = onRefresh,
                                ),
                            )
                            if (onEditCategoryClicked != null) {
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_edit_categories),
                                        onClick = onEditCategoryClicked,
                                    ),
                                )
                            }
                            if (onEditDisplayNameClicked != null) {
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_set_display_name),
                                        onClick = onEditDisplayNameClicked,
                                    ),
                                )
                            }
                            if (onShareClicked != null) {
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_share),
                                        onClick = onShareClicked,
                                    ),
                                )
                            }
                        }
                        .build(),
                )
            }
        },
    )
}

@Composable
private fun AnimeInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    anime: AnimeTitle,
    sourceName: String?,
    sourceAvailable: Boolean,
    onSearch: (String, Boolean) -> Unit,
    onCoverClick: () -> Unit,
) {
    Box {
        val backdropGradientColors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
        AsyncImage(
            model = anime.toMangaCover(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = Brush.verticalGradient(colors = backdropGradientColors))
                }
                .blur(4.dp)
                .alpha(0.2f),
        )

        if (isTabletUi) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MangaCover.Book(
                    modifier = Modifier.fillMaxWidth(0.65f),
                    data = anime.toMangaCover(),
                    contentDescription = anime.displayTitle,
                    onClick = onCoverClick,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AnimeContentInfo(
                    anime = anime,
                    sourceName = sourceName,
                    sourceAvailable = sourceAvailable,
                    textAlign = TextAlign.Center,
                    onSearch = onSearch,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MangaCover.Book(
                    modifier = Modifier
                        .sizeIn(maxWidth = 100.dp)
                        .align(Alignment.Top),
                    data = anime.toMangaCover(),
                    contentDescription = anime.displayTitle,
                    onClick = onCoverClick,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimeContentInfo(
                        anime = anime,
                        sourceName = sourceName,
                        sourceAvailable = sourceAvailable,
                        textAlign = TextAlign.Start,
                        onSearch = onSearch,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AnimeContentInfo(
    anime: AnimeTitle,
    sourceName: String?,
    sourceAvailable: Boolean,
    textAlign: TextAlign,
    onSearch: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val title = anime.displayTitle.ifBlank { stringResource(MR.strings.unknown_title) }
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clickableNoIndication(
            onLongClick = { if (title.isNotBlank()) context.copyToClipboard(title, title) },
            onClick = { if (title.isNotBlank()) onSearch(title, true) },
        ),
        textAlign = textAlign,
    )

    val originalTitle = anime.originalTitle?.trim().takeUnless { it.isNullOrEmpty() || it == title }
    if (originalTitle != null) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = originalTitle,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .secondaryItemAlpha()
                .clickableNoIndication(
                    onLongClick = { context.copyToClipboard(originalTitle, originalTitle) },
                    onClick = { onSearch(originalTitle, true) },
                ),
            textAlign = textAlign,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    AnimeCompactMetadataLine(
        text = animeCreditsLine(anime),
        textAlign = textAlign,
    )

    AnimeStatusAndSourceRow(
        anime = anime,
        sourceName = sourceName,
        sourceAvailable = sourceAvailable,
        textAlign = textAlign,
        onSearch = onSearch,
    )

    AnimeCompactMetadataLine(
        text = animeFactsLine(anime),
        textAlign = textAlign,
    )
}

@Composable
private fun AnimeActionRow(
    favorite: Boolean,
    nextUpdateMillis: Long,
    onAddToLibraryClicked: () -> Unit,
    onRefresh: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onWebViewClicked: (() -> Unit)?,
    onWebViewLongClicked: (() -> Unit)?,
) {
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Row(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        AnimeActionButton(
            title = if (favorite) stringResource(MR.strings.in_library) else stringResource(MR.strings.add_to_library),
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategoryClicked,
        )
        AnimeActionButton(
            title = nextUpdateMillis.takeIf { it > 0L }?.let { relativeDateText(it) }
                ?: stringResource(MR.strings.not_applicable),
            icon = Icons.Filled.HourglassEmpty,
            color = if (nextUpdateMillis > 0L) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = {},
        )
        if (onWebViewClicked != null) {
            AnimeActionButton(
                title = stringResource(MR.strings.action_web_view),
                icon = Icons.Outlined.Public,
                color = defaultActionButtonColor,
                onClick = onWebViewClicked,
                onLongClick = onWebViewLongClicked,
            )
        } else {
            AnimeActionButton(
                title = stringResource(MR.strings.action_retry),
                icon = Icons.Outlined.Refresh,
                color = MaterialTheme.colorScheme.primary,
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun ColumnScope.AnimeStatusAndSourceRow(
    anime: AnimeTitle,
    sourceName: String?,
    sourceAvailable: Boolean,
    textAlign: TextAlign,
    onSearch: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val sourceText = sourceName ?: stringResource(MR.strings.source_not_installed, anime.source.toString())
    val isSourceError = !sourceAvailable
    val statusText = anime.status.toAnimeStatusText()

    if (statusText == null && sourceText.isBlank()) return

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        statusText?.let { status ->
            Icon(
                imageVector = anime.status.toAnimeStatusIcon(),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                Text(
                    text = status,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    textAlign = textAlign,
                )
                DotSeparatorText()
                if (isSourceError) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = sourceText,
                    modifier = Modifier.clickableNoIndication(
                        onLongClick = {
                            if (sourceText.isNotBlank()) {
                                context.copyToClipboard(sourceText, sourceText)
                            }
                        },
                        onClick = { onSearch(sourceText, false) },
                    ),
                    color = if (isSourceError) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        } ?: run {
            if (isSourceError) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = sourceText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickableNoIndication(
                    onLongClick = {
                        if (sourceText.isNotBlank()) {
                            context.copyToClipboard(sourceText, sourceText)
                        }
                    },
                    onClick = { onSearch(sourceText, false) },
                ),
                textAlign = textAlign,
                color = if (isSourceError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ColumnScope.AnimeCompactMetadataLine(
    text: String?,
    textAlign: TextAlign,
) {
    if (text.isNullOrBlank()) return

    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.secondaryItemAlpha(),
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun animeCreditsLine(anime: AnimeTitle): String? {
    val studioLabel = stringResource(MR.strings.studio)
    val directorLabel = stringResource(MR.strings.director)
    val writerLabel = stringResource(MR.strings.writer)
    val producerLabel = stringResource(MR.strings.producer)

    return listOfNotNull(
        anime.studio?.trim()?.takeIf { it.isNotEmpty() }?.let { "$studioLabel: $it" },
        anime.director?.trim()?.takeIf { it.isNotEmpty() }?.let { "$directorLabel: $it" },
        anime.writer?.trim()?.takeIf { it.isNotEmpty() }?.let { "$writerLabel: $it" },
        anime.producer?.trim()?.takeIf { it.isNotEmpty() }?.let { "$producerLabel: $it" },
    )
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" • ")
}

private fun animeFactsLine(anime: AnimeTitle): String? {
    return listOfNotNull(
        anime.country?.trim()?.takeIf { it.isNotEmpty() },
        anime.year?.trim()?.takeIf { it.isNotEmpty() },
        anime.duration?.trim()?.takeIf { it.isNotEmpty() },
    )
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" • ")
}


@Composable
private fun Long.toAnimeStatusText(): String? {
    return when (this) {
        SAnime.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
        SAnime.COMPLETED.toLong() -> stringResource(MR.strings.completed)
        SAnime.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
        SAnime.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
        SAnime.UNKNOWN.toLong() -> null
        else -> stringResource(MR.strings.unknown_status)
    }
}

private fun Long.toAnimeStatusIcon(): ImageVector {
    return when (this) {
        SAnime.ONGOING.toLong() -> Icons.Outlined.Schedule
        SAnime.COMPLETED.toLong() -> Icons.Outlined.DoneAll
        SAnime.CANCELLED.toLong() -> Icons.Outlined.Close
        SAnime.ON_HIATUS.toLong() -> Icons.Outlined.Pause
        else -> Icons.Outlined.Block
    }
}

@Composable
private fun RowScope.AnimeActionButton(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.weight(1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ExpandableAnimeDescription(
    description: String?,
    tags: List<String>,
    onTagSearch: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val resolvedDescription = description.takeUnless { it.isNullOrBlank() }
        ?: stringResource(MR.strings.anime_no_description)

    Column {
        AnimeSummary(
            description = resolvedDescription,
            expanded = expanded,
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
                .clickableNoIndication { expanded = !expanded },
        )

        if (tags.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .padding(vertical = 12.dp)
                    .animateContentSize()
                    .fillMaxWidth(),
            ) {
                var showMenu by remember { mutableStateOf(false) }
                var selectedTag by remember { mutableStateOf("") }
                val context = LocalContext.current
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_search)) },
                        onClick = {
                            onTagSearch(selectedTag)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_copy_to_clipboard)) },
                        onClick = {
                            if (selectedTag.isNotBlank()) {
                                context.copyToClipboard(selectedTag, selectedTag)
                            }
                            showMenu = false
                        },
                    )
                }
                if (expanded) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        tags.forEach { tag ->
                            AnimeTagChip(tag) {
                                selectedTag = tag
                                showMenu = true
                            }
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        items(tags) { tag ->
                            AnimeTagChip(tag) {
                                selectedTag = tag
                                showMenu = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeSummary(
    description: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val preferences = remember { Injekt.get<UiPreferences>() }
    val loadImages = remember { preferences.imagesInDescription.get() }
    val animProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        label = "anime-summary",
    )
    var infoHeight by remember { mutableIntStateOf(0) }

    Layout(
        modifier = modifier.clipToBounds(),
        contents = listOf(
            {
                Text(
                    text = "\n\n",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            {
                Column(
                    modifier = Modifier.onSizeChanged { size ->
                        infoHeight = size.height
                    },
                ) {
                    SelectionContainer {
                        MarkdownRender(
                            content = description,
                            modifier = Modifier.secondaryItemAlpha(),
                            annotator = animeDescriptionAnnotator(
                                loadImages = loadImages,
                                linkStyle = getMarkdownLinkStyle().toSpanStyle(),
                            ),
                            loadImages = loadImages,
                        )
                    }
                }
            },
            {
                val colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                Box(
                    modifier = Modifier.background(Brush.verticalGradient(colors = colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down)
                    Icon(
                        painter = rememberAnimatedVectorPainter(image, !expanded),
                        contentDescription = stringResource(
                            if (expanded) MR.strings.manga_info_collapse else MR.strings.manga_info_expand,
                        ),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(Brush.radialGradient(colors = colors.asReversed())),
                    )
                }
            },
        ),
    ) { (shrunk, actual, scrim), constraints ->
        val shrunkHeight = shrunk.single().measure(constraints).height
        val heightDelta = infoHeight - shrunkHeight
        val scrimHeight = 24.dp.roundToPx()

        val actualPlaceable = actual.single().measure(constraints)
        val scrimPlaceable = scrim.single().measure(
            Constraints.fixed(width = constraints.maxWidth, height = scrimHeight),
        )

        val currentHeight = shrunkHeight + ((heightDelta + scrimHeight) * animProgress).roundToInt()
        layout(constraints.maxWidth, currentHeight) {
            actualPlaceable.place(0, 0)
            scrimPlaceable.place(0, currentHeight - scrimHeight)
        }
    }
}

@Composable
private fun animeDescriptionAnnotator(loadImages: Boolean, linkStyle: SpanStyle) = remember(loadImages, linkStyle) {
    markdownAnnotator(
        annotate = { content, child ->
            if (!loadImages && child.type == MarkdownElementTypes.IMAGE) {
                val inlineLink = child.findChildOfType(MarkdownElementTypes.INLINE_LINK)

                val url = inlineLink?.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getUnescapedTextInNode(content)
                    ?: inlineLink?.findChildOfType(MarkdownElementTypes.AUTOLINK)
                        ?.findChildOfType(MarkdownTokenTypes.AUTOLINK)
                        ?.getUnescapedTextInNode(content)
                    ?: return@markdownAnnotator false

                val textNode = inlineLink?.findChildOfType(MarkdownElementTypes.LINK_TITLE)
                    ?: inlineLink?.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                val altText = textNode?.findChildOfType(MarkdownTokenTypes.TEXT)
                    ?.getUnescapedTextInNode(content).orEmpty()

                withLink(LinkAnnotation.Url(url = url)) {
                    pushStyle(linkStyle)
                    appendInlineContent(MARKDOWN_INLINE_IMAGE_TAG)
                    append(altText)
                    pop()
                }

                return@markdownAnnotator true
            }

            if (child.type in DISALLOWED_MARKDOWN_TYPES) {
                append(content.substring(child.startOffset, child.endOffset))
                return@markdownAnnotator true
            }

            false
        },
        config = markdownAnnotatorConfig(
            eolAsNewLine = true,
        ),
    )
}

@Composable
private fun AnimeTagChip(
    text: String,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SuggestionChip(
            modifier = Modifier.padding(vertical = 4.dp),
            onClick = onClick,
            label = { Text(text = text, style = MaterialTheme.typography.bodySmall) },
        )
    }
}

@Composable
private fun EpisodeHeader(
    episodeCount: Int,
    sourceAvailable: Boolean,
    sourceName: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = pluralStringResource(MR.plurals.anime_num_episodes, episodeCount, episodeCount),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (!sourceAvailable) {
            Text(
                text = stringResource(MR.strings.source_not_installed, sourceName.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun LazyListScope.episodeItems(
    episodes: List<AnimeEpisode>,
    selectedEpisodeIds: Set<Long>,
    selectionMode: Boolean,
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
    sourceAvailable: Boolean,
    onEpisodeClick: (Long) -> Unit,
    onEpisodeSelected: (AnimeEpisode, Boolean, Boolean) -> Unit,
) {
    if (episodes.isEmpty()) {
        item {
            Text(
                text = stringResource(MR.strings.anime_no_episodes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        return
    }

    items(
        items = episodes,
        key = { it.id },
    ) { episode ->
        AnimeEpisodeListItem(
            episode = episode,
            selected = episode.id in selectedEpisodeIds,
            selectionMode = selectionMode,
            playbackState = playbackStateByEpisodeId[episode.id],
            sourceAvailable = sourceAvailable,
            onClick = {
                if (selectionMode) {
                    onEpisodeSelected(episode, episode.id !in selectedEpisodeIds, false)
                } else {
                    onEpisodeClick(episode.id)
                }
            },
            onLongClick = {
                onEpisodeSelected(episode, true, true)
            },
        )
    }
}

@Composable
private fun AnimeEpisodeListItem(
    episode: AnimeEpisode,
    selected: Boolean,
    selectionMode: Boolean,
    playbackState: AnimePlaybackState?,
    sourceAvailable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val progress = playbackState.progressFraction()
    val resumeText = playbackState
        ?.takeIf { !it.completed && it.positionMs > 0L }
        ?.positionMs
        ?.milliseconds
        ?.toDurationString(context, fallback = stringResource(MR.strings.not_applicable))
    val subtitleDate = episode.dateUpload
        .takeIf { it > 0L }
        ?.let { relativeDateText(it) }
    val subtitleStatus = when {
        episode.completed || playbackState?.completed == true -> stringResource(MR.strings.completed)
        resumeText != null -> stringResource(MR.strings.action_resume) + " " + resumeText
        episode.watched -> stringResource(MR.strings.anime_watched)
        else -> null
    }
    val titleAlpha = if (episode.completed || playbackState?.completed == true) DISABLED_ALPHA else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                } else {
                    Color.Transparent
                },
            )
            .clickableNoIndication(
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!episode.watched && !episode.completed && playbackState?.completed != true) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = episode.name.ifBlank { episode.url },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = LocalContentColor.current.copy(alpha = titleAlpha),
                )
            }

            if (subtitleDate != null || subtitleStatus != null) {
                Row {
                    val subtitleColor = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA)
                    val subtitleStyle = MaterialTheme.typography.bodySmall.copy(color = subtitleColor)
                    ProvideTextStyle(value = subtitleStyle) {
                        if (subtitleDate != null) {
                            Text(
                                text = subtitleDate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitleStatus != null) DotSeparatorText()
                        }
                        if (subtitleStatus != null) {
                            Text(
                                text = subtitleStatus,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                            )
                        }
                    }
                }
            }

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
        }

        if (!selectionMode) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playbackState?.positionMs?.let { it > 0L } == true) {
                        MR.strings.action_resume
                    } else {
                        MR.strings.action_start
                    },
                ),
                modifier = Modifier.padding(start = 4.dp),
                tint = if (sourceAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    HorizontalDivider()
}

@Composable
private fun AnimeBottomActionMenu(
    visible: Boolean,
    selectedEpisodes: List<AnimeEpisode>,
    onMarkSelectedWatched: (Boolean) -> Unit,
) {
    MangaBottomActionMenu(
        visible = visible,
        onMarkAsReadClicked = {
            onMarkSelectedWatched(true)
        }.takeIf { selectedEpisodes.any { !it.completed || !it.watched } },
        onMarkAsUnreadClicked = {
            onMarkSelectedWatched(false)
        }.takeIf { selectedEpisodes.any { it.completed || it.watched } },
        markAsReadLabel = MR.strings.action_mark_as_watched,
        markAsUnreadLabel = MR.strings.action_mark_as_unwatched,
    )
}

private fun AnimePlaybackState?.progressFraction(): Float? {
    if (this == null || durationMs <= 0L || positionMs <= 0L || completed) return null
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
