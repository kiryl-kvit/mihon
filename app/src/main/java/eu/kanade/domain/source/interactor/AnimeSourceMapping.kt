package eu.kanade.domain.source.interactor

import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source

internal fun AnimeCatalogueSource.isEnabled(enabledLanguages: Set<String>): Boolean {
    return lang in enabledLanguages
}

internal fun AnimeCatalogueSource.toDomainSource(pin: Pins = Pins.unpinned): Source {
    return Source(
        id = id,
        lang = lang,
        name = name,
        supportsLatest = supportsLatest,
        isStub = false,
        pin = pin,
    )
}
