package mihon.core.common

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class CustomPreferences(
    preferenceStore: PreferenceStore,
) {
    val homeScreenStartupTab: Preference<HomeScreenTabs> = preferenceStore.getEnum(
        Preference.appStateKey("home_screen_startup_tab"),
        HomeScreenTabs.Library,
    )
}
