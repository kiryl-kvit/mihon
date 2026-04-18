package eu.kanade.presentation.anime.updates

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.updates.ChapterUpdatesUiItem
import eu.kanade.presentation.updates.UpdatesBottomBarConfig
import eu.kanade.presentation.updates.UpdatesScreen
import eu.kanade.presentation.updates.UpdatesScreenState
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.presentation.updates.updatesLastUpdatedItem
import eu.kanade.presentation.updates.updatesUiItems
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.anime.updates.AnimeUpdatesItem
import tachiyomi.i18n.MR

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
            ChapterUpdatesUiItem(
                modifier = Modifier.animateItemFastScroll(),
                title = item.visibleAnimeTitle,
                subtitle = item.update.episodeName,
                coverData = item.visibleCoverData,
                selected = item.selected,
                read = item.update.completed,
                bookmark = false,
                readProgress = null,
                onClick = {
                    when {
                        state.selectionMode -> onUpdateSelected(item, !item.selected, false)
                        else -> onOpenEpisode(item)
                    }
                },
                onLongClick = {
                    onUpdateSelected(item, !item.selected, true)
                },
                onClickCover = { onClickCover(item) }.takeIf { !state.selectionMode },
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
