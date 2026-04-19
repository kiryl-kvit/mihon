package eu.kanade.tachiyomi.ui.anime

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.anime.AnimeMergeTargetPickerDialog
import eu.kanade.presentation.anime.AnimeScheduleSheet
import eu.kanade.presentation.anime.AnimeScreen
import eu.kanade.presentation.anime.DuplicateAnimeDialog
import eu.kanade.presentation.anime.EpisodeSettingsDialog
import eu.kanade.presentation.anime.ManageAnimeMergeDialog
import eu.kanade.presentation.browse.components.BrowseMergeEditorDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.manga.components.EditDisplayNameDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.anime.browse.AnimeBrowseSourceScreen
import eu.kanade.tachiyomi.ui.anime.browse.globalsearch.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerActivity
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.domain.anime.model.toSAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.core.common.i18n.stringResource as contextStringResource

data class AnimeScreen(
    private val animeId: Long,
    val fromSource: Boolean = false,
    private val bypassMerge: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel {
            AnimeScreenModel(context.applicationContext, animeId, fromSource = fromSource, bypassMerge = bypassMerge)
        }
        val state by screenModel.state.collectAsState()

        when (val current = state) {
            AnimeScreenModel.State.Loading -> LoadingScreen()
            is AnimeScreenModel.State.Error -> {
                Scaffold(
                    topBar = {
                        AppBar(
                            title = stringResource(MR.strings.browse),
                            navigateUp = navigator::pop,
                            scrollBehavior = it,
                        )
                    },
                ) { contentPadding ->
                    EmptyScreen(
                        message = current.message,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
            }
            is AnimeScreenModel.State.Success -> {
                AnimeScreen(
                    state = current,
                    snackbarHostState = screenModel.snackbarHostState,
                    navigateUp = navigator::pop,
                    onRefresh = screenModel::refresh,
                    onAddToLibraryClicked = screenModel::toggleFavorite,
                    onAddToMergeClicked = screenModel::showMergeTargetPicker.takeIf {
                        !current.isPartOfMerge && (current.isFromSource || current.anime.favorite)
                    },
                    onEditCategoryClicked = screenModel::showChangeCategoryDialog.takeIf { current.anime.favorite },
                    onEditDisplayNameClicked = screenModel::showEditDisplayNameDialog.takeIf { current.anime.favorite },
                    onShareClicked = {
                        shareAnime(context, current.anime, screenModel)
                    }.takeIf { screenModel.webViewSource != null },
                    onWebViewClicked = {
                        openAnimeInWebView(navigator, current.anime, screenModel)
                    }.takeIf { screenModel.webViewSource != null },
                    onWebViewLongClicked = {
                        copyAnimeUrl(context, current.anime, screenModel)
                    }.takeIf { screenModel.webViewSource != null },
                    onSearch = { query, global ->
                        scope.launch {
                            performSearch(navigator, current.anime, query, global)
                        }
                    },
                    onTagSearch = { tag ->
                        scope.launch {
                            performTagSearch(navigator, current.anime, tag)
                        }
                    },
                    onScheduleClicked = screenModel::showScheduleDialog.takeIf { current.canOpenScheduleDialog },
                    onDuplicatesClicked = screenModel::showDuplicateDialog.takeIf {
                        current.isFromSource && current.anime.initialized && current.duplicateCandidates.isNotEmpty()
                    },
                    onCoverClicked = screenModel::showCoverDialog,
                    onFilterClicked = screenModel::showSettingsDialog,
                    onManageMergeClicked = screenModel::showManageMergeDialog.takeIf { current.isPartOfMerge },
                    onOpenMergedEntryClicked = {
                        navigator.push(AnimeScreen(current.mergeTargetId))
                    }.takeIf { current.showMergeNotice },
                    onEpisodeClick = { episode ->
                        scope.launch {
                            context.startAnimeEpisode(
                                visibleAnimeId = screenModel.getVisibleAnimeId(episode.animeId),
                                ownerAnimeId = episode.animeId,
                                episodeId = episode.id,
                                bypassMerge = bypassMerge,
                            )
                        }
                    },
                    onEpisodeSelected = screenModel::toggleSelection,
                    onAllEpisodesSelected = screenModel::toggleAllSelection,
                    onInvertSelection = screenModel::invertSelection,
                    onMarkSelectedWatched = screenModel::markSelectedEpisodesWatched,
                )

                when (val dialog = current.dialog) {
                    AnimeScreenModel.Dialog.FullCover -> {
                        val sm = rememberScreenModel { AnimeCoverScreenModel(current.anime.id) }
                        val anime by sm.state.collectAsState()
                        if (anime != null) {
                            MangaCoverDialog(
                                coverData = anime!!.toMangaCover(),
                                snackbarHostState = sm.snackbarHostState,
                                isCustomCover = false,
                                onShareClick = { sm.shareCover(context) },
                                onSaveClick = { sm.saveCover(context) },
                                onEditClick = null,
                                onDismissRequest = screenModel::dismissDialog,
                            )
                        } else {
                            LoadingScreen(Modifier.systemBarsPadding())
                        }
                    }
                    is AnimeScreenModel.Dialog.DuplicateAnime -> {
                        DuplicateAnimeDialog(
                            duplicates = dialog.duplicates,
                            onDismissRequest = screenModel::dismissDialog,
                            onConfirm = { screenModel.toggleFavorite(checkDuplicate = false) },
                            onOpenAnime = { navigator.push(AnimeScreen(it.id)) },
                            onMerge = { screenModel.openMergeEditorForDuplicate(it.anime.id) },
                        )
                    }
                    AnimeScreenModel.Dialog.Schedule -> {
                        AnimeScheduleSheet(
                            schedule = current.schedule,
                            onRetry = screenModel::retryLoadSchedule,
                            onDismissRequest = screenModel::dismissDialog,
                        )
                    }
                    AnimeScreenModel.Dialog.SettingsSheet -> {
                        EpisodeSettingsDialog(
                            onDismissRequest = screenModel::dismissDialog,
                            anime = current.anime,
                            onUnwatchedFilterChanged = screenModel::setUnwatchedFilter,
                            onStartedFilterChanged = screenModel::setStartedFilter,
                            onSortModeChanged = screenModel::setSorting,
                            onDisplayModeChanged = screenModel::setDisplayMode,
                            onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
                            onResetToDefault = screenModel::resetToDefaultSettings,
                        )
                    }
                    is AnimeScreenModel.Dialog.ChangeCategory -> {
                        ChangeCategoryDialog(
                            initialSelection = dialog.initialSelection,
                            onDismissRequest = screenModel::dismissDialog,
                            onEditCategories = { navigator.push(CategoryScreen()) },
                            onConfirm = { include, _ -> screenModel.setCategories(include) },
                        )
                    }
                    is AnimeScreenModel.Dialog.EditDisplayName -> {
                        EditDisplayNameDialog(
                            initialValue = dialog.initialValue,
                            onDismissRequest = screenModel::dismissDialog,
                            onConfirm = screenModel::updateDisplayName,
                        )
                    }
                    is AnimeScreenModel.Dialog.ManageMerge -> {
                        ManageAnimeMergeDialog(
                            targetId = dialog.targetId,
                            members = dialog.members,
                            removableIds = dialog.removableIds,
                            libraryRemovalIds = dialog.libraryRemovalIds,
                            onDismissRequest = screenModel::dismissDialog,
                            onMove = screenModel::reorderMergeMembers,
                            onSaveOrder = screenModel::saveMergeOrder,
                            onOpenAnime = { animeIdToOpen ->
                                screenModel.dismissDialog()
                                navigator.push(
                                    AnimeScreen(
                                        animeIdToOpen,
                                        bypassMerge = animeIdToOpen != dialog.savedTargetId,
                                    ),
                                )
                            },
                            onSelectTarget = screenModel::setManageMergeTarget,
                            onToggleRemoveMember = screenModel::toggleMergedMemberRemoval,
                            onToggleRemoveMemberFromLibrary = screenModel::toggleMergedMemberLibraryRemoval,
                            onRemoveMembers = screenModel::removeMergedMembers,
                            onUnmergeAll = screenModel::unmergeAll,
                        )
                    }
                    is AnimeScreenModel.Dialog.EditMerge -> {
                        BrowseMergeEditorDialog(
                            entries = dialog.entries,
                            targetId = dialog.targetId,
                            targetLocked = dialog.targetLocked,
                            removedIds = dialog.removedIds,
                            libraryRemovalIds = dialog.libraryRemovalIds,
                            confirmEnabled = dialog.enabled,
                            onDismissRequest = screenModel::dismissDialog,
                            onMove = screenModel::moveMergeEntry,
                            onSelectTarget = screenModel::setMergeTarget,
                            onToggleRemove = screenModel::toggleMergeEntryRemoval,
                            onToggleLibraryRemove = screenModel::toggleMergeEntryLibraryRemoval,
                            onConfirm = screenModel::confirmMerge,
                        )
                    }
                    is AnimeScreenModel.Dialog.RemoveMergedAnime -> {
                        DeleteLibraryMangaDialog(
                            containsLocalManga = false,
                            containsMergedManga = true,
                            onDismissRequest = screenModel::dismissDialog,
                            onConfirm = { deleteAnime, _ ->
                                if (deleteAnime) {
                                    screenModel.removeMergedAnime(dialog.members)
                                } else {
                                    screenModel.dismissDialog()
                                }
                            },
                        )
                    }
                    is AnimeScreenModel.Dialog.SelectMergeTarget -> {
                        AnimeMergeTargetPickerDialog(
                            title = context.contextStringResource(MR.strings.action_merge_into_library),
                            query = dialog.query,
                            visibleTargets = dialog.visibleTargets,
                            onDismissRequest = screenModel::dismissDialog,
                            onQueryChange = screenModel::updateMergeTargetQuery,
                            onSelectTarget = screenModel::openMergeEditor,
                        )
                    }
                    null -> Unit
                }
            }
        }
    }
}

private fun getAnimeUrl(anime: AnimeTitle, screenModel: AnimeScreenModel): String? {
    val source = screenModel.webViewSource ?: return null
    return runCatching { source.getAnimeUrl(anime.toSAnime()) }.getOrNull()
}

private fun openAnimeInWebView(
    navigator: Navigator,
    anime: AnimeTitle,
    screenModel: AnimeScreenModel,
) {
    val source = screenModel.webViewSource ?: return
    val url = getAnimeUrl(anime, screenModel) ?: return

    navigator.push(
        WebViewScreen(
            url = url,
            initialTitle = anime.title,
            headers = source.getWebViewHeaders(),
        ),
    )
}

private fun copyAnimeUrl(context: Context, anime: AnimeTitle, screenModel: AnimeScreenModel) {
    val url = getAnimeUrl(anime, screenModel) ?: return
    context.copyToClipboard(url, url)
}

private fun shareAnime(context: Context, anime: AnimeTitle, screenModel: AnimeScreenModel) {
    runCatching {
        val url = getAnimeUrl(anime, screenModel) ?: return
        context.startActivity(url.toUri().toShareIntent(context, type = "text/plain"))
    }.onFailure {
        context.toast(it.message)
    }
}

private suspend fun performSearch(
    navigator: Navigator,
    anime: AnimeTitle,
    query: String,
    global: Boolean,
) {
    if (global) {
        navigator.push(AnimeGlobalSearchScreen(query))
        return
    }

    if (navigator.size < 2) {
        navigator.push(AnimeGlobalSearchScreen(query))
        return
    }

    when (val previousScreen = navigator.items[navigator.size - 2]) {
        is AnimeBrowseSourceScreen -> {
            if (previousScreen.sourceId == anime.source) {
                navigator.pop()
                previousScreen.search(query)
            } else {
                navigator.push(AnimeGlobalSearchScreen(query))
            }
        }
        else -> navigator.push(AnimeGlobalSearchScreen(query))
    }
}

private suspend fun performTagSearch(
    navigator: Navigator,
    anime: AnimeTitle,
    tag: String,
) {
    if (navigator.size < 2) {
        navigator.push(AnimeGlobalSearchScreen(tag))
        return
    }

    when (val previousScreen = navigator.items[navigator.size - 2]) {
        is AnimeBrowseSourceScreen -> {
            if (previousScreen.sourceId == anime.source) {
                navigator.pop()
                previousScreen.search(tag)
            } else {
                navigator.push(AnimeGlobalSearchScreen(tag))
            }
        }
        else -> performSearch(navigator, anime, tag, global = false)
    }
}

private fun android.content.Context.startAnimeEpisode(
    visibleAnimeId: Long,
    ownerAnimeId: Long,
    episodeId: Long,
    bypassMerge: Boolean = false,
) {
    startActivity(
        VideoPlayerActivity.newIntent(
            context = this,
            animeId = visibleAnimeId,
            ownerAnimeId = ownerAnimeId,
            episodeId = episodeId,
            bypassMerge = bypassMerge,
        ),
    )
}
