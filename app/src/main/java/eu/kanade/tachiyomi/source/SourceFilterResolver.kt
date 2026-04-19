package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList

suspend fun CatalogueSource.resolveFilterList(): FilterList {
    return when (this) {
        is AsyncCatalogueFilterSource -> getFilterListAsync()
        else -> getFilterList()
    }
}

fun CatalogueSource.defaultBackgroundFilterList(): FilterList {
    return when (this) {
        is AsyncCatalogueFilterSource -> FilterList()
        else -> getFilterList()
    }
}

suspend fun AnimeCatalogueSource.resolveFilterList(): FilterList {
    return when (this) {
        is AsyncAnimeCatalogueFilterSource -> getFilterListAsync()
        else -> getFilterList()
    }
}

fun AnimeCatalogueSource.defaultBackgroundFilterList(): FilterList {
    return when (this) {
        is AsyncAnimeCatalogueFilterSource -> FilterList()
        else -> getFilterList()
    }
}
