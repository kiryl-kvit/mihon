package mihon.core.common

import dev.icerock.moko.resources.StringResource
import mihon.core.common.HomeScreenTabs
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.i18n.MR

class CustomPreferences(
    preferenceStore: PreferenceStore,
) {
    val homeScreenStartupTab: Preference<HomeScreenTabs> = preferenceStore.getEnum(
        Preference.appStateKey("home_screen_startup_tab"),
        HomeScreenTabs.Library,
    )

    val homeScreenTabs: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("home_screen_tabs"),
        defaultHomeScreenTabs(),
    )

    val homeScreenTabOrder: Preference<List<HomeScreenTabs>> = preferenceStore.getObjectFromString(
        Preference.appStateKey("home_screen_tab_order"),
        defaultHomeScreenTabOrder(),
        serializer = { it.toHomeScreenTabOrderPreferenceValue() },
        deserializer = { it.toHomeScreenTabOrder() },
    )

    val enableMangaPreview: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("enable_manga_preview"),
        false,
    )

    val mangaPreviewPageCount: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("manga_preview_page_count"),
        5,
    )

    val mangaPreviewSize: Preference<MangaPreviewSize> = preferenceStore.getEnum(
        Preference.appStateKey("manga_preview_size"),
        MangaPreviewSize.MEDIUM,
    )

    enum class MangaPreviewSize(val titleRes: StringResource) {
        SMALL(MR.strings.pref_manga_preview_size_small),
        MEDIUM(MR.strings.pref_manga_preview_size_medium),
        LARGE(MR.strings.pref_manga_preview_size_large),
    }
}
