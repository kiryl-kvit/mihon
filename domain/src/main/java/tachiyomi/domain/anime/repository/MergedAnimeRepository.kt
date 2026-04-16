package tachiyomi.domain.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.AnimeMerge

interface MergedAnimeRepository {

    suspend fun getAll(): List<AnimeMerge>

    fun subscribeAll(): Flow<List<AnimeMerge>>

    suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge>

    fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>>

    suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge>

    suspend fun getTargetId(animeId: Long): Long?

    fun subscribeTargetId(animeId: Long): Flow<Long?>

    suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>)

    suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>)

    suspend fun deleteGroup(targetAnimeId: Long)
}
