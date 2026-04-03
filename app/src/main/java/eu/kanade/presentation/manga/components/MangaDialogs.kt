package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.collections.immutable.toImmutableList
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

@Composable
fun DeleteChaptersDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = stringResource(MR.strings.confirm_delete_chapters))
        },
    )
}

@Composable
fun SetIntervalDialog(
    interval: Int,
    nextUpdate: Instant?,
    onDismissRequest: () -> Unit,
    onValueChanged: ((Int) -> Unit)? = null,
) {
    var selectedInterval by rememberSaveable { mutableIntStateOf(if (interval < 0) -interval else 0) }

    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate != null) {
            val now = Instant.now()
            now.until(nextUpdate, ChronoUnit.DAYS).toInt().coerceAtLeast(0)
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_library_update_smart_update)) },
        text = {
            Column {
                if (nextUpdateDays != null && nextUpdateDays >= 0 && interval >= 0) {
                    Text(
                        stringResource(
                            MR.strings.manga_interval_expected_update,
                            pluralStringResource(
                                MR.plurals.day,
                                count = nextUpdateDays,
                                nextUpdateDays,
                            ),
                            pluralStringResource(
                                MR.plurals.day,
                                count = interval.absoluteValue,
                                interval.absoluteValue,
                            ),
                        ),
                    )
                } else {
                    Text(
                        stringResource(MR.strings.manga_interval_expected_update_null),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.padding.small))

                if (onValueChanged != null && (!isReleaseBuildType)) {
                    Text(stringResource(MR.strings.manga_interval_custom_amount))

                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val size = DpSize(width = maxWidth / 2, height = 128.dp)
                        val items = (0..FetchInterval.MAX_INTERVAL)
                            .map {
                                if (it == 0) {
                                    stringResource(MR.strings.label_default)
                                } else {
                                    it.toString()
                                }
                            }
                            .toImmutableList()
                        WheelTextPicker(
                            items = items,
                            size = size,
                            startIndex = selectedInterval,
                            onSelectionChanged = { selectedInterval = it },
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onValueChanged?.invoke(selectedInterval)
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun EditDisplayNameDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_set_display_name)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                TextButton(onClick = { onConfirm("") }) {
                    Text(text = stringResource(MR.strings.action_reset))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun ManageMergeDialog(
    targetId: Long,
    members: List<MangaScreenModel.MergeMember>,
    removableIds: List<Long>,
    onDismissRequest: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onSaveOrder: () -> Unit,
    onOpenManga: (Long) -> Unit,
    onToggleRemoveMember: (Long) -> Unit,
    onRemoveMembers: (List<Long>) -> Unit,
    onUnmergeAll: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState, PaddingValues()) { from, to ->
        val fromIndex = members.indexOfFirst { it.id == from.key }
        val toIndex = members.indexOfFirst { it.id == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        onMove(fromIndex, toIndex)
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_manage_merge)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = "Top to bottom = merged entry order",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    items(
                        items = members,
                        key = { it.id },
                    ) { member ->
                        ReorderableItem(reorderableState, member.id, enabled = members.size > 1) {
                            ManageMergeItem(
                                member = member,
                                isTarget = member.id == targetId,
                                markedForRemoval = member.id in removableIds,
                                onToggleRemove = { onToggleRemoveMember(member.id) },
                                onOpenManga = onOpenManga,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                TextButton(onClick = onUnmergeAll) {
                    Text(text = stringResource(MR.strings.action_unmerge))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                TextButton(onClick = onSaveOrder) {
                    Text(text = stringResource(MR.strings.action_save))
                }
                TextButton(
                    enabled = removableIds.isNotEmpty(),
                    onClick = { onRemoveMembers(removableIds) },
                ) {
                    Text(text = stringResource(MR.strings.action_remove))
                }
            }
        },
    )
}

@Composable
private fun ReorderableCollectionItemScope.ManageMergeItem(
    member: MangaScreenModel.MergeMember,
    isTarget: Boolean,
    markedForRemoval: Boolean,
    onToggleRemove: () -> Unit,
    onOpenManga: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (markedForRemoval) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.small, vertical = MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                MangaCover.Square(
                    data = member.manga,
                    modifier = Modifier.size(64.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    Text(
                        text = member.manga.presentationTitle(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = member.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (markedForRemoval) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (markedForRemoval) {
                        Text(
                            text = stringResource(MR.strings.action_remove),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(MR.strings.label_more),
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        if (!isTarget) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (markedForRemoval) MR.strings.action_keep else MR.strings.action_remove,
                                        ),
                                    )
                                },
                                onClick = {
                                    onToggleRemove()
                                    expanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_open)) },
                            onClick = {
                                onOpenManga(member.id)
                                expanded = false
                            },
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = null,
                    modifier = Modifier.draggableHandle(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isTarget) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(MaterialTheme.padding.extraSmall),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = "Root",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
