package eu.kanade.presentation.anime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.source.model.SAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.DuplicateAnimeCandidate
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason
import tachiyomi.domain.manga.service.DuplicatePreferences
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun DuplicateAnimeDialog(
    duplicates: List<DuplicateAnimeCandidate>,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onOpenAnime: (anime: AnimeTitle) -> Unit,
    onMerge: ((duplicate: DuplicateAnimeCandidate) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sourceManager = remember { Injekt.get<AnimeSourceManager>() }
    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    AdaptiveSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            Text(
                text = stringResource(MR.strings.possible_duplicates_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .then(horizontalPaddingModifier)
                    .padding(top = MaterialTheme.padding.small),
            )

            Text(
                text = stringResource(MR.strings.possible_duplicates_summary),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.then(horizontalPaddingModifier),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 520.dp),
                contentPadding = horizontalPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(
                    items = duplicates,
                    key = { it.anime.id },
                ) { duplicate ->
                    val sourceName = sourceManager.get(duplicate.anime.source)?.name
                        ?: stringResource(MR.strings.source_not_installed, duplicate.anime.source.toString())
                    DuplicateAnimeListItem(
                        duplicate = duplicate,
                        sourceName = sourceName,
                        isStubSource = sourceManager.get(duplicate.anime.source) == null,
                        onMerge = { onMerge?.invoke(duplicate) },
                        onDismissRequest = onDismissRequest,
                        onOpenAnime = { onOpenAnime(duplicate.anime) },
                    )
                }
            }

            Column(modifier = horizontalPaddingModifier) {
                HorizontalDivider()

                TextPreferenceWidget(
                    title = stringResource(MR.strings.action_add_anyway),
                    icon = Icons.Outlined.Add,
                    onPreferenceClick = {
                        onDismissRequest()
                        onConfirm()
                    },
                    modifier = Modifier.padding(top = MaterialTheme.padding.small).clip(CircleShape),
                )
            }

            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .then(horizontalPaddingModifier)
                    .padding(bottom = MaterialTheme.padding.medium)
                    .heightIn(min = minHeight)
                    .fillMaxWidth(),
            ) {
                Text(
                    modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall),
                    text = stringResource(MR.strings.action_cancel),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun DuplicateAnimeListItem(
    duplicate: DuplicateAnimeCandidate,
    sourceName: String,
    isStubSource: Boolean,
    onDismissRequest: () -> Unit,
    onOpenAnime: () -> Unit,
    onMerge: (() -> Unit)?,
) {
    val anime = duplicate.anime
    val duplicatePreferences = remember { Injekt.get<DuplicatePreferences>() }
    val extendedEnabled by duplicatePreferences.extendedDuplicateDetectionEnabled.collectAsState()

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            width = 1.dp,
            color = if (duplicate.isStrongMatch) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.width(DuplicateCoverWidth)) {
                MangaCover.Book(
                    data = ImageRequest.Builder(LocalContext.current)
                        .data(anime.toMangaCover())
                        .crossfade(true)
                        .build(),
                    modifier = Modifier.fillMaxWidth(),
                )
                BadgeGroup(
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart),
                ) {
                    Badge(
                        color = MaterialTheme.colorScheme.secondary,
                        textColor = MaterialTheme.colorScheme.onSecondary,
                        text = pluralStringResource(
                            MR.plurals.anime_num_episodes,
                            duplicate.episodeCount.toInt(),
                            duplicate.episodeCount,
                        ),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Text(
                    text = anime.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!anime.displayName.isNullOrBlank() && anime.displayName != anime.title) {
                    Text(
                        text = anime.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DuplicateMetaRow(
                    text = sourceName,
                    iconImageVector = androidx.compose.material.icons.Icons.Outlined.Public,
                    iconTint = if (isStubSource) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    maxLines = 2,
                )

                anime.statusLabel()?.let {
                    DuplicateMetaRow(
                        text = it,
                        iconImageVector = anime.statusIcon(),
                    )
                }

                anime.director?.takeIf { it.isNotBlank() }?.let {
                    DuplicateMetaRow(
                        text = it,
                        iconImageVector = androidx.compose.material.icons.Icons.Filled.PersonOutline,
                        maxLines = 2,
                    )
                }

                anime.studio?.takeIf { it.isNotBlank() && it != anime.director }?.let {
                    DuplicateMetaRow(
                        text = it,
                        iconImageVector = androidx.compose.material.icons.Icons.Filled.Brush,
                        maxLines = 2,
                    )
                }

                anime.description
                    ?.normalizedPreview()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        DuplicateMetaRow(
                            text = it,
                            iconImageVector = androidx.compose.material.icons.Icons.Outlined.Description,
                            maxLines = 3,
                        )
                    }

                if (extendedEnabled) {
                    FlowRow(
                        modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        Badge(
                            text = stringResource(MR.strings.possible_duplicates_score, duplicate.scorePercent),
                            color = duplicate.scoreBadgeColor(),
                            textColor = duplicate.scoreBadgeTextColor(),
                        )
                        duplicate.reasons.forEach { reason ->
                            Badge(
                                text = reason.label(),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.extraSmall),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    FilledTonalButton(
                        onClick = {
                            onDismissRequest()
                            onOpenAnime()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(MR.strings.action_open),
                            modifier = Modifier.padding(start = MaterialTheme.padding.extraSmall),
                        )
                    }

                    onMerge?.let {
                        Button(
                            onClick = {
                                it()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.CallSplit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(MR.strings.action_merge_with_this),
                                modifier = Modifier.padding(start = MaterialTheme.padding.extraSmall),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateMetaRow(
    text: String,
    iconImageVector: ImageVector,
    maxLines: Int = 1,
    iconTint: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier
            .secondaryItemAlpha()
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconImageVector,
            contentDescription = null,
            modifier = Modifier.size(MetaIconWidth),
            tint = if (iconTint == Color.Unspecified) LocalContentColor.current else iconTint,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
        )
    }
}

@Composable
private fun DuplicateMangaMatchReason.label(): String {
    return when (this) {
        DuplicateMangaMatchReason.DESCRIPTION -> stringResource(MR.strings.possible_duplicates_reason_description)
        DuplicateMangaMatchReason.TITLE -> stringResource(MR.strings.possible_duplicates_reason_title)
        DuplicateMangaMatchReason.TRACKER -> stringResource(MR.strings.possible_duplicates_reason_tracker)
        DuplicateMangaMatchReason.AUTHOR -> stringResource(MR.strings.possible_duplicates_reason_author)
        DuplicateMangaMatchReason.ARTIST -> stringResource(MR.strings.possible_duplicates_reason_artist)
        DuplicateMangaMatchReason.COVER -> stringResource(MR.strings.possible_duplicates_reason_cover)
        DuplicateMangaMatchReason.STATUS -> stringResource(MR.strings.possible_duplicates_reason_status)
        DuplicateMangaMatchReason.GENRE -> stringResource(MR.strings.possible_duplicates_reason_genre)
        DuplicateMangaMatchReason.CHAPTER_COUNT -> stringResource(MR.strings.possible_duplicates_reason_chapter_count)
    }
}

@Composable
private fun AnimeTitle.statusLabel(): String? {
    return when (status) {
        SAnime.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
        SAnime.COMPLETED.toLong() -> stringResource(MR.strings.completed)
        SAnime.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
        SAnime.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
        SAnime.UNKNOWN.toLong() -> null
        else -> stringResource(MR.strings.unknown)
    }
}

private fun AnimeTitle.statusIcon(): ImageVector {
    return when (status) {
        SAnime.ONGOING.toLong() -> Icons.Outlined.Schedule
        SAnime.COMPLETED.toLong() -> Icons.Outlined.DoneAll
        SAnime.CANCELLED.toLong() -> Icons.Outlined.Close
        SAnime.ON_HIATUS.toLong() -> Icons.Outlined.Pause
        else -> Icons.Outlined.Block
    }
}

@Composable
private fun DuplicateAnimeCandidate.scoreBadgeColor(): Color {
    return if (isStrongMatch) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
}

@Composable
private fun DuplicateAnimeCandidate.scoreBadgeTextColor(): Color {
    return if (isStrongMatch) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onTertiary
    }
}

private fun String.normalizedPreview(): String {
    return replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val DuplicateCoverWidth = 96.dp
private val MetaIconWidth = 16.dp
