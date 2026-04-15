package eu.kanade.presentation.anime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun EpisodeSettingsDialog(
    onDismissRequest: () -> Unit,
    anime: AnimeTitle,
    onUnwatchedFilterChanged: (TriState) -> Unit,
    onStartedFilterChanged: (TriState) -> Unit,
    onSortModeChanged: (Long) -> Unit,
    onDisplayModeChanged: (Long) -> Unit,
    onSetAsDefault: (applyToExistingAnime: Boolean) -> Unit,
    onResetToDefault: () -> Unit,
) {
    var showSetAsDefaultDialog by rememberSaveable { mutableStateOf(false) }
    if (showSetAsDefaultDialog) {
        SetAsDefaultDialog(
            onDismissRequest = { showSetAsDefaultDialog = false },
            onConfirmed = onSetAsDefault,
        )
    }

    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
            stringResource(MR.strings.action_sort),
            stringResource(MR.strings.action_display),
        ),
        tabOverflowMenuContent = { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.set_episode_settings_as_default)) },
                onClick = {
                    showSetAsDefaultDialog = true
                    closeMenu()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_reset)) },
                onClick = {
                    onResetToDefault()
                    closeMenu()
                },
            )
        },
    ) { page ->
        Column(
            modifier = androidx.compose.ui.Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> FilterPage(
                    unwatchedFilter = anime.unwatchedFilter,
                    onUnwatchedFilterChanged = onUnwatchedFilterChanged,
                    startedFilter = anime.startedFilter,
                    onStartedFilterChanged = onStartedFilterChanged,
                )
                1 -> SortPage(
                    sortingMode = anime.sorting,
                    sortDescending = anime.sortDescending(),
                    onItemSelected = onSortModeChanged,
                )
                2 -> DisplayPage(
                    displayMode = anime.displayMode,
                    onItemSelected = onDisplayModeChanged,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FilterPage(
    unwatchedFilter: TriState,
    onUnwatchedFilterChanged: (TriState) -> Unit,
    startedFilter: TriState,
    onStartedFilterChanged: (TriState) -> Unit,
) {
    TriStateItem(
        label = stringResource(MR.strings.action_filter_unwatched),
        state = unwatchedFilter,
        onClick = onUnwatchedFilterChanged,
    )
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = startedFilter,
        onClick = onStartedFilterChanged,
    )
}

@Composable
private fun ColumnScope.SortPage(
    sortingMode: Long,
    sortDescending: Boolean,
    onItemSelected: (Long) -> Unit,
) {
    listOf(
        MR.strings.sort_by_source to AnimeTitle.EPISODE_SORTING_SOURCE,
        MR.strings.sort_by_number to AnimeTitle.EPISODE_SORTING_NUMBER,
        MR.strings.sort_by_upload_date to AnimeTitle.EPISODE_SORTING_UPLOAD_DATE,
        MR.strings.action_sort_alpha to AnimeTitle.EPISODE_SORTING_ALPHABET,
    ).map { (titleRes, mode) ->
        SortItem(
            label = stringResource(titleRes),
            sortDescending = sortDescending.takeIf { sortingMode == mode },
            onClick = { onItemSelected(mode) },
        )
    }
}

@Composable
private fun ColumnScope.DisplayPage(
    displayMode: Long,
    onItemSelected: (Long) -> Unit,
) {
    listOf(
        MR.strings.show_title to AnimeTitle.EPISODE_DISPLAY_NAME,
        MR.strings.show_chapter_number to AnimeTitle.EPISODE_DISPLAY_NUMBER,
    ).map { (titleRes, mode) ->
        RadioItem(
            label = stringResource(titleRes),
            selected = displayMode == mode,
            onClick = { onItemSelected(mode) },
        )
    }
}

@Composable
private fun SetAsDefaultDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: (optionalChecked: Boolean) -> Unit,
) {
    var optionalChecked by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.episode_settings)) },
        text = {
            Column {
                Text(text = stringResource(MR.strings.confirm_set_episode_settings))
                LabeledCheckbox(
                    label = stringResource(MR.strings.also_set_episode_settings_for_library),
                    checked = optionalChecked,
                    onCheckedChange = { optionalChecked = it },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmed(optionalChecked)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
