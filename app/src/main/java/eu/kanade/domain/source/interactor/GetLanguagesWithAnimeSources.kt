package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.AnimeSourceManager
import java.util.SortedMap

class GetLanguagesWithAnimeSources(
    private val sourceManager: AnimeSourceManager,
    private val preferences: SourcePreferences,
) {

    fun subscribe(): Flow<SortedMap<String, List<Source>>> {
        return combine(
            preferences.enabledLanguages.changes(),
            preferences.disabledAnimeSources.changes(),
            sourceManager.catalogueSources,
        ) { enabledLanguages, disabledSources, sources ->
            val sortedSources = sources
                .map { it.toDomainSource() }
                .sortedWith(
                    compareBy<Source> { it.id.toString() in disabledSources }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )

            sortedSources
                .groupBy { it.lang }
                .toSortedMap(
                    compareBy<String> { it !in enabledLanguages }.then(LocaleHelper.comparator),
                )
        }
    }
}
