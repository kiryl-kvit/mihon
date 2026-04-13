package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SAnime

/**
 * Optional interface for anime sources that can expose a canonical details page URL.
 */
interface AnimeWebViewSource : AnimeSource {

    fun getAnimeUrl(anime: SAnime): String

    fun getWebViewHeaders(): Map<String, String> = emptyMap()
}
