package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import mihon.core.common.CustomPreferences
import mihon.core.common.GlobalCustomPreferences
import mihon.core.common.HomeScreenTabs
import mihon.core.common.resolveHomeScreenTab
import mihon.core.common.sanitizeHomeScreenTabOrder
import mihon.core.common.sanitizeHomeScreenTabs
import mihon.core.common.toHomeScreenTabPreferenceValue
import mihon.core.common.toHomeScreenTabs
import mihon.feature.profiles.core.ProfilesPreferences
import mihon.feature.profiles.ui.ProfilesSettingsScreen
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object CustomSettingsScreen : SearchableSettings {
    private fun readResolve(): Any = CustomSettingsScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_custom

    @Composable
    override fun getPreferences(): List<Preference> {
        val customPreferences = remember { Injekt.get<CustomPreferences>() }
        val globalCustomPreferences = remember { Injekt.get<GlobalCustomPreferences>() }
        val profilesPreferences = remember { Injekt.get<ProfilesPreferences>() }
        val previewEnabled by customPreferences.enableMangaPreview.collectAsState()
        val previewPageCount by customPreferences.mangaPreviewPageCount.collectAsState()
        val startupTab by customPreferences.homeScreenStartupTab.collectAsState()
        val homeScreenTabs by customPreferences.homeScreenTabs.collectAsState()
        val storedHomeTabOrder by customPreferences.homeScreenTabOrder.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var showProfilesInfo by rememberSaveable { mutableStateOf(false) }
        var showHomeTabsDialog by rememberSaveable { mutableStateOf(false) }
        val homeTabEntries = rememberHomeTabEntries()
        val enabledHomeTabs = remember(homeScreenTabs, storedHomeTabOrder) {
            sanitizeHomeScreenTabs(homeScreenTabs.toHomeScreenTabs(), storedHomeTabOrder)
        }
        val startupTabEntries = remember(enabledHomeTabs, homeTabEntries) {
            enabledHomeTabs
                .filterNot { it == HomeScreenTabs.Profiles }
                .associateWith { homeTabEntries.getValue(it) }
                .toImmutableMap()
        }

        if (showProfilesInfo) {
            AlertDialog(
                onDismissRequest = { showProfilesInfo = false },
                title = { Text(text = stringResource(MR.strings.profiles_info_title)) },
                text = { Text(text = stringResource(MR.strings.profiles_info_description)) },
                confirmButton = {
                    TextButton(onClick = { showProfilesInfo = false }) {
                        Text(text = stringResource(MR.strings.action_close))
                    }
                },
            )
        }

        if (showHomeTabsDialog) {
            val selectedTabs = remember(homeScreenTabs, storedHomeTabOrder) {
                sanitizeHomeScreenTabs(homeScreenTabs.toHomeScreenTabs(), storedHomeTabOrder).toMutableStateList()
            }
            val orderedTabs = remember(storedHomeTabOrder) {
                sanitizeHomeScreenTabOrder(storedHomeTabOrder).toMutableStateList()
            }
            val listState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(listState, PaddingValues()) { from, to ->
                val fromIndex = orderedTabs.indexOfFirst { it.name == from.key }
                val toIndex = orderedTabs.indexOfFirst { it.name == to.key }
                if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
                orderedTabs.add(toIndex, orderedTabs.removeAt(fromIndex))
            }
            AlertDialog(
                onDismissRequest = { showHomeTabsDialog = false },
                title = { Text(text = stringResource(MR.strings.pref_home_screen_tabs)) },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        items(
                            items = orderedTabs,
                            key = { it.name },
                        ) { tab ->
                            val isSelected = tab in selectedTabs
                            val isLocked = tab == HomeScreenTabs.More
                            ReorderableItem(reorderableState, tab.name, enabled = orderedTabs.size > 1) {
                                HomeTabItem(
                                    label = homeTabEntries.getValue(tab),
                                    checked = isSelected,
                                    visibilityLocked = isLocked,
                                    dragEnabled = orderedTabs.size > 1,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (tab !in selectedTabs) {
                                                selectedTabs.add(tab)
                                            }
                                        } else {
                                            selectedTabs.remove(tab)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val updatedTabOrder = sanitizeHomeScreenTabOrder(orderedTabs)
                            val newTabs = sanitizeHomeScreenTabs(selectedTabs.toSet(), updatedTabOrder)
                            customPreferences.homeScreenTabOrder.set(updatedTabOrder)
                            customPreferences.homeScreenTabs.set(newTabs.toHomeScreenTabPreferenceValue())
                            val resolvedStartupTab = resolveHomeScreenTab(
                                requestedTab = startupTab,
                                enabledTabs = newTabs.filterNot { it == HomeScreenTabs.Profiles },
                                tabOrder = updatedTabOrder,
                            )
                            if (resolvedStartupTab != startupTab) {
                                customPreferences.homeScreenStartupTab.set(resolvedStartupTab)
                            }
                            showHomeTabsDialog = false
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showHomeTabsDialog = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.profiles_user_profiles),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.profiles_manage_title),
                        subtitle = stringResource(MR.strings.profiles_manage_summary),
                        widget = {
                            IconButton(onClick = { showProfilesInfo = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = stringResource(MR.strings.profiles_info_title),
                                )
                            }
                        },
                        onClick = {
                            navigator.push(ProfilesSettingsScreen())
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = profilesPreferences.pickerEnabled,
                        title = stringResource(MR.strings.profiles_choose_on_launch),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_general),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_home_screen_tabs),
                        subtitle = enabledHomeTabs.joinToString { homeTabEntries.getValue(it) },
                        onClick = { showHomeTabsDialog = true },
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = customPreferences.homeScreenStartupTab,
                        entries = startupTabEntries,
                        title = stringResource(MR.strings.pref_startup_screen),
                        onValueChanged = {
                            customPreferences.homeScreenStartupTab.set(
                                resolveHomeScreenTab(
                                    requestedTab = it,
                                    enabledTabs = enabledHomeTabs.filterNot { it == HomeScreenTabs.Profiles },
                                    tabOrder = storedHomeTabOrder,
                                ),
                            )
                            false
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = customPreferences.enableFeeds,
                        title = stringResource(MR.strings.pref_enable_feeds),
                        subtitle = stringResource(MR.strings.pref_enable_feeds_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = globalCustomPreferences.extensionsAutoUpdates,
                        title = stringResource(MR.strings.pref_extensions_auto_update),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_duplicate_detection),
                        subtitle = stringResource(MR.strings.pref_duplicate_detection_summary),
                        isProfileSpecific = true,
                        onClick = {
                            navigator.push(DuplicateDetectionSettingsScreen)
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_manga_preview),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = customPreferences.enableMangaPreview,
                        title = stringResource(MR.strings.pref_enable_manga_preview),
                        subtitle = stringResource(MR.strings.pref_enable_manga_preview_summary),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = previewPageCount,
                        preference = customPreferences.mangaPreviewPageCount,
                        valueRange = CustomPreferences.MANGA_PREVIEW_PAGE_COUNT_RANGE,
                        title = stringResource(MR.strings.pref_manga_preview_page_count),
                        valueString = previewPageCount.toString(),
                        enabled = previewEnabled,
                        onValueChanged = {
                            customPreferences.mangaPreviewPageCount.set(it)
                        },
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = customPreferences.mangaPreviewSize,
                        entries = CustomPreferences.MangaPreviewSize.entries
                            .associateWith { stringResource(it.titleRes) }
                            .toImmutableMap(),
                        title = stringResource(MR.strings.pref_manga_preview_size),
                        enabled = previewEnabled,
                    ),
                ),
            ),
        )
    }

    @Composable
    private fun rememberHomeTabEntries(): ImmutableMap<HomeScreenTabs, String> {
        return persistentMapOf(
            HomeScreenTabs.Library to stringResource(MR.strings.label_library),
            HomeScreenTabs.Updates to stringResource(MR.strings.label_recent_updates),
            HomeScreenTabs.History to stringResource(MR.strings.history),
            HomeScreenTabs.Browse to stringResource(MR.strings.browse),
            HomeScreenTabs.More to stringResource(MR.strings.label_more),
            HomeScreenTabs.Profiles to stringResource(MR.strings.profiles_switch_summary),
        )
    }

    @Composable
    private fun ReorderableCollectionItemScope.HomeTabItem(
        label: String,
        checked: Boolean,
        visibilityLocked: Boolean,
        dragEnabled: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            LabeledCheckbox(
                label = label,
                checked = checked,
                enabled = !visibilityLocked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .then(if (dragEnabled) Modifier.draggableHandle() else Modifier)
                    .padding(MaterialTheme.padding.small),
            )
        }
    }
}
