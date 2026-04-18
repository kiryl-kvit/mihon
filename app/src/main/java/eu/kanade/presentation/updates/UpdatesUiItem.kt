package eu.kanade.presentation.updates

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import eu.kanade.presentation.manga.components.MangaCover as MangaCoverComposable
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "updates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun <T> LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel<T>>,
    itemKey: (T) -> String,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is UpdatesUiModel.Header -> "header"
                is UpdatesUiModel.Item -> "item"
            }
        },
        key = {
            when (it) {
                is UpdatesUiModel.Header -> "updatesHeader-${it.date}"
                is UpdatesUiModel.Item -> itemKey(it.item)
            }
        },
    ) { item ->
        when (item) {
            is UpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItemFastScroll(),
                    text = relativeDateText(item.date),
                )
            }

            is UpdatesUiModel.Item -> itemContent(item.item)
        }
    }
}

internal fun LazyListScope.mangaUpdatesUiItems(
    uiModels: List<UpdatesUiModel<UpdatesItem>>,
    selectionMode: Boolean,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    updatesUiItems(
        uiModels = uiModels,
        itemKey = { "updates-${it.visibleMangaId}-${it.update.chapterId}" },
    ) { updatesItem ->
        ChapterUpdatesUiItem(
            modifier = Modifier.animateItemFastScroll(),
            title = updatesItem.visibleMangaTitle,
            subtitle = updatesItem.update.chapterName,
            coverData = updatesItem.visibleCoverData,
            selected = updatesItem.selected,
            read = updatesItem.update.read,
            bookmark = updatesItem.update.bookmark,
            readProgress = updatesItem.update.lastPageRead
                .takeIf { !updatesItem.update.read && it > 0L }
                ?.let {
                    stringResource(
                        MR.strings.chapter_progress,
                        it + 1,
                    )
                },
            onLongClick = {
                onUpdateSelected(updatesItem, !updatesItem.selected, true)
            },
            onClick = {
                when {
                    selectionMode -> onUpdateSelected(updatesItem, !updatesItem.selected, false)
                    else -> onClickUpdate(updatesItem)
                }
            },
            onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
            onDownloadChapter = { action: ChapterDownloadAction ->
                onDownloadChapter(listOf(updatesItem), action)
            }.takeIf { !selectionMode },
            downloadStateProvider = updatesItem.downloadStateProvider,
            downloadProgressProvider = updatesItem.downloadProgressProvider,
        )
    }
}

@Composable
fun UpdatesBaseUiItem(
    title: String,
    coverData: MangaCoverData,
    selected: Boolean,
    read: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickCover: (() -> Unit)? = null,
    subtitle: @Composable RowScope.(Float) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (read) DISABLED_ALPHA else 1f

    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCoverComposable.Square(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = coverData,
            onClick = onClickCover,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = title,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                subtitle(textAlpha)
            }
        }

        trailing?.invoke()
    }
}

@Composable
internal fun ChapterUpdatesUiItem(
    title: String,
    subtitle: String,
    coverData: MangaCoverData,
    selected: Boolean,
    read: Boolean,
    bookmark: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)? = null,
    downloadStateProvider: (() -> Download.State)? = null,
    downloadProgressProvider: (() -> Int)? = null,
    modifier: Modifier = Modifier,
) {
    UpdatesBaseUiItem(
        title = title,
        coverData = coverData,
        selected = selected,
        read = read,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        onClickCover = onClickCover,
        subtitle = { textAlpha ->
            var textHeight by remember { mutableIntStateOf(0) }
            if (!read) {
                Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = stringResource(MR.strings.unread),
                    modifier = Modifier
                        .height(8.dp)
                        .padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (bookmark) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                    modifier = Modifier
                        .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
            Text(
                text = subtitle,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textHeight = it.size.height },
                modifier = Modifier.weight(weight = 1f, fill = false),
            )
            if (readProgress != null) {
                DotSeparatorText()
                Text(
                    text = readProgress,
                    maxLines = 1,
                    color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailing = if (downloadStateProvider != null && downloadProgressProvider != null) {
            {
                ChapterDownloadIndicator(
                    enabled = onDownloadChapter != null,
                    modifier = Modifier.padding(start = 4.dp),
                    downloadStateProvider = downloadStateProvider,
                    downloadProgressProvider = downloadProgressProvider,
                    onClick = { onDownloadChapter?.invoke(it) },
                )
            }
        } else {
            null
        },
    )
}
