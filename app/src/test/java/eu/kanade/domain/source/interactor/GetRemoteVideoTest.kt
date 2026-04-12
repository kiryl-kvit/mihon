package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.interactor.GetRemoteVideo
import tachiyomi.domain.source.repository.VideoSourcePagingSource
import tachiyomi.domain.source.repository.VideoSourceRepository

class GetRemoteVideoTest {

    @Test
    fun `popular query routes to popular paging source`() {
        val popular = FakeVideoSourcePagingSource()
        val repository = FakeVideoSourceRepository(popular = popular)

        val result = GetRemoteVideo(repository)(1L, GetRemoteVideo.QUERY_POPULAR, FilterList())

        result shouldBe popular
    }

    @Test
    fun `latest query routes to latest paging source`() {
        val latest = FakeVideoSourcePagingSource()
        val repository = FakeVideoSourceRepository(latest = latest)

        val result = GetRemoteVideo(repository)(1L, GetRemoteVideo.QUERY_LATEST, FilterList())

        result shouldBe latest
    }

    @Test
    fun `custom query routes to search paging source`() {
        val search = FakeVideoSourcePagingSource()
        val repository = FakeVideoSourceRepository(search = search)

        val result = GetRemoteVideo(repository)(1L, "query", FilterList())

        result shouldBe search
    }
}

private class FakeVideoSourceRepository(
    private val search: VideoSourcePagingSource = FakeVideoSourcePagingSource(),
    private val popular: VideoSourcePagingSource = FakeVideoSourcePagingSource(),
    private val latest: VideoSourcePagingSource = FakeVideoSourcePagingSource(),
) : VideoSourceRepository {
    override fun search(sourceId: Long, query: String, filterList: FilterList): VideoSourcePagingSource = search

    override fun getPopular(sourceId: Long): VideoSourcePagingSource = popular

    override fun getLatest(sourceId: Long): VideoSourcePagingSource = latest
}

private class FakeVideoSourcePagingSource : VideoSourcePagingSource() {
    override fun getRefreshKey(state: androidx.paging.PagingState<Long, tachiyomi.domain.video.model.VideoTitle>): Long? = null

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, tachiyomi.domain.video.model.VideoTitle> {
        error("Not used")
    }
}
