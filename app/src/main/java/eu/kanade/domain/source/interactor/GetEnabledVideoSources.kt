package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.VideoCatalogueSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Pins
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.VideoSourceManager

class GetEnabledVideoSources(
    private val videoSourceManager: VideoSourceManager,
    private val preferences: SourcePreferences,
) {

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedVideoSources.changes(),
            preferences.enabledLanguages.changes(),
            preferences.disabledVideoSources.changes(),
            preferences.lastUsedVideoSource.changes(),
            videoSourceManager.catalogueSources,
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, sources ->
            sources
                .filter { it.isEnabled(enabledLanguages) }
                .filterNot { it.id.toString() in disabledSources }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.toDomainSource(pin = flag)
                    val toFlatten = mutableListOf(source)
                    if (source.id == lastUsedSource) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }

    private fun VideoCatalogueSource.isEnabled(enabledLanguages: Set<String>): Boolean {
        return lang in enabledLanguages || "all" in enabledLanguages
    }

    private fun VideoCatalogueSource.toDomainSource(pin: Pins): Source {
        return Source(
            id = id,
            lang = lang,
            name = name,
            supportsLatest = supportsLatest,
            isStub = false,
            pin = pin,
        )
    }
}
