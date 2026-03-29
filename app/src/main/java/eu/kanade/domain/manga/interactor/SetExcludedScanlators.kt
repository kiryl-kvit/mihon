package eu.kanade.domain.manga.interactor

import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler

class SetExcludedScanlators(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) {

    suspend fun await(mangaId: Long, excludedScanlators: Set<String>) {
        handler.await(inTransaction = true) {
            val currentExcluded = handler.awaitList {
                excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(profileProvider.activeProfileId, mangaId)
            }.toSet()
            val toAdd = excludedScanlators.minus(currentExcluded)
            for (scanlator in toAdd) {
                excluded_scanlatorsQueries.insert(profileProvider.activeProfileId, mangaId, scanlator)
            }
            val toRemove = currentExcluded.minus(excludedScanlators)
            excluded_scanlatorsQueries.remove(profileProvider.activeProfileId, mangaId, toRemove)
        }
    }
}
