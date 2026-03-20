package mihon.core.common

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class CustomPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun homeScreenStartupTab() = preferenceStore.getEnum(
        Preference.appStateKey("home_screen_startup_tab"),
        HomeScreenTabs.Library)

    fun extensionsAutoUpdates() = preferenceStore.getBoolean(
        Preference.appStateKey("extensions_auto_updates"),
        false)
}
