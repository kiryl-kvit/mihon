package eu.kanade.tachiyomi.ui.anime.browse.extension

import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.extension.interactor.GetAnimeExtensionSources
import eu.kanade.domain.source.interactor.ToggleAnimeSource
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsSourceUiModel
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsState
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionDetailsScreenModel(
    pkgName: String,
    context: Context,
    private val network: NetworkHelper = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getVideoExtensionSources: GetAnimeExtensionSources = Injekt.get(),
    private val toggleAnimeSource: ToggleAnimeSource = Injekt.get(),
    private val toggleIncognito: ToggleIncognito = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<ExtensionDetailsState>(ExtensionDetailsState()) {

    private val _events: Channel<AnimeExtensionDetailsEvent> = Channel()
    val events: Flow<AnimeExtensionDetailsEvent> = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            launch {
                extensionManager.installedExtensionsFlow
                    .map { extensions ->
                        extensions
                            .filterIsInstance<Extension.InstalledAnime>()
                            .firstOrNull { extension -> extension.pkgName == pkgName }
                    }
                    .collectLatest { extension ->
                        if (extension == null) {
                            _events.send(AnimeExtensionDetailsEvent.Uninstalled)
                            return@collectLatest
                        }
                        mutableState.update { state ->
                            state.copy(extension = extension)
                        }
                    }
            }
            launch {
                state.collectLatest { state ->
                    val extension = state.extension as? Extension.InstalledAnime ?: return@collectLatest
                    getVideoExtensionSources.subscribe(extension)
                        .map {
                            it.sortedWith(
                                compareBy(
                                    { !it.enabled },
                                    { item ->
                                        item.source.name.takeIf { item.labelAsName }
                                            ?: LocaleHelper.getSourceDisplayName(item.source.lang, context).lowercase()
                                    },
                                ),
                            )
                        }
                        .catch { throwable ->
                            logcat(LogPriority.ERROR, throwable)
                            mutableState.update { it.copyWithSources(persistentListOf()) }
                        }
                        .collectLatest { sources ->
                            mutableState.update {
                                it.copyWithSources(
                                    sources
                                        .map { source ->
                                            ExtensionDetailsSourceUiModel(
                                                id = source.source.id,
                                                name = source.source.name,
                                                title = source.source.toString(),
                                                lang = source.source.lang,
                                                labelAsName = source.labelAsName,
                                                enabled = source.enabled,
                                                hasSettings = source.source is ConfigurableAnimeSource,
                                            )
                                        }
                                        .toImmutableList(),
                                )
                            }
                        }
                }
            }
            launch {
                preferences.incognitoExtensions
                    .changes()
                    .map { pkgName in it }
                    .distinctUntilChanged()
                    .collectLatest { isIncognito ->
                        mutableState.update { it.copy(isIncognito = isIncognito) }
                    }
            }
        }
    }

    fun uninstallExtension() {
        val extension = state.value.extension ?: return
        extensionManager.uninstallExtension(extension)
    }

    fun clearCookies() {
        val extension = state.value.extension as? Extension.InstalledAnime ?: return

        val urls = extension.sources
            .filterIsInstance<AnimeHttpSource>()
            .mapNotNull { it.baseUrl.takeUnless(String::isEmpty) }
            .distinct()

        val cleared = urls.sumOf {
            try {
                network.cookieJar.remove(it.toHttpUrl())
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to clear cookies for $it" }
                0
            }
        }

        logcat { "Cleared $cleared cookies for: ${urls.joinToString()}" }
    }

    fun toggleSource(sourceId: Long) {
        toggleAnimeSource.await(sourceId)
    }

    fun toggleSources(enable: Boolean) {
        (state.value.extension as? Extension.InstalledAnime)?.sources
            ?.map { it.id }
            ?.let { toggleAnimeSource.await(it, enable) }
    }

    fun toggleIncognito(enable: Boolean) {
        state.value.extension?.pkgName?.let { packageName ->
            toggleIncognito.await(packageName, enable)
        }
    }
}

sealed interface AnimeExtensionDetailsEvent {
    data object Uninstalled : AnimeExtensionDetailsEvent
}
