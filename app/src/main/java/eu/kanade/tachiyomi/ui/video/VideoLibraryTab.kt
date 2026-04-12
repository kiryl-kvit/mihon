package eu.kanade.tachiyomi.ui.video

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.util.ifVideoSourcesLoaded
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerActivity
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import androidx.compose.material3.contentColorFor

data object VideoLibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        if (!ifVideoSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { VideoLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_library),
                    scrollBehavior = it,
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            when (val current = state) {
                VideoLibraryScreenModel.State.Loading -> LoadingScreen(Modifier.padding(contentPadding))
                is VideoLibraryScreenModel.State.Error -> EmptyScreen(
                    message = current.message,
                    modifier = Modifier.padding(contentPadding),
                )
                is VideoLibraryScreenModel.State.Success -> {
                    if (current.videos.isEmpty()) {
                        EmptyScreen(
                            stringRes = MR.strings.information_empty_library,
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        VideoLibraryContent(
                            state = current,
                            contentPadding = contentPadding,
                            onOpenVideo = { navigator.push(VideoScreen(it)) },
                            onOpenEpisode = { videoId, episodeId ->
                                context.startActivity(
                                    VideoPlayerActivity.newIntent(
                                        context = context,
                                        videoId = videoId,
                                        episodeId = episodeId,
                                    ),
                                )
                            },
                            onContinueWatching = { videoId, episodeId ->
                                context.startActivity(
                                    VideoPlayerActivity.newIntent(
                                        context = context,
                                        videoId = videoId,
                                        episodeId = episodeId,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        LaunchedEffect(state) {
            if (state !is VideoLibraryScreenModel.State.Loading) {
                (context as? MainActivity)?.ready = true
            }
        }
    }
}

@Composable
private fun VideoLibraryContent(
    state: VideoLibraryScreenModel.State.Success,
    contentPadding: PaddingValues,
    onOpenVideo: (Long) -> Unit,
    onOpenEpisode: (Long, Long) -> Unit,
    onContinueWatching: (Long, Long) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.continueWatching?.let { continueWatching ->
            item {
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_resume),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            MangaCover.Book(
                                modifier = Modifier.width(96.dp),
                                data = continueWatching.coverData,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = continueWatching.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = continueWatching.episodeName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = relativeTimeSpanString(continueWatching.watchedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(
                                    onClick = {
                                        onContinueWatching(
                                            continueWatching.videoId,
                                            continueWatching.episodeId,
                                        )
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stringResource(MR.strings.action_resume))
                                }
                            }
                        }
                    }
                }
            }
        }

        items(state.videos, key = { it.videoId }) { item ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenVideo(item.videoId) },
                ) {
                    val statusText = buildList {
                        if (item.hasInProgress) {
                            add(stringResource(MR.strings.action_resume))
                        }
                        if (item.unwatchedCount > 0) {
                            add(stringResource(MR.strings.unread) + " " + item.unwatchedCount)
                        } else {
                            add(stringResource(MR.strings.completed))
                        }
                    }.joinToString(" • ")

                    ListItem(
                        leadingContent = {
                            MangaCover.Book(
                                modifier = Modifier.size(width = 56.dp, height = 80.dp),
                                data = item.coverData,
                            )
                        },
                        headlineContent = {
                            Text(
                                text = item.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = item.sourceName
                                        ?: stringResource(MR.strings.source_not_installed, item.sourceId.toString()),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (item.hasInProgress) {
                                        Icon(
                                            imageVector = Icons.Filled.Circle,
                                            contentDescription = null,
                                            modifier = Modifier.size(8.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            if (item.primaryEpisodeId != null) {
                                FilledIconButton(
                                    onClick = { onOpenEpisode(item.videoId, item.primaryEpisodeId) },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                        contentColor = contentColorFor(MaterialTheme.colorScheme.primaryContainer),
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(
                                            if (item.hasInProgress) MR.strings.action_resume else MR.strings.action_start,
                                        ),
                                    )
                                }
                            } else if (item.unwatchedCount > 0) {
                                Badge {
                                    Text(item.unwatchedCount.toString())
                                }
                            }
                        },
                    )
                    item.progressFraction?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(4.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    HorizontalDivider()
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
