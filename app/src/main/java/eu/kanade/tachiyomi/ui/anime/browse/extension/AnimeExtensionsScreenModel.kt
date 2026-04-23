package eu.kanade.tachiyomi.ui.anime.browse.extension

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetAnimeExtensions
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionListState
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class AnimeExtensionsScreenModel(
    basePreferences: BasePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getAnimeExtensions: GetAnimeExtensions = Injekt.get(),
) : StateScreenModel<ExtensionListState>(ExtensionListState()) {

    init {
        val context = Injekt.get<Application>()
        val extensionMapper: (Map<String, InstallStep>) -> ((Extension) -> ExtensionUiModel.Item) = { map ->
            {
                ExtensionUiModel.Item(it, map[it.pkgName] ?: InstallStep.Idle)
            }
        }

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }
                    .distinctUntilChanged()
                    .debounce(SEARCH_DEBOUNCE_MILLIS)
                    .map { searchQueryPredicate(it ?: "") },
                extensionManager.installSteps(),
                getAnimeExtensions.subscribe(),
            ) { predicate, downloads, (_updates, _installed, _available, _untrusted) ->
                buildMap {
                    val updates = _updates.filter(predicate).map(extensionMapper(downloads))
                    if (updates.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending), updates)
                    }

                    val installed = _installed.filter(predicate).map(extensionMapper(downloads))
                    val untrusted = _untrusted.filter(predicate).map(extensionMapper(downloads))
                    if (installed.isNotEmpty() || untrusted.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_installed), installed + untrusted)
                    }

                    val languagesWithExtensions = _available
                        .filter(predicate)
                        .groupBy { it.lang }
                        .toSortedMap(LocaleHelper.comparator)
                        .map { (lang, exts) ->
                            ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                                exts.map(extensionMapper(downloads))
                        }
                    if (languagesWithExtensions.isNotEmpty()) {
                        putAll(languagesWithExtensions)
                    }
                }
            }
                .collectLatest { items ->
                    mutableState.update { current ->
                        current.copy(
                            isLoading = false,
                            items = items,
                        )
                    }
                }
        }

        screenModelScope.launchIO { findAvailableExtensions() }

        combine(
            extensionManager.pendingAnimeUpdatesCount,
            extensionManager.isAutoUpdateInProgress,
        ) { updates, inProgress ->
            if (inProgress) 0 else updates
        }
            .onEach { mutableState.update { state -> state.copy(updates = it) } }
            .launchIn(screenModelScope)

        basePreferences.extensionInstaller.changes()
            .onEach { mutableState.update { state -> state.copy(installer = it) } }
            .launchIn(screenModelScope)
    }

    fun searchQueryPredicate(query: String): (Extension) -> Boolean {
        val subqueries = query.split(",")
            .map { it.trim() }
            .filterNot { it.isBlank() }

        if (subqueries.isEmpty()) return { true }

        return { extension ->
            subqueries.any { subquery ->
                if (extension.name.contains(subquery, ignoreCase = true)) return@any true

                when (extension) {
                    is Extension.InstalledAnime -> extension.sources.any { source ->
                        source.name.contains(subquery, ignoreCase = true) ||
                            source.id == subquery.toLongOrNull()
                    }

                    is Extension.AvailableAnime -> extension.sources.any {
                        it.name.contains(subquery, ignoreCase = true) ||
                            it.baseUrl.contains(subquery, ignoreCase = true) ||
                            it.id == subquery.toLongOrNull()
                    }

                    else -> false
                }
            }
        }
    }

    fun search(query: String?) {
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    fun updateAllExtensions() {
        screenModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.InstalledAnime>()
                .filter { it.hasUpdate }
                .forEach(::updateExtension)
        }
    }

    fun installExtension(extension: Extension.AvailableAnime) {
        screenModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.InstalledAnime) {
        screenModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        extensionManager.cancelInstallUpdateExtension(extension)
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) =
        this.firstOrNull { it.isCompleted() }

    fun uninstallExtension(extension: Extension) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }

            extensionManager.findAvailableExtensions()

            delay(1.seconds)

            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }
}
