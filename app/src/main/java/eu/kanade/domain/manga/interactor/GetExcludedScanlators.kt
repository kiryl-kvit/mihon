package eu.kanade.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler

class GetExcludedScanlators(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) {

    suspend fun await(mangaId: Long): Set<String> {
        return handler.awaitList {
            excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(profileProvider.activeProfileId, mangaId)
        }
            .toSet()
    }

    fun subscribe(mangaId: Long): Flow<Set<String>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(profileId, mangaId)
            }
        }
            .map { it.toSet() }
    }
}
