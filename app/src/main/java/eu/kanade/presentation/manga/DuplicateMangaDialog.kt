package eu.kanade.presentation.manga

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Done
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
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.domain.manga.service.DuplicatePreferences
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
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
fun DuplicateMangaDialog(
    duplicates: List<DuplicateMangaCandidate>,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onOpenManga: (manga: Manga) -> Unit,
    onMigrate: (manga: Manga) -> Unit,
    onMerge: ((duplicate: DuplicateMangaCandidate) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sourceManager = remember { Injekt.get<SourceManager>() }
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
                    key = { it.manga.id },
                ) {
                    DuplicateMangaListItem(
                        duplicate = it,
                        getSource = { sourceManager.getOrStub(it.manga.source) },
                        onMigrate = { onMigrate(it.manga) },
                        onMerge = { onMerge?.invoke(it) },
                        onDismissRequest = onDismissRequest,
                        onOpenManga = { onOpenManga(it.manga) },
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
@OptIn(ExperimentalLayoutApi::class)
private fun DuplicateMangaListItem(
    duplicate: DuplicateMangaCandidate,
    getSource: () -> Source,
    onDismissRequest: () -> Unit,
    onOpenManga: () -> Unit,
    onMigrate: () -> Unit,
    onMerge: (() -> Unit)?,
) {
    val source = getSource()
    val manga = duplicate.manga
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
                        .data(manga)
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
                            MR.plurals.manga_num_chapters,
                            duplicate.chapterCount.toInt(),
                            duplicate.chapterCount,
                        ),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Text(
                    text = manga.presentationTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!manga.displayName.isNullOrBlank() && manga.displayName != manga.title) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DuplicateMetaRow(
                    text = if (source is StubSource) {
                        stringResource(MR.strings.source_not_installed, source.name)
                    } else {
                        source.name
                    },
                    iconImageVector = Icons.Outlined.Public,
                    iconTint = if (source is StubSource) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    maxLines = 2,
                )

                DuplicateMetaRow(
                    text = manga.statusLabel(),
                    iconImageVector = manga.statusIcon(),
                )

                if (!manga.author.isNullOrBlank()) {
                    DuplicateMetaRow(
                        text = manga.author!!,
                        iconImageVector = Icons.Filled.PersonOutline,
                        maxLines = 2,
                    )
                }

                if (!manga.artist.isNullOrBlank() && manga.author != manga.artist) {
                    DuplicateMetaRow(
                        text = manga.artist!!,
                        iconImageVector = Icons.Filled.Brush,
                        maxLines = 2,
                    )
                }

                manga.description
                    ?.normalizedPreview()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        DuplicateMetaRow(
                            text = it,
                            iconImageVector = Icons.Outlined.Description,
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
                            onOpenManga()
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

                    Button(
                        onClick = {
                            onDismissRequest()
                            onMigrate()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(MR.strings.action_migrate),
                            modifier = Modifier.padding(start = MaterialTheme.padding.extraSmall),
                        )
                    }
                }
                onMerge?.let {
                    OutlinedButton(
                        onClick = {
                            onDismissRequest()
                            it()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MaterialTheme.padding.small),
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
            modifier = Modifier.size(MangaDetailsIconWidth),
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
private fun Manga.statusLabel(): String {
    return when (status) {
        SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
        SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
        SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
        SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
        SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
        SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
        else -> stringResource(MR.strings.unknown)
    }
}

private fun Manga.statusIcon(): ImageVector {
    return when (status) {
        SManga.ONGOING.toLong() -> Icons.Outlined.Schedule
        SManga.COMPLETED.toLong() -> Icons.Outlined.DoneAll
        SManga.LICENSED.toLong() -> Icons.Outlined.AttachMoney
        SManga.PUBLISHING_FINISHED.toLong() -> Icons.Outlined.Done
        SManga.CANCELLED.toLong() -> Icons.Outlined.Close
        SManga.ON_HIATUS.toLong() -> Icons.Outlined.Pause
        else -> Icons.Outlined.Block
    }
}

@Composable
private fun DuplicateMangaCandidate.scoreBadgeColor(): Color {
    return if (isStrongMatch) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
}

@Composable
private fun DuplicateMangaCandidate.scoreBadgeTextColor(): Color {
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
private val MangaDetailsIconWidth = 16.dp
