package eu.kanade.presentation.anime.updates

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.updates.UpdatesBaseUiItem
import eu.kanade.presentation.updates.UpdatesBottomBarConfig
import eu.kanade.presentation.updates.UpdatesScreen
import eu.kanade.presentation.updates.UpdatesScreenState
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.presentation.updates.updatesLastUpdatedItem
import eu.kanade.presentation.updates.updatesUiItems
import eu.kanade.tachiyomi.ui.anime.updates.AnimeUpdatesItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AnimeUpdatesScreen(
    state: UpdatesScreenState<AnimeUpdatesItem>,
    uiModels: List<UpdatesUiModel<AnimeUpdatesItem>>,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    lastUpdated: Long,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onUpdateLibrary: () -> Boolean,
    onFilterClicked: () -> Unit,
    hasActiveFilters: Boolean,
    onClickCover: (AnimeUpdatesItem) -> Unit,
    onOpenEpisode: (AnimeUpdatesItem) -> Unit,
    onUpdateSelected: (AnimeUpdatesItem, Boolean, Boolean) -> Unit,
) {
    UpdatesScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onSelectAll = onSelectAll,
        onInvertSelection = onInvertSelection,
        onUpdateLibrary = onUpdateLibrary,
        onCalendarClicked = null,
        onFilterClicked = onFilterClicked,
        hasActiveFilters = hasActiveFilters,
    ) {
        updatesLastUpdatedItem(lastUpdated)
        updatesUiItems(
            uiModels = uiModels,
            itemKey = { "anime-updates-${it.update.animeId}-${it.update.episodeId}" },
        ) { item ->
            AnimeUpdatesUiItem(
                item = item,
                selectionMode = state.selectionMode,
                onUpdateSelected = onUpdateSelected,
                onClickCover = onClickCover,
                onOpenEpisode = onOpenEpisode,
            )
        }
    }
}

fun animeUpdatesBottomBarConfig(
    selected: List<AnimeUpdatesItem>,
    onMarkWatched: (List<AnimeUpdatesItem>, Boolean) -> Unit,
): UpdatesBottomBarConfig {
    return UpdatesBottomBarConfig(
        visible = selected.isNotEmpty(),
        markAsReadLabel = MR.strings.action_mark_as_watched,
        markAsUnreadLabel = MR.strings.action_mark_as_unwatched,
        onMarkAsReadClicked = {
            onMarkWatched(selected, true)
        }.takeIf { selected.any { !it.update.completed || !it.update.watched } },
        onMarkAsUnreadClicked = {
            onMarkWatched(selected, false)
        }.takeIf { selected.any { it.update.completed || it.update.watched } },
    )
}

@Composable
private fun AnimeUpdatesUiItem(
    item: AnimeUpdatesItem,
    selectionMode: Boolean,
    onUpdateSelected: (AnimeUpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (AnimeUpdatesItem) -> Unit,
    onOpenEpisode: (AnimeUpdatesItem) -> Unit,
) {
    UpdatesBaseUiItem(
        title = item.update.animeTitle,
        coverData = item.update.coverData,
        selected = item.selected,
        read = item.update.completed,
        onClick = {
            when {
                selectionMode -> onUpdateSelected(item, !item.selected, false)
                else -> onOpenEpisode(item)
            }
        },
        onLongClick = {
            onUpdateSelected(item, !item.selected, true)
        },
        onClickCover = { onClickCover(item) }.takeIf { !selectionMode },
        subtitle = { textAlpha ->
            if (!item.update.watched) {
                Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = stringResource(MR.strings.action_filter_unwatched),
                    modifier = Modifier.padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = when {
                    item.update.completed -> stringResource(MR.strings.completed)
                    item.update.watched -> stringResource(MR.strings.anime_watched)
                    else -> item.update.episodeName
                },
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight = 1f, fill = false),
            )
            if (!item.update.completed && item.update.watched) {
                DotSeparatorText()
                Text(
                    text = item.update.episodeName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
