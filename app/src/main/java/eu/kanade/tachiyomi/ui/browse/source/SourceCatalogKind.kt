package eu.kanade.tachiyomi.ui.browse.source

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.domain.source.interactor.SourceListListing
import eu.kanade.tachiyomi.ui.anime.browse.AnimeBrowseSourceScreen
import eu.kanade.tachiyomi.ui.anime.browse.globalsearch.AnimeGlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.interactor.GetRemoteManga

enum class SourceCatalogKind {
    MANGA,
    ANIME,
    ;

    fun globalSearchScreen(): Screen {
        return when (this) {
            MANGA -> GlobalSearchScreen()
            ANIME -> AnimeGlobalSearchScreen()
        }
    }

    fun browseSourceScreen(sourceId: Long, listingQuery: String): Screen {
        return when (this) {
            MANGA -> BrowseSourceScreen(sourceId, listingQuery)
            ANIME -> AnimeBrowseSourceScreen(sourceId, listingQuery)
        }
    }

    fun listingQuery(listing: SourceListListing): String {
        return when (this) {
            MANGA -> when (listing) {
                SourceListListing.Popular -> GetRemoteManga.QUERY_POPULAR
                SourceListListing.Latest -> GetRemoteManga.QUERY_LATEST
            }
            ANIME -> when (listing) {
                SourceListListing.Popular -> GetRemoteAnime.QUERY_POPULAR
                SourceListListing.Latest -> GetRemoteAnime.QUERY_LATEST
            }
        }
    }
}
