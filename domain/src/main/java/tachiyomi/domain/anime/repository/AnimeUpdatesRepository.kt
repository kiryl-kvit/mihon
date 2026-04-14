package tachiyomi.domain.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.AnimeUpdatesWithRelations

interface AnimeUpdatesRepository {

    suspend fun awaitWithWatched(watched: Boolean, after: Long, limit: Long): List<AnimeUpdatesWithRelations>

    fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
    ): Flow<List<AnimeUpdatesWithRelations>>

    fun subscribeWithWatched(watched: Boolean, after: Long, limit: Long): Flow<List<AnimeUpdatesWithRelations>>
}
