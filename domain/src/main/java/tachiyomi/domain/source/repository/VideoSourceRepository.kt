package tachiyomi.domain.source.repository

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.model.FilterList
import tachiyomi.domain.video.model.VideoTitle

typealias VideoSourcePagingSource = PagingSource<Long, VideoTitle>

interface VideoSourceRepository {

    fun search(sourceId: Long, query: String, filterList: FilterList): VideoSourcePagingSource

    fun getPopular(sourceId: Long): VideoSourcePagingSource

    fun getLatest(sourceId: Long): VideoSourcePagingSource
}
