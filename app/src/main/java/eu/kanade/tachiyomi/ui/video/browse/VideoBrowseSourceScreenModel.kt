package eu.kanade.tachiyomi.ui.video.browse

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
import eu.kanade.tachiyomi.source.VideoCatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.interactor.GetRemoteVideo
import tachiyomi.domain.source.service.VideoSourceManager
import tachiyomi.domain.video.interactor.GetVideo
import tachiyomi.domain.video.model.VideoTitle
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VideoBrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    videoSourceManager: VideoSourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getRemoteVideo: GetRemoteVideo = Injekt.get(),
    private val getVideo: GetVideo = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
) : StateScreenModel<VideoBrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = videoSourceManager.get(sourceId) as? VideoCatalogueSource

    init {
        source?.let { videoSource ->
            mutableState.update {
                it.copy(filters = videoSource.getFilterList())
            }

            if (!getIncognitoState.await(videoSource.id)) {
                sourcePreferences.lastUsedVideoSource.set(videoSource.id)
            }
        }
    }

    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val videoPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteVideo(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { video ->
                    getVideo.subscribe(video.url, video.source)
                        .map { it ?: video }
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
        val videoSource = source ?: return
        mutableState.update { it.copy(filters = videoSource.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source == null) return
        mutableState.update { it.copy(filters = filters) }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val videoSource = source ?: return
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = videoSource.getFilterList())

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

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteVideo.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteVideo.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteVideo.QUERY_POPULAR -> Popular
                    GetRemoteVideo.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList())
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
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
