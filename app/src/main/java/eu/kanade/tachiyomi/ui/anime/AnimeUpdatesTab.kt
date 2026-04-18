package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.anime.updates.AnimeUpdatesScreen
import eu.kanade.presentation.anime.updates.animeUpdatesBottomBarConfig
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.presentation.updates.UpdatesScreenState
import eu.kanade.presentation.updates.animeUpdatesFilterOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.anime.updates.AnimeUpdatesItem
import eu.kanade.tachiyomi.ui.anime.updates.AnimeUpdatesScreenModel
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

data object AnimeUpdatesTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val screenModel = rememberScreenModel { AnimeUpdatesScreenModel() }
        val settingsScreenModel = rememberScreenModel { UpdatesSettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        AnimeUpdatesScreen(
            state = UpdatesScreenState<AnimeUpdatesItem>(
                isLoading = state.isLoading,
                isEmpty = state.items.isEmpty(),
                selectionMode = state.selectionMode,
                selectedCount = state.selected.size,
                bottomBarConfig = animeUpdatesBottomBarConfig(
                    selected = state.selected,
                    onMarkWatched = screenModel::markUpdatesWatched,
                ),
            ),
            uiModels = state.getUiModel(),
            snackbarHostState = screenModel.snackbarHostState,
            lastUpdated = screenModel.lastUpdated,
            onSelectAll = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onUpdateLibrary = screenModel::updateLibrary,
            onFilterClicked = screenModel::showFilterDialog,
            hasActiveFilters = state.hasActiveFilters,
            onClickCover = { item -> navigator.push(AnimeScreen(item.visibleAnimeId)) },
            onOpenEpisode = { item ->
                scope.launch {
                    context.openAnimeEpisode(item.visibleAnimeId, item.update.animeId, item.update.episodeId)
                }
            },
            onUpdateSelected = screenModel::toggleSelection,
        )

        when (state.dialog) {
            AnimeUpdatesScreenModel.Dialog.FilterSheet -> {
                UpdatesFilterDialog(
                    onDismissRequest = { screenModel.setDialog(null) },
                    screenModel = settingsScreenModel,
                    options = animeUpdatesFilterOptions(),
                )
            }

            null -> Unit
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    AnimeUpdatesScreenModel.Event.InternalError -> {
                        screenModel.snackbarHostState.showSnackbar(
                            context.stringResource(MR.strings.internal_error),
                        )
                    }

                    is AnimeUpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                    }
                }
            }
        }

        LaunchedEffect(state.selectionMode) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        DisposableEffect(Unit) {
            screenModel.resetNewUpdatesCount()

            onDispose {
                screenModel.resetNewUpdatesCount()
            }
        }
    }
}

private fun Context.openAnimeEpisode(visibleAnimeId: Long, ownerAnimeId: Long, episodeId: Long) {
    startActivity(
        VideoPlayerActivity.newIntent(
            context = this,
            animeId = visibleAnimeId,
            ownerAnimeId = ownerAnimeId,
            episodeId = episodeId,
        ),
    )
}
