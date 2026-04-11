package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.VideosPage

interface VideoCatalogueSource : VideoSource {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Get a page with a list of video titles.
     *
     * @param page the page number to retrieve.
     */
    suspend fun getPopularVideos(page: Int): VideosPage

    /**
     * Get a page with a list of video titles.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    suspend fun getSearchVideos(page: Int, query: String, filters: FilterList): VideosPage

    /**
     * Get a page with a list of latest video title updates.
     *
     * @param page the page number to retrieve.
     */
    suspend fun getLatestUpdates(page: Int): VideosPage

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList
}
