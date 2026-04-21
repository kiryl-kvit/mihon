package eu.kanade.presentation.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.ui.anime.AnimeEpisodeListEntry
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import kotlin.time.Duration.Companion.milliseconds

fun LazyListScope.animeEpisodeItems(
    anime: AnimeTitle,
    episodeListItems: List<AnimeEpisodeListEntry>,
    selectedEpisodeIds: Set<Long>,
    selectionMode: Boolean,
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
    showPlaybackStatus: Boolean = true,
    onEpisodeClick: (AnimeEpisode) -> Unit,
    onEpisodeSelected: ((AnimeEpisode, Boolean, Boolean) -> Unit)? = null,
) {
    if (episodeListItems.isEmpty()) {
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
        items = episodeListItems,
        key = {
            when (it) {
                is AnimeEpisodeListEntry.Item -> "episode-${it.episode.id}"
                is AnimeEpisodeListEntry.MemberHeader -> "member-${it.animeId}"
            }
        },
    ) { entry ->
        when (entry) {
            is AnimeEpisodeListEntry.MemberHeader -> {
                ListGroupHeader(text = entry.title)
            }
            is AnimeEpisodeListEntry.Item -> {
                val episode = entry.episode
                AnimeEpisodeListItem(
                    anime = anime,
                    episode = episode,
                    selected = episode.id in selectedEpisodeIds,
                    playbackState = playbackStateByEpisodeId[episode.id],
                    showPlaybackStatus = showPlaybackStatus,
                    onClick = {
                        if (selectionMode && onEpisodeSelected != null) {
                            onEpisodeSelected(episode, episode.id !in selectedEpisodeIds, false)
                        } else {
                            onEpisodeClick(episode)
                        }
                    },
                    onLongClick = onEpisodeSelected?.let {
                        {
                            it(episode, true, true)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AnimeEpisodeListItem(
    anime: AnimeTitle,
    episode: AnimeEpisode,
    selected: Boolean,
    playbackState: AnimePlaybackState?,
    showPlaybackStatus: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val progress = playbackState.progressFraction(takeIf = showPlaybackStatus)
    val resumeText = playbackState
        ?.takeIf { showPlaybackStatus && !it.completed && it.positionMs > 0L }
        ?.positionMs
        ?.milliseconds
        ?.toDurationString(context, fallback = stringResource(MR.strings.not_applicable))
    val subtitleDate = episode.dateUpload
        .takeIf { it > 0L }
        ?.let { relativeDateText(it) }
    val subtitleStatus = when {
        !showPlaybackStatus -> null
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
                    text = episode.displayTitle(anime),
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
    }

    HorizontalDivider()
}

@Composable
private fun AnimeEpisode.displayTitle(anime: AnimeTitle): String {
    return if (anime.displayMode == AnimeTitle.EPISODE_DISPLAY_NUMBER) {
        val number = episodeNumber.takeIf { it >= 0.0 }
            ?.let(::formatChapterNumber)
            ?: url
        stringResource(MR.strings.display_mode_chapter, number)
    } else {
        name.ifBlank { url }
    }
}

private fun AnimePlaybackState?.progressFraction(takeIf: Boolean): Float? {
    if (!takeIf) return null
    if (this == null || durationMs <= 0L || positionMs <= 0L || completed) return null
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
