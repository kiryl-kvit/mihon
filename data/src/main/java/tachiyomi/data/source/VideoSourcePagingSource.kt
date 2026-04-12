package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.VideoCatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.VideosPage
import mihon.domain.video.model.toDomainVideo
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.repository.VideoSourcePagingSource
import tachiyomi.domain.video.interactor.NetworkToLocalVideo
import tachiyomi.domain.video.model.VideoTitle
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VideoSourceSearchPagingSource(
    source: VideoCatalogueSource,
    private val query: String,
    private val filters: FilterList,
) : BaseVideoSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): VideosPage {
        return source.getSearchVideos(currentPage, query, filters)
    }
}

class VideoSourcePopularPagingSource(source: VideoCatalogueSource) : BaseVideoSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): VideosPage {
        return source.getPopularVideos(currentPage)
    }
}

class VideoSourceLatestPagingSource(source: VideoCatalogueSource) : BaseVideoSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): VideosPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseVideoSourcePagingSource(
    protected val source: VideoCatalogueSource,
    private val networkToLocalVideo: NetworkToLocalVideo = Injekt.get(),
) : VideoSourcePagingSource() {

    private val seenVideos = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): VideosPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, VideoTitle> {
        val page = params.key ?: 1

        return try {
            val videosPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.videos.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            val videos = videosPage.videos
                .map { it.toDomainVideo(source.id) }
                .filter { seenVideos.add(it.url) }
                .let { networkToLocalVideo(it) }

            LoadResult.Page(
                data = videos,
                prevKey = null,
                nextKey = if (videosPage.hasNextPage) page + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, VideoTitle>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
