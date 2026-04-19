package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Optional anime-catalogue capability for sources whose filter metadata must be loaded asynchronously.
 */
interface AsyncAnimeCatalogueFilterSource : AnimeCatalogueSource {
    suspend fun getFilterListAsync(): FilterList
}
