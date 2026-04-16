package tachiyomi.domain.anime.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.repository.MergedAnimeRepository

class GetMergedAnime(
    private val repository: MergedAnimeRepository,
) {

    suspend fun awaitAll(): List<AnimeMerge> {
        return repository.getAll()
    }

    fun subscribeAll(): Flow<List<AnimeMerge>> {
        return repository.subscribeAll()
    }

    suspend fun awaitGroupByAnimeId(animeId: Long): List<AnimeMerge> {
        return repository.getGroupByAnimeId(animeId)
    }

    fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> {
        return repository.subscribeGroupByAnimeId(animeId)
    }

    suspend fun awaitGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> {
        return repository.getGroupByTargetId(targetAnimeId)
    }

    suspend fun awaitTargetId(animeId: Long): Long? {
        return repository.getTargetId(animeId)
    }

    fun subscribeTargetId(animeId: Long): Flow<Long?> {
        return repository.subscribeTargetId(animeId)
    }

    suspend fun awaitVisibleTargetId(animeId: Long): Long {
        return repository.getTargetId(animeId) ?: animeId
    }
}
