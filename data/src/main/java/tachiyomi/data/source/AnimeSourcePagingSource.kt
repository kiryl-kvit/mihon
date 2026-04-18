package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import mihon.domain.anime.model.toDomainAnime
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.source.repository.AnimeSourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSourceSearchPagingSource(
    source: AnimeCatalogueSource,
    private val query: String,
    private val filters: FilterList,
) : BaseAnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getSearchAnime(currentPage, query, filters)
    }
}

class AnimeSourcePopularPagingSource(source: AnimeCatalogueSource) : BaseAnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class AnimeSourceLatestPagingSource(source: AnimeCatalogueSource) : BaseAnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseAnimeSourcePagingSource(
    protected val source: AnimeCatalogueSource,
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
) : AnimeSourcePagingSource() {

    private val seenAnimeUrls = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, AnimeTitle> {
        val page = params.key ?: 1

        return try {
            val animePage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.animes.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            val animes = animePage.animes
                .map { it.toDomainAnime(source.id) }
                .filter { seenAnimeUrls.add(it.url) }
                .let { networkToLocalAnime(it) }

            LoadResult.Page(
                data = animes,
                prevKey = null,
                nextKey = if (animePage.hasNextPage) page + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, AnimeTitle>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}
