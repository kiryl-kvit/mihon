package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.repository.AnimeSourcePagingSource
import tachiyomi.domain.source.repository.AnimeSourceRepository

class GetRemoteAnimeTest {

    @Test
    fun `popular query routes to popular paging source`() {
        val popular = FakeAnimeSourcePagingSource()
        val repository = FakeAnimeSourceRepository(popular = popular)

        val result = GetRemoteAnime(repository)(1L, GetRemoteAnime.QUERY_POPULAR, FilterList())

        result shouldBe popular
    }

    @Test
    fun `latest query routes to latest paging source`() {
        val latest = FakeAnimeSourcePagingSource()
        val repository = FakeAnimeSourceRepository(latest = latest)

        val result = GetRemoteAnime(repository)(1L, GetRemoteAnime.QUERY_LATEST, FilterList())

        result shouldBe latest
    }

    @Test
    fun `custom query routes to search paging source`() {
        val search = FakeAnimeSourcePagingSource()
        val repository = FakeAnimeSourceRepository(search = search)

        val result = GetRemoteAnime(repository)(1L, "query", FilterList())

        result shouldBe search
    }
}

private class FakeAnimeSourceRepository(
    private val search: AnimeSourcePagingSource = FakeAnimeSourcePagingSource(),
    private val popular: AnimeSourcePagingSource = FakeAnimeSourcePagingSource(),
    private val latest: AnimeSourcePagingSource = FakeAnimeSourcePagingSource(),
) : AnimeSourceRepository {
    override fun search(sourceId: Long, query: String, filterList: FilterList): AnimeSourcePagingSource = search

    override fun getPopular(sourceId: Long): AnimeSourcePagingSource = popular

    override fun getLatest(sourceId: Long): AnimeSourcePagingSource = latest
}

private class FakeAnimeSourcePagingSource : AnimeSourcePagingSource() {
    override fun getRefreshKey(
        state: androidx.paging.PagingState<Long, tachiyomi.domain.anime.model.AnimeTitle>,
    ): Long? = null

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, tachiyomi.domain.anime.model.AnimeTitle> {
        error("Not used")
    }
}
