package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.manga.pushSourceMangaScreen
import kotlinx.coroutines.launch
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.feature.migration.list.MigrationListScreen
import tachiyomi.domain.manga.interactor.GetMergedManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateSearchScreen(private val mangaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val getMergedManga = remember { Injekt.get<GetMergedManga>() }

        val screenModel = rememberScreenModel { MigrateSearchScreenModel(mangaId = mangaId) }
        val state by screenModel.state.collectAsState()

        MigrateSearchScreen(
            state = state,
            fromSourceId = state.from?.source,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            getManga = { screenModel.getManga(it) },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            onClickSource = { navigator.push(MigrateSourceSearchScreen(state.from!!, it.id, state.searchQuery)) },
            onClickItem = {
                val migrateListScreen = navigator.items
                    .filterIsInstance<MigrationListScreen>()
                    .lastOrNull()

                if (migrateListScreen == null) {
                    screenModel.setMigrateDialog(mangaId, it)
                } else {
                    migrateListScreen.addMatchOverride(current = mangaId, target = it.id)
                    navigator.popUntil { screen -> screen is MigrationListScreen }
                }
            },
            onLongClickItem = {
                scope.launch {
                    navigator.pushSourceMangaScreen(it.id, getMergedManga)
                }
            },
        )

        when (val dialog = state.dialog) {
            is SearchScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.current] so we show [dialog.target].
                    onClickTitle = {
                        scope.launch {
                            navigator.pushSourceMangaScreen(dialog.target.id, getMergedManga)
                        }
                    },
                    onDismissRequest = { screenModel.clearDialog() },
                    onComplete = {
                        if (navigator.lastItem is MangaScreen) {
                            val lastItem = navigator.lastItem
                            navigator.popUntil { navigator.items.contains(lastItem) }
                            navigator.push(MangaScreen(dialog.target.id))
                        } else {
                            navigator.replace(MangaScreen(dialog.target.id))
                        }
                    },
                )
            }
            else -> {}
        }
    }
}
