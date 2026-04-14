package eu.kanade.tachiyomi.ui.updates

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
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesBottomBarConfig
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.presentation.updates.UpdatesScreen
import eu.kanade.presentation.updates.UpdatesScreenState
import eu.kanade.presentation.updates.mangaUpdatesUiItems
import eu.kanade.presentation.updates.updatesLastUpdatedItem
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel.Event
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.UpcomingScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object UpdatesTab : Tab {

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

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val screenModel = rememberScreenModel { UpdatesScreenModel() }
        val settingsScreenModel = rememberScreenModel { UpdatesSettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        UpdatesScreen(
            state = UpdatesScreenState<UpdatesItem>(
                isLoading = state.isLoading,
                isEmpty = state.items.isEmpty(),
                selectionMode = state.selectionMode,
                selectedCount = state.selected.size,
                bottomBarConfig = UpdatesBottomBarConfig(
                    visible = state.selected.isNotEmpty(),
                    onBookmarkClicked = {
                        screenModel.bookmarkUpdates(state.selected, true)
                    }.takeIf { state.selected.any { !it.update.bookmark } },
                    onRemoveBookmarkClicked = {
                        screenModel.bookmarkUpdates(state.selected, false)
                    }.takeIf { state.selected.isNotEmpty() && state.selected.all { it.update.bookmark } },
                    onMarkAsReadClicked = {
                        screenModel.markUpdatesRead(state.selected, true)
                    }.takeIf { state.selected.any { !it.update.read } },
                    onMarkAsUnreadClicked = {
                        screenModel.markUpdatesRead(state.selected, false)
                    }.takeIf { state.selected.any { it.update.read || it.update.lastPageRead > 0L } },
                    onDownloadClicked = {
                        screenModel.downloadChapters(state.selected, ChapterDownloadAction.START)
                    }.takeIf { state.selected.any { it.downloadStateProvider() != Download.State.DOWNLOADED } },
                    onDeleteClicked = {
                        screenModel.showConfirmDeleteChapters(state.selected)
                    }.takeIf { state.selected.any { it.downloadStateProvider() == Download.State.DOWNLOADED } },
                ),
            ),
            snackbarHostState = screenModel.snackbarHostState,
            onSelectAll = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onUpdateLibrary = screenModel::updateLibrary,
            onCalendarClicked = { navigator.push(UpcomingScreen()) },
            onFilterClicked = screenModel::showFilterDialog,
            hasActiveFilters = state.hasActiveFilters,
        ) {
            updatesLastUpdatedItem(screenModel.lastUpdated)
            mangaUpdatesUiItems(
                uiModels = state.getUiModel(),
                selectionMode = state.selectionMode,
                onUpdateSelected = screenModel::toggleSelection,
                onClickCover = { item ->
                    navigator.push(MangaScreen(item.visibleMangaId))
                },
                onClickUpdate = {
                    val intent = ReaderActivity.newIntent(context, it.visibleMangaId, it.update.chapterId)
                    context.startActivity(intent)
                },
                onDownloadChapter = screenModel::downloadChapters,
            )
        }

        val onDismissDialog = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is UpdatesScreenModel.Dialog.DeleteConfirmation -> {
                UpdatesDeleteConfirmationDialog(
                    onDismissRequest = onDismissDialog,
                    onConfirm = { screenModel.deleteChapters(dialog.toDelete) },
                )
            }
            is UpdatesScreenModel.Dialog.FilterSheet -> {
                UpdatesFilterDialog(
                    onDismissRequest = onDismissDialog,
                    screenModel = settingsScreenModel,
                )
            }
            null -> {}
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                        context.stringResource(MR.strings.internal_error),
                    )
                    is Event.LibraryUpdateTriggered -> {
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
