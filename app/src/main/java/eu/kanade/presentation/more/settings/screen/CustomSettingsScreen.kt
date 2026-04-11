package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import eu.kanade.presentation.more.settings.screen.debug.LogsScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
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
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
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
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val browseLongPressAction by customPreferences.browseLongPressAction.collectAsState()
        val previewEnabled by customPreferences.enableMangaPreview.collectAsState()
        val previewPageCount by customPreferences.mangaPreviewPageCount.collectAsState()
        val autoScrollEnabled by readerPreferences.autoScrollEnabled.collectAsState()
        val autoScrollSpeed by readerPreferences.autoScrollSpeed.collectAsState()
        val startupTab by customPreferences.homeScreenStartupTab.collectAsState()
        val homeScreenTabs by customPreferences.homeScreenTabs.collectAsState()
        val storedHomeTabOrder by customPreferences.homeScreenTabOrder.collectAsState()
        val homeScreenTabsTitle = stringResource(MR.strings.pref_home_screen_tabs)
        val startupScreenTitle = stringResource(MR.strings.pref_startup_screen)
        val navigator = LocalNavigator.currentOrThrow
        var showProfilesInfo by rememberSaveable { mutableStateOf(false) }
        var showHomeTabsDialog by rememberSaveable { mutableStateOf(false) }
        val homeTabEntries = rememberHomeTabEntries()
        val enabledHomeTabs = remember(homeScreenTabs, storedHomeTabOrder) {
            sanitizeHomeScreenTabs(homeScreenTabs.toHomeScreenTabs(), storedHomeTabOrder)
        }
        val resolvedStartupTab = remember(startupTab, enabledHomeTabs, storedHomeTabOrder) {
            resolveHomeScreenTab(
                requestedTab = startupTab,
                enabledTabs = enabledHomeTabs.filterNot { it == HomeScreenTabs.Profiles },
                tabOrder = storedHomeTabOrder,
            )
        }
        val homeTabsSubtitle = remember(enabledHomeTabs, resolvedStartupTab, startupScreenTitle) {
            buildHomeTabsSubtitle(
                enabledTabs = enabledHomeTabs,
                startupTab = resolvedStartupTab,
                homeTabEntries = homeTabEntries,
                startupScreenTitle = startupScreenTitle,
            )
        }

        LaunchedEffect(previewEnabled, browseLongPressAction) {
            if (!previewEnabled && browseLongPressAction == CustomPreferences.BrowseLongPressAction.MANGA_PREVIEW) {
                customPreferences.browseLongPressAction.set(CustomPreferences.BrowseLongPressAction.LIBRARY_ACTION)
            }
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
            var selectedStartupTab by remember(resolvedStartupTab) {
                mutableStateOf(resolvedStartupTab)
            }
            val listState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(listState, PaddingValues()) { from, to ->
                val fromIndex = orderedTabs.indexOfFirst { it.name == from.key }
                val toIndex = orderedTabs.indexOfFirst { it.name == to.key }
                if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
                orderedTabs.add(toIndex, orderedTabs.removeAt(fromIndex))
            }
            val dialogEnabledTabs = sanitizeHomeScreenTabs(selectedTabs.toSet(), orderedTabs)
                .filterNot { it == HomeScreenTabs.Profiles }

            LaunchedEffect(dialogEnabledTabs, orderedTabs.toList()) {
                val resolvedDialogStartupTab = resolveHomeScreenTab(
                    requestedTab = selectedStartupTab,
                    enabledTabs = dialogEnabledTabs,
                    tabOrder = orderedTabs,
                )
                if (resolvedDialogStartupTab != selectedStartupTab) {
                    selectedStartupTab = resolvedDialogStartupTab
                }
            }

            AlertDialog(
                onDismissRequest = { showHomeTabsDialog = false },
                title = { Text(text = homeScreenTabsTitle) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                    ) {
                        Box {
                            ScrollbarLazyColumn(
                                modifier = Modifier.heightIn(max = 280.dp),
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
                            if (listState.canScrollBackward) {
                                HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                            }
                            if (listState.canScrollForward) {
                                HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                        Column {
                            Text(
                                text = startupScreenTitle,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                            )
                            dialogEnabledTabs.forEach { tab ->
                                RadioItem(
                                    label = homeTabEntries.getValue(tab),
                                    selected = selectedStartupTab == tab,
                                    onClick = { selectedStartupTab = tab },
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
                                requestedTab = selectedStartupTab,
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
                        title = homeScreenTabsTitle,
                        subtitle = homeTabsSubtitle,
                        isProfileSpecific = true,
                        onClick = { showHomeTabsDialog = true },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = customPreferences.enableFeeds,
                        title = stringResource(MR.strings.pref_enable_feeds),
                        subtitle = stringResource(MR.strings.pref_enable_feeds_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = customPreferences.browseLongPressAction,
                        entries = CustomPreferences.BrowseLongPressAction.entries
                            .associateWith { stringResource(it.titleRes) }
                            .toImmutableMap(),
                        title = stringResource(MR.strings.pref_browse_long_press_action),
                        entryEnabledProvider = {
                            previewEnabled || it != CustomPreferences.BrowseLongPressAction.MANGA_PREVIEW
                        },
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
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_view_logs),
                        subtitle = stringResource(MR.strings.pref_view_logs_summary),
                        onClick = {
                            navigator.push(LogsScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_auto_scroll),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.autoScrollEnabled,
                        title = stringResource(MR.strings.pref_enable_auto_scroll),
                        subtitle = stringResource(MR.strings.pref_auto_scroll_summary),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = autoScrollSpeed,
                        preference = readerPreferences.autoScrollSpeed,
                        valueRange = ReaderPreferences.AUTO_SCROLL_SPEED_RANGE,
                        title = stringResource(MR.strings.pref_auto_scroll_speed),
                        valueString = stringResource(ReaderPreferences.AutoScrollLevelLabels[autoScrollSpeed]),
                        enabled = autoScrollEnabled,
                        onValueChanged = { readerPreferences.autoScrollSpeed.set(it) },
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

    private fun buildHomeTabsSubtitle(
        enabledTabs: Collection<HomeScreenTabs>,
        startupTab: HomeScreenTabs,
        homeTabEntries: ImmutableMap<HomeScreenTabs, String>,
        startupScreenTitle: String,
    ): String {
        return buildString {
            append(enabledTabs.joinToString { homeTabEntries.getValue(it) })
            append('\n')
            append(startupScreenTitle)
            append(": ")
            append(homeTabEntries.getValue(startupTab))
        }
    }
}
