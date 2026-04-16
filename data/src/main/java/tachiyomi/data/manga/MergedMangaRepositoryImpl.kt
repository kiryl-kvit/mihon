package tachiyomi.data.manga

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.repository.MergedMangaRepository

class MergedMangaRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : MergedMangaRepository {

    override suspend fun getAll(): List<MangaMerge> {
        return handler.awaitList {
            merged_mangasQueries.getAll(profileProvider.activeProfileId, ::mapMerge)
        }
    }

    override fun subscribeAll(): Flow<List<MangaMerge>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                merged_mangasQueries.getAll(profileId, ::mapMerge)
            }
        }
    }

    override suspend fun getGroupByMangaId(mangaId: Long): List<MangaMerge> {
        return handler.awaitList {
            merged_mangasQueries.getEntriesByMangaId(profileProvider.activeProfileId, mangaId, ::mapMerge)
        }
    }

    override fun subscribeGroupByMangaId(mangaId: Long): Flow<List<MangaMerge>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                merged_mangasQueries.getEntriesByMangaId(profileId, mangaId, ::mapMerge)
            }
        }
    }

    override suspend fun getGroupByTargetId(targetMangaId: Long): List<MangaMerge> {
        return handler.awaitList {
            merged_mangasQueries.getEntriesByTargetId(profileProvider.activeProfileId, targetMangaId, ::mapMerge)
        }
    }

    override suspend fun getTargetId(mangaId: Long): Long? {
        return handler.awaitOneOrNull {
            merged_mangasQueries.getTargetIdByMangaId(profileProvider.activeProfileId, mangaId)
        }
    }

    override fun subscribeTargetId(mangaId: Long): Flow<Long?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                merged_mangasQueries.getTargetIdByMangaId(profileId, mangaId)
            }
        }
    }

    override suspend fun upsertGroup(targetMangaId: Long, orderedMangaIds: List<Long>) {
        require(targetMangaId in orderedMangaIds) { "Target manga must be in orderedMangaIds" }
        require(orderedMangaIds.distinct().size == orderedMangaIds.size) { "Duplicate manga ids in merge group" }

        handler.await(inTransaction = true) {
            val profileId = profileProvider.activeProfileId
            orderedMangaIds.forEach { mangaId ->
                merged_mangasQueries.deleteByMangaId(profileId, mangaId)
            }
            merged_mangasQueries.deleteByTargetId(profileId, targetMangaId)
            orderedMangaIds.forEachIndexed { index, mangaId ->
                merged_mangasQueries.insert(profileId, targetMangaId, mangaId, index.toLong())
            }
        }
    }

    override suspend fun removeMembers(targetMangaId: Long, mangaIds: List<Long>) {
        if (mangaIds.isEmpty()) return

        handler.await(inTransaction = true) {
            val profileId = profileProvider.activeProfileId
            val existing = merged_mangasQueries.getEntriesByTargetId(profileId, targetMangaId, ::mapMerge)
                .executeAsList()
            if (existing.isEmpty()) return@await

            val remainingIds = existing.map { it.mangaId }.filterNot { it in mangaIds }
            if (remainingIds.size <= 1) {
                merged_mangasQueries.deleteByTargetId(profileId, targetMangaId)
                return@await
            }

            val newTargetId = remainingIds.firstOrNull { it == targetMangaId } ?: remainingIds.first()
            merged_mangasQueries.deleteByTargetId(profileId, targetMangaId)
            remainingIds.forEachIndexed { index, mangaId ->
                merged_mangasQueries.insert(profileId, newTargetId, mangaId, index.toLong())
            }
        }
    }

    override suspend fun deleteGroup(targetMangaId: Long) {
        handler.await {
            merged_mangasQueries.deleteByTargetId(profileProvider.activeProfileId, targetMangaId)
        }
    }

    private fun mapMerge(targetMangaId: Long, mangaId: Long, position: Long): MangaMerge {
        return MangaMerge(targetId = targetMangaId, mangaId = mangaId, position = position)
    }
}
