package eu.kanade.tachiyomi.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifVideoSourcesLoaded
import eu.kanade.domain.video.model.toMangaCover
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.toDurationString
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerActivity
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.milliseconds

data class VideoScreen(
    private val videoId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifVideoSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { VideoScreenModel(context.applicationContext, videoId) }
        val state by screenModel.state.collectAsState()

        when (val current = state) {
            VideoScreenModel.State.Loading -> LoadingScreen()
            is VideoScreenModel.State.Error -> {
                Scaffold(
                    topBar = {
                        AppBar(
                            title = stringResource(MR.strings.browse),
                            navigateUp = navigator::pop,
                            scrollBehavior = it,
                        )
                    },
                ) { contentPadding ->
                    EmptyScreen(
                        message = current.message,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
            }
            is VideoScreenModel.State.Success -> {
                Scaffold(
                    topBar = {
                        AppBar(
                            title = current.video.displayTitle,
                            subtitle = current.sourceName
                                ?: stringResource(MR.strings.source_not_installed, current.video.source.toString()),
                            navigateUp = navigator::pop,
                            actions = {
                                AppBarActions(
                                    actions = persistentListOf<AppBar.AppBarAction>().builder()
                                        .apply {
                                            add(
                                                AppBar.Action(
                                                    title = if (current.video.favorite) {
                                                        stringResource(MR.strings.in_library)
                                                    } else {
                                                        stringResource(MR.strings.add_to_library)
                                                    },
                                                    icon = if (current.video.favorite) {
                                                        Icons.Filled.Favorite
                                                    } else {
                                                        Icons.Outlined.FavoriteBorder
                                                    },
                                                    onClick = screenModel::toggleFavorite,
                                                ),
                                            )
                                            add(
                                                AppBar.Action(
                                                    title = stringResource(MR.strings.action_retry),
                                                    icon = Icons.Outlined.Refresh,
                                                    onClick = screenModel::refresh,
                                                    enabled = current.sourceAvailable && !current.isRefreshing,
                                                ),
                                            )
                                            if (current.video.favorite) {
                                                add(
                                                    AppBar.OverflowAction(
                                                        title = stringResource(MR.strings.action_edit_categories),
                                                        onClick = screenModel::showChangeCategoryDialog,
                                                    ),
                                                )
                                            }
                                        }
                                        .build(),
                                )
                            },
                            scrollBehavior = it,
                        )
                    },
                    snackbarHost = { SnackbarHost(screenModel.snackbarHostState) },
                ) { contentPadding ->
                    VideoScreenContent(
                        state = current,
                        contentPadding = contentPadding,
                        onEpisodeClick = { episodeId ->
                            context.startVideoEpisode(current.video.id, episodeId)
                        },
                    )
                }

                when (val dialog = current.dialog) {
                    is VideoScreenModel.Dialog.ChangeCategory -> {
                        ChangeCategoryDialog(
                            initialSelection = dialog.initialSelection,
                            onDismissRequest = screenModel::dismissDialog,
                            onEditCategories = { navigator.push(CategoryScreen()) },
                            onConfirm = { include, _ -> screenModel.setCategories(include) },
                        )
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun VideoScreenContent(
    state: VideoScreenModel.State.Success,
    contentPadding: PaddingValues,
    onEpisodeClick: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MangaCover.Book(
                    modifier = Modifier.width(120.dp),
                    data = state.video.toMangaCover(),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.video.displayTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.sourceName
                            ?: stringResource(MR.strings.source_not_installed, state.video.source.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.sourceAvailable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    state.video.genre
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(" • ")
                        ?.let { genres ->
                            Text(
                                text = genres,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
            }
        }

        state.primaryEpisode?.let { episode ->
            item {
                FilledTonalButton(
                    onClick = { onEpisodeClick(episode.id) },
                    enabled = state.sourceAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (state.playbackStateByEpisodeId[episode.id]?.positionMs?.let { it > 0L } == true) {
                                MR.strings.action_resume
                            } else {
                                MR.strings.action_start
                            },
                        ),
                    )
                }
            }
        }

        if (state.categories.isNotEmpty()) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.categories.forEach { category ->
                        AssistChip(
                            onClick = {},
                            label = { Text(category.visualName) },
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = state.video.description.takeUnless { it.isNullOrBlank() }
                    ?: stringResource(MR.strings.description_placeholder),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.episodes.isEmpty() && !state.isRefreshing) {
            item {
                Text(
                    text = stringResource(MR.strings.no_results_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.episodes, key = { it.id }) { episode ->
            val context = LocalContext.current
            val playbackState = state.playbackStateByEpisodeId[episode.id]
            val progress = playbackState.progressFraction()
            val resumeText = playbackState
                ?.takeIf { !it.completed && it.positionMs > 0L }
                ?.positionMs
                ?.milliseconds
                ?.toDurationString(
                    context = context,
                    fallback = stringResource(MR.strings.not_applicable),
                )
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = episode.name.ifBlank { episode.url },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = buildList {
                                    if (episode.completed || playbackState?.completed == true) {
                                        add(stringResource(MR.strings.completed))
                                    } else if (resumeText != null) {
                                        add(stringResource(MR.strings.action_resume) + " " + resumeText)
                                    } else if (episode.watched) {
                                        add(stringResource(MR.strings.label_read_chapters))
                                    }
                                    if (episode.dateUpload > 0L) {
                                        add(java.text.DateFormat.getDateInstance().format(java.util.Date(episode.dateUpload)))
                                    }
                                }.ifEmpty { listOf(stringResource(MR.strings.not_applicable)) }.joinToString(" • "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(
                                    if (playbackState?.positionMs?.let { it > 0L } == true) {
                                        MR.strings.action_resume
                                    } else {
                                        MR.strings.action_start
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.clickable(enabled = state.sourceAvailable) {
                            onEpisodeClick(episode.id)
                        },
                    )
                    if (progress != null) {
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
    }
}

private fun android.content.Context.startVideoEpisode(videoId: Long, episodeId: Long) {
    startActivity(
        VideoPlayerActivity.newIntent(
            context = this,
            videoId = videoId,
            episodeId = episodeId,
        ),
    )
}

private fun tachiyomi.domain.video.model.VideoPlaybackState?.progressFraction(): Float? {
    if (this == null || durationMs <= 0L || positionMs <= 0L || completed) return null
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
