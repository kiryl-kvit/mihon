package eu.kanade.tachiyomi.ui.video.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.anime.animeEpisodeItems
import eu.kanade.tachiyomi.ui.anime.AnimeEpisodeListEntry
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.OverlayActionButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun VideoPlayerEpisodesDrawer(
    visible: Boolean,
    anime: AnimeTitle,
    episodeListItems: List<AnimeEpisodeListEntry>,
    currentEpisodeId: Long,
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
    sourceAvailable: Boolean,
    onEpisodeClick: (AnimeEpisode) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentEpisodeIndex = episodeListItems.indexOfFirst { entry ->
        (entry as? AnimeEpisodeListEntry.Item)?.episode?.id == currentEpisodeId
    }

    LaunchedEffect(visible, currentEpisodeId, episodeListItems) {
        if (visible && currentEpisodeIndex >= 0) {
            listState.scrollToItem(currentEpisodeIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(onClick = onDismissRequest),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.94f)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 280.dp, max = 340.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = null,
                                )
                                Text(
                                    text = stringResource(MR.strings.episodes),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            IconButton(onClick = onDismissRequest) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(MR.strings.action_close),
                                )
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                        ) {
                            animeEpisodeItems(
                                anime = anime,
                                episodeListItems = episodeListItems,
                                selectedEpisodeIds = setOf(currentEpisodeId),
                                selectionMode = false,
                                playbackStateByEpisodeId = playbackStateByEpisodeId,
                                sourceAvailable = sourceAvailable,
                                showPlaybackStatus = false,
                                onEpisodeClick = onEpisodeClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoPlayerTimelineToolbar(
    onOpenEpisodes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        OverlayActionButton(
            title = stringResource(MR.strings.episodes),
            icon = Icons.AutoMirrored.Filled.ViewList,
            onClick = onOpenEpisodes,
            contentDescription = stringResource(MR.strings.action_view_episodes),
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.88f),
        )
    }
}
