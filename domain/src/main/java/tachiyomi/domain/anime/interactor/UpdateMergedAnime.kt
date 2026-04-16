package tachiyomi.domain.anime.interactor

import tachiyomi.domain.anime.repository.MergedAnimeRepository

class UpdateMergedAnime(
    private val repository: MergedAnimeRepository,
) {

    suspend fun awaitMerge(targetAnimeId: Long, orderedAnimeIds: List<Long>) {
        repository.upsertGroup(targetAnimeId, orderedAnimeIds)
    }

    suspend fun awaitRemoveMembers(targetAnimeId: Long, animeIds: List<Long>) {
        repository.removeMembers(targetAnimeId, animeIds)
    }

    suspend fun awaitDeleteGroup(targetAnimeId: Long) {
        repository.deleteGroup(targetAnimeId)
    }
}
