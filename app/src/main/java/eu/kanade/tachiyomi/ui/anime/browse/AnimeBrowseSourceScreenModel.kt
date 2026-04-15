package eu.kanade.tachiyomi.ui.anime.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.anime.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.AnimeTitle
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    animeSourceManager: AnimeSourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getRemoteAnime: GetRemoteAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
) : StateScreenModel<AnimeBrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource

    init {
        source?.let { animeSource ->
            mutableState.update {
                it.copy(filters = animeSource.getFilterList())
            }

            if (!getIncognitoState.await(animeSource.id)) {
                sourcePreferences.lastUsedAnimeSource.set(animeSource.id)
            }
        }
    }

    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val animePagerFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteAnime(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { anime ->
                    getAnime.subscribe(anime.url, anime.source)
                        .map { it ?: anime }
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(ioCoroutineScope)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        val animeSource = source ?: return
        mutableState.update { it.copy(filters = animeSource.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source == null) return
        mutableState.update { it.copy(filters = filters) }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val animeSource = source ?: return
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = animeSource.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun openFilterSheet() {
        mutableState.update { it.copy(dialog = Dialog.Filter) }
    }

    fun onAnimeLibraryAction(anime: AnimeTitle) {
        screenModelScope.launchIO {
            handleAnimeLibraryAction(anime)
        }
    }

    suspend fun onAnimeLongClick(anime: AnimeTitle): Boolean {
        handleAnimeLibraryAction(anime)
        return true
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun addFavorite(anime: AnimeTitle) {
        screenModelScope.launchIO {
            addFavoriteInternal(anime)
        }
    }

    fun changeAnimeFavorite(anime: AnimeTitle) {
        screenModelScope.launchIO {
            val favorite = !anime.favorite
            if (favorite) {
                setAnimeDefaultEpisodeFlags.await(anime)
            }
            animeRepository.update(
                AnimeTitleUpdate(
                    id = anime.id,
                    favorite = favorite,
                    dateAdded = if (favorite) {
                        System.currentTimeMillis()
                    } else {
                        0L
                    },
                ),
            )
        }
    }

    fun moveAnimeToCategories(anime: AnimeTitle, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(anime.id, categoryIds)
        }
    }

    private suspend fun handleAnimeLibraryAction(anime: AnimeTitle) {
        if (anime.favorite) {
            setDialog(Dialog.RemoveAnime(anime))
        } else {
            addFavoriteInternal(anime)
        }
    }

    private suspend fun addFavoriteInternal(anime: AnimeTitle) {
        val categories = getCategories()
        val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
        val defaultCategory = categories.find { it.id == defaultCategoryId }

        when {
            defaultCategory != null -> {
                setAnimeCategories.await(anime.id, listOf(defaultCategory.id))
                updateFavorite(anime, true)
            }
            defaultCategoryId == 0L || categories.isEmpty() -> {
                setAnimeCategories.await(anime.id, emptyList())
                updateFavorite(anime, true)
            }
            else -> {
                val preselectedIds = getAnimeCategories.await(anime.id).map { it.id }
                setDialog(
                    Dialog.ChangeAnimeCategory(
                        anime = anime,
                        initialSelection = categories
                            .mapAsCheckboxState { it.id in preselectedIds }
                            .toImmutableList(),
                    ),
                )
            }
        }
    }

    private suspend fun updateFavorite(anime: AnimeTitle, favorite: Boolean) {
        if (favorite) {
            setAnimeDefaultEpisodeFlags.await(anime)
        }
        animeRepository.update(
            AnimeTitleUpdate(
                id = anime.id,
                favorite = favorite,
                dateAdded = if (favorite) {
                    System.currentTimeMillis()
                } else {
                    0L
                },
            ),
        )
    }

    private suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    private fun moveAnimeToCategories(anime: AnimeTitle, vararg categories: Category) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    private fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteAnime.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteAnime.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteAnime.QUERY_POPULAR -> Popular
                    GetRemoteAnime.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList())
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog

        data class RemoveAnime(val anime: AnimeTitle) : Dialog

        data class ChangeAnimeCategory(
            val anime: AnimeTitle,
            val initialSelection: kotlinx.collections.immutable.ImmutableList<CheckboxState<Category>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}
