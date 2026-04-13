package eu.kanade.presentation.video

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.domain.video.model.toMangaCover
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaNotesDisplay
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MarkdownRender
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.video.VideoScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoPlaybackState
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.VerticalFastScroller
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.util.shouldExpandFAB
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun VideoScreen(
    state: VideoScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEpisodeClick: (Long) -> Unit,
) {
    if (isTabletUi()) {
        VideoScreenLargeImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = navigateUp,
            onRefresh = onRefresh,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEpisodeClick = onEpisodeClick,
        )
    } else {
        VideoScreenSmallImpl(
            state = state,
            snackbarHostState = snackbarHostState,
            navigateUp = navigateUp,
            onRefresh = onRefresh,
            onAddToLibraryClicked = onAddToLibraryClicked,
            onEditCategoryClicked = onEditCategoryClicked,
            onEpisodeClick = onEpisodeClick,
        )
    }
}

@Composable
private fun VideoScreenSmallImpl(
    state: VideoScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEpisodeClick: (Long) -> Unit,
) {
    val episodeListState = rememberLazyListState()

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
                label = "video-title-alpha",
            )
            val backgroundAlpha by animateFloatAsState(
                if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                label = "video-background-alpha",
            )
            VideoToolbar(
                title = state.video.displayTitle,
                navigateUp = navigateUp,
                onRefresh = onRefresh,
                onEditCategoryClicked = onEditCategoryClicked,
                titleAlphaProvider = { titleAlpha },
                backgroundAlphaProvider = { backgroundAlpha },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val primaryEpisode = state.primaryEpisode
            if (primaryEpisode != null) {
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
            enabled = state.sourceAvailable,
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
                        VideoInfoBox(
                            isTabletUi = false,
                            appBarPadding = topPadding,
                            video = state.video,
                            sourceName = state.sourceName,
                            sourceAvailable = state.sourceAvailable,
                        )
                    }
                    item {
                        VideoActionRow(
                            favorite = state.video.favorite,
                            nextUpdateMillis = state.video.nextUpdate,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onRefresh = onRefresh,
                            onEditCategoryClicked = onEditCategoryClicked,
                        )
                    }
                    item {
                        ExpandableVideoDescription(
                            description = state.video.description,
                            notes = state.video.notes,
                            tags = state.video.genre.orEmpty(),
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
                        playbackStateByEpisodeId = state.playbackStateByEpisodeId,
                        sourceAvailable = state.sourceAvailable,
                        onEpisodeClick = onEpisodeClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoScreenLargeImpl(
    state: VideoScreenModel.State.Success,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibraryClicked: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    onEpisodeClick: (Long) -> Unit,
) {
    val episodeListState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val insetPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()

    Scaffold(
        topBar = {
            VideoToolbar(
                title = state.video.displayTitle,
                navigateUp = navigateUp,
                onRefresh = onRefresh,
                onEditCategoryClicked = onEditCategoryClicked,
                titleAlphaProvider = { 1f },
                backgroundAlphaProvider = { 1f },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val primaryEpisode = state.primaryEpisode
            if (primaryEpisode != null) {
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
            enabled = state.sourceAvailable,
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
                        VideoInfoBox(
                            isTabletUi = true,
                            appBarPadding = contentPadding.calculateTopPadding(),
                            video = state.video,
                            sourceName = state.sourceName,
                            sourceAvailable = state.sourceAvailable,
                        )
                        VideoActionRow(
                            favorite = state.video.favorite,
                            nextUpdateMillis = state.video.nextUpdate,
                            onAddToLibraryClicked = onAddToLibraryClicked,
                            onRefresh = onRefresh,
                            onEditCategoryClicked = onEditCategoryClicked,
                        )
                        ExpandableVideoDescription(
                            description = state.video.description,
                            notes = state.video.notes,
                            tags = state.video.genre.orEmpty(),
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
                                playbackStateByEpisodeId = state.playbackStateByEpisodeId,
                                sourceAvailable = state.sourceAvailable,
                                onEpisodeClick = onEpisodeClick,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun VideoToolbar(
    title: String,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
    titleAlphaProvider: () -> Float,
    backgroundAlphaProvider: () -> Float,
) {
    AppBar(
        titleContent = {
            AppBarTitle(
                title = title,
                modifier = Modifier.alpha(titleAlphaProvider()),
            )
        },
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlphaProvider()),
        navigateUp = navigateUp,
        actions = {
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
                    }
                    .build(),
            )
        },
    )
}

@Composable
private fun VideoInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    video: VideoTitle,
    sourceName: String?,
    sourceAvailable: Boolean,
) {
    Box {
        val backdropGradientColors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
        AsyncImage(
            model = video.toMangaCover(),
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
                    data = video.toMangaCover(),
                    contentDescription = video.displayTitle,
                )
                Spacer(modifier = Modifier.height(16.dp))
                VideoContentInfo(
                    video = video,
                    sourceName = sourceName,
                    sourceAvailable = sourceAvailable,
                    textAlign = TextAlign.Center,
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
                    data = video.toMangaCover(),
                    contentDescription = video.displayTitle,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoContentInfo(
                        video = video,
                        sourceName = sourceName,
                        sourceAvailable = sourceAvailable,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoContentInfo(
    video: VideoTitle,
    sourceName: String?,
    sourceAvailable: Boolean,
    textAlign: TextAlign,
) {
    val context = LocalContext.current
    val title = video.displayTitle.ifBlank { stringResource(MR.strings.unknown_title) }
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clickableNoIndication(
            onLongClick = { if (title.isNotBlank()) context.copyToClipboard(title, title) },
            onClick = {},
        ),
        textAlign = textAlign,
    )

    Text(
        text = sourceName ?: stringResource(MR.strings.source_not_installed, video.source.toString()),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        color = if (sourceAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )

    video.genre
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(" • ")
        ?.let { genres ->
            Text(
                text = genres,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.secondaryItemAlpha(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
            )
        }
}

@Composable
private fun VideoActionRow(
    favorite: Boolean,
    nextUpdateMillis: Long,
    onAddToLibraryClicked: () -> Unit,
    onRefresh: () -> Unit,
    onEditCategoryClicked: (() -> Unit)?,
) {
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Row(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        VideoActionButton(
            title = if (favorite) stringResource(MR.strings.in_library) else stringResource(MR.strings.add_to_library),
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategoryClicked,
        )
        VideoActionButton(
            title = nextUpdateMillis.takeIf { it > 0L }?.let { relativeDateText(it) }
                ?: stringResource(MR.strings.not_applicable),
            icon = Icons.Filled.HourglassEmpty,
            color = if (nextUpdateMillis > 0L) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = {},
        )
        VideoActionButton(
            title = stringResource(MR.strings.action_retry),
            icon = Icons.Outlined.Refresh,
            color = MaterialTheme.colorScheme.primary,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun RowScope.VideoActionButton(
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
private fun ExpandableVideoDescription(
    description: String?,
    notes: String,
    tags: List<String>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val resolvedDescription = description.takeUnless { it.isNullOrBlank() }
        ?: stringResource(MR.strings.video_no_description)

    Column {
        VideoSummary(
            description = resolvedDescription,
            notes = notes,
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
                    .padding(vertical = 12.dp),
            ) {
                if (expanded) {
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        tags.forEach { tag ->
                            VideoTagChip(tag)
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        items(tags) { tag ->
                            VideoTagChip(tag)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSummary(
    description: String,
    notes: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val animProgress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        label = "video-summary",
    )
    var infoHeight by remember { mutableIntStateOf(0) }

    Layout(
        modifier = modifier.clipToBounds(),
        contents = listOf(
            {
                Text(
                    // Shows at least 3 lines if no notes; 6 when notes are present.
                    text = if (notes.isBlank()) "\n\n" else "\n\n\n\n\n",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            {
                Column(
                    modifier = Modifier.onSizeChanged { size ->
                        infoHeight = size.height
                    },
                ) {
                    if (notes.isNotBlank()) {
                        MangaNotesDisplay(
                            content = notes,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                top = if (expanded) 0.dp else 12.dp,
                                bottom = if (expanded) 16.dp else 12.dp,
                            ),
                        )
                    }

                    SelectionContainer {
                        MarkdownRender(
                            content = description,
                            modifier = Modifier.secondaryItemAlpha(),
                            loadImages = false,
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
private fun VideoTagChip(text: String) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        SuggestionChip(
            modifier = Modifier.padding(vertical = 4.dp),
            onClick = {},
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
            text = pluralStringResource(MR.plurals.video_num_episodes, episodeCount, episodeCount),
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
    episodes: List<VideoEpisode>,
    playbackStateByEpisodeId: Map<Long, VideoPlaybackState>,
    sourceAvailable: Boolean,
    onEpisodeClick: (Long) -> Unit,
) {
    if (episodes.isEmpty()) {
        item {
            Text(
                text = stringResource(MR.strings.video_no_episodes),
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
        VideoEpisodeListItem(
            episode = episode,
            playbackState = playbackStateByEpisodeId[episode.id],
            sourceAvailable = sourceAvailable,
            onClick = { onEpisodeClick(episode.id) },
        )
    }
}

@Composable
private fun VideoEpisodeListItem(
    episode: VideoEpisode,
    playbackState: VideoPlaybackState?,
    sourceAvailable: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val progress = playbackState.progressFraction()
    val resumeText = playbackState
        ?.takeIf { !it.completed && it.positionMs > 0L }
        ?.positionMs
        ?.milliseconds
        ?.toDurationString(context, fallback = stringResource(MR.strings.not_applicable))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = sourceAvailable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.name.ifBlank { episode.url },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = buildList {
                        if (episode.completed || playbackState?.completed == true) {
                            add(stringResource(MR.strings.completed))
                        } else if (resumeText != null) {
                            add(stringResource(MR.strings.action_resume) + " " + resumeText)
                        } else if (episode.watched) {
                            add(stringResource(MR.strings.video_watched))
                        }
                        if (episode.dateUpload > 0L) {
                            add(relativeDateText(episode.dateUpload))
                        }
                    }.ifEmpty { listOf(stringResource(MR.strings.not_applicable)) }.joinToString(" • "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playbackState?.positionMs?.let { it > 0L } == true) {
                        MR.strings.action_resume
                    } else {
                        MR.strings.action_start
                    },
                ),
                tint = if (sourceAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }

        HorizontalDivider()
    }
}

private fun VideoPlaybackState?.progressFraction(): Float? {
    if (this == null || durationMs <= 0L || positionMs <= 0L || completed) return null
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
