package eu.kanade.tachiyomi.ui.anime

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.anime.more.AnimeMoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import kotlinx.coroutines.launch
import mihon.feature.profiles.core.ProfileManager
import mihon.feature.profiles.ui.ProfilePickerScreen
import mihon.feature.profiles.ui.handleProfileShortcut
import mihon.feature.support.SupportUsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object AnimeMoreTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(SettingsScreen())
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { AnimeMoreScreenModel() }
        val profileManager = remember { Injekt.get<ProfileManager>() }
        val uiPreferences = remember { Injekt.get<UiPreferences>() }

        AnimeMoreScreen(
            incognitoMode = screenModel.incognitoMode,
            onIncognitoModeChange = { screenModel.incognitoMode = it },
            onClickCategories = { navigator.push(CategoryScreen()) },
            onClickDataAndStorage = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onClickProfiles = {
                scope.launch {
                    handleProfileShortcut(
                        context = context,
                        profileManager = profileManager,
                        uiPreferences = uiPreferences,
                        onOpenProfilePicker = { navigator.push(ProfilePickerScreen()) },
                    )
                }
            },
            onClickSettings = { navigator.push(SettingsScreen()) },
            onClickSupport = { navigator.push(SupportUsScreen()) },
            onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
        )
    }
}

private class AnimeMoreScreenModel(
    basePreferences: BasePreferences = Injekt.get(),
) : ScreenModel {

    var incognitoMode by basePreferences.incognitoMode.asState(screenModelScope)
}
