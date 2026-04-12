package tachiyomi.data.source

import eu.kanade.tachiyomi.source.VideoCatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import tachiyomi.domain.source.repository.VideoSourcePagingSource
import tachiyomi.domain.source.repository.VideoSourceRepository
import tachiyomi.domain.source.service.VideoSourceManager

class VideoSourceRepositoryImpl(
    private val videoSourceManager: VideoSourceManager,
) : VideoSourceRepository {

    override fun search(
        sourceId: Long,
        query: String,
        filterList: FilterList,
    ): VideoSourcePagingSource {
        val source = videoSourceManager.get(sourceId) as VideoCatalogueSource
        return VideoSourceSearchPagingSource(source, query, filterList)
    }

    override fun getPopular(sourceId: Long): VideoSourcePagingSource {
        val source = videoSourceManager.get(sourceId) as VideoCatalogueSource
        return VideoSourcePopularPagingSource(source)
    }

    override fun getLatest(sourceId: Long): VideoSourcePagingSource {
        val source = videoSourceManager.get(sourceId) as VideoCatalogueSource
        return VideoSourceLatestPagingSource(source)
    }
}
