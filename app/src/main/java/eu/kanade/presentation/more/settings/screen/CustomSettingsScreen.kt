package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import mihon.core.common.CustomPreferences
import mihon.core.common.GlobalCustomPreferences
import mihon.core.common.HomeScreenTabs
import mihon.feature.profiles.core.ProfilesPreferences
import mihon.feature.profiles.ui.ProfilesSettingsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
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
        val navigator = LocalNavigator.currentOrThrow
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.profiles_user_profiles),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.profiles_manage_title),
                        subtitle = stringResource(MR.strings.profiles_manage_summary),
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
                    Preference.PreferenceItem.ListPreference(
                        preference = customPreferences.homeScreenStartupTab,
                        entries = persistentMapOf(
                            HomeScreenTabs.Library to stringResource(MR.strings.label_library),
                            HomeScreenTabs.Updates to stringResource(MR.strings.label_recent_updates),
                            HomeScreenTabs.History to stringResource(MR.strings.history),
                            HomeScreenTabs.Browse to stringResource(MR.strings.browse),
                        ),
                        title = stringResource(MR.strings.pref_startup_screen),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = globalCustomPreferences.extensionsAutoUpdates,
                        title = stringResource(MR.strings.pref_extensions_auto_update),
                    ),
                ),
            ),
        )
    }
}
