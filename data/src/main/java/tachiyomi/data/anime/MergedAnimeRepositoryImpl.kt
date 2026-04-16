package tachiyomi.data.anime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.repository.MergedAnimeRepository

class MergedAnimeRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : MergedAnimeRepository {

    override suspend fun getAll(): List<AnimeMerge> {
        return handler.awaitList {
            merged_animesQueries.getAll(profileProvider.activeProfileId, ::mapMerge)
        }
    }

    override fun subscribeAll(): Flow<List<AnimeMerge>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                merged_animesQueries.getAll(profileId, ::mapMerge)
            }
        }
    }

    override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> {
        return handler.awaitList {
            merged_animesQueries.getEntriesByAnimeId(profileProvider.activeProfileId, animeId, ::mapMerge)
        }
    }

    override fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                merged_animesQueries.getEntriesByAnimeId(profileId, animeId, ::mapMerge)
            }
        }
    }

    override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> {
        return handler.awaitList {
            merged_animesQueries.getEntriesByTargetId(profileProvider.activeProfileId, targetAnimeId, ::mapMerge)
        }
    }

    override suspend fun getTargetId(animeId: Long): Long? {
        return handler.awaitOneOrNull {
            merged_animesQueries.getTargetIdByAnimeId(profileProvider.activeProfileId, animeId)
        }
    }

    override fun subscribeTargetId(animeId: Long): Flow<Long?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                merged_animesQueries.getTargetIdByAnimeId(profileId, animeId)
            }
        }
    }

    override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) {
        require(targetAnimeId in orderedAnimeIds) { "Target anime must be in orderedAnimeIds" }
        require(orderedAnimeIds.distinct().size == orderedAnimeIds.size) { "Duplicate anime ids in merge group" }

        handler.await(inTransaction = true) {
            val profileId = profileProvider.activeProfileId
            orderedAnimeIds.forEach { animeId ->
                merged_animesQueries.deleteByAnimeId(profileId, animeId)
            }
            merged_animesQueries.deleteByTargetId(profileId, targetAnimeId)
            orderedAnimeIds.forEachIndexed { index, animeId ->
                merged_animesQueries.insert(profileId, targetAnimeId, animeId, index.toLong())
            }
        }
    }

    override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) {
        if (animeIds.isEmpty()) return

        handler.await(inTransaction = true) {
            val profileId = profileProvider.activeProfileId
            val existing = merged_animesQueries.getEntriesByTargetId(profileId, targetAnimeId, ::mapMerge)
                .executeAsList()
            if (existing.isEmpty()) return@await

            val remainingIds = existing.map { it.animeId }.filterNot { it in animeIds }
            if (remainingIds.size <= 1) {
                merged_animesQueries.deleteByTargetId(profileId, targetAnimeId)
                return@await
            }

            val newTargetId = remainingIds.firstOrNull { it == targetAnimeId } ?: remainingIds.first()
            merged_animesQueries.deleteByTargetId(profileId, targetAnimeId)
            remainingIds.forEachIndexed { index, animeId ->
                merged_animesQueries.insert(profileId, newTargetId, animeId, index.toLong())
            }
        }
    }

    override suspend fun deleteGroup(targetAnimeId: Long) {
        handler.await {
            merged_animesQueries.deleteByTargetId(profileProvider.activeProfileId, targetAnimeId)
        }
    }

    private fun mapMerge(targetAnimeId: Long, animeId: Long, position: Long): AnimeMerge {
        return AnimeMerge(targetId = targetAnimeId, animeId = animeId, position = position)
    }
}
