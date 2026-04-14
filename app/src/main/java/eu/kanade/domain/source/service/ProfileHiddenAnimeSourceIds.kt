package eu.kanade.domain.source.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.source.service.HiddenAnimeSourceIds

class ProfileHiddenAnimeSourceIds(
    private val sourcePreferences: SourcePreferences,
) : HiddenAnimeSourceIds {

    override fun get(): Set<Long> {
        return sourcePreferences.disabledAnimeSources.get().mapNotNull(String::toLongOrNull).toSet()
    }

    override fun subscribe(): Flow<Set<Long>> {
        return sourcePreferences.disabledAnimeSources.changes()
            .map { hiddenSources -> hiddenSources.mapNotNull(String::toLongOrNull).toSet() }
    }
}
