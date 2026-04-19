package eu.kanade.tachiyomi.ui.anime.browse.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.anime.model.toDomainAnime
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.source.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

class AnimeGlobalSearchScreenModel(
    initialQuery: String = "",
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<AnimeGlobalSearchScreenModel.State>(State(searchQuery = initialQuery)) {

    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    private var searchJob: Job? = null

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledAnimeSources.get()
    private val pinnedSources = sourcePreferences.pinnedAnimeSources.get()

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null

    private val sortComparator = { map: Map<AnimeCatalogueSource, SearchItemResult> ->
        compareBy<AnimeCatalogueSource>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        screenModelScope.launch {
            preferences.globalSearchFilterState.changes().collectLatest { onlyShowHasResults ->
                mutableState.update { it.copy(onlyShowHasResults = onlyShowHasResults) }
            }
        }

        if (initialQuery.isNotBlank()) {
            search()
        }
    }

    @Composable
    fun getAnimeState(initialAnime: AnimeTitle): androidx.compose.runtime.State<AnimeTitle> {
        return produceState(initialValue = initialAnime) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .filterNotNull()
                .collectLatest { anime ->
                    value = anime
                }
        }
    }

    private fun getEnabledSources(): List<AnimeCatalogueSource> {
        return animeSourceManager.getCatalogueSources()
            .filter { it.lang in enabledLanguages }
            .filterNot { "${it.id}" in disabledSources }
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState.toggle()
    }

    fun search() {
        val query = state.value.searchQuery?.takeIf { it.isNotBlank() } ?: return
        val sourceFilter = state.value.sourceFilter

        val sameQuery = lastQuery == query
        if (sameQuery && lastSourceFilter == sourceFilter) return

        lastQuery = query
        lastSourceFilter = sourceFilter

        searchJob?.cancel()

        val sources = getEnabledSources()

        if (sameQuery) {
            val existingResults = state.value.items
            updateItems(
                sources.associateWith { existingResults[it] ?: SearchItemResult.Loading }.toPersistentMap(),
            )
        } else {
            updateItems(
                sources.associateWith { SearchItemResult.Loading }.toPersistentMap(),
            )
        }

        searchJob = ioCoroutineScope.launch {
            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }

                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getSearchAnime(1, query, source.getFilterList())
                        }

                        val titles = page.animes
                            .map { it.toDomainAnime(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalAnime(it) }

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()
        }
    }

    private fun updateItems(items: PersistentMap<AnimeCatalogueSource, SearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: AnimeCatalogueSource, result: SearchItemResult) {
        val newItems = state.value.items.mutate { items ->
            items[source] = result
        }
        updateItems(newItems)
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter = SourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<AnimeCatalogueSource, SearchItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems: Map<AnimeCatalogueSource, SearchItemResult> =
            items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class SourceFilter {
    All,
    PinnedOnly,
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<AnimeTitle>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !isEmpty)
    }
}
