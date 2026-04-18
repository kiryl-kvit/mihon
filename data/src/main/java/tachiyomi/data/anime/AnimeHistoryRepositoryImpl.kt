package tachiyomi.data.anime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.anime.repository.AnimeHistoryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeHistoryRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : AnimeHistoryRepository {

    override fun getHistory(query: String): Flow<List<AnimeHistoryWithRelations>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                anime_historyQueries.getHistory(profileId, query, AnimeHistoryMapper::mapHistoryWithRelations)
            }
        }
    }

    override suspend fun getLastHistory(): AnimeHistoryWithRelations? {
        return handler.awaitOneOrNull {
            anime_historyQueries.getLatestHistory(
                profileProvider.activeProfileId,
                AnimeHistoryMapper::mapHistoryWithRelations,
            )
        }
    }

    override fun getLastHistoryAsFlow(): Flow<AnimeHistoryWithRelations?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                anime_historyQueries.getLatestHistory(profileId, AnimeHistoryMapper::mapHistoryWithRelations)
            }
        }
    }

    override suspend fun getTotalWatchedDuration(): Long {
        return handler.awaitOne { anime_historyQueries.getWatchedDuration(profileProvider.activeProfileId) }
    }

    override suspend fun getHistoryByAnimeId(animeId: Long): List<AnimeHistory> {
        return handler.awaitList {
            anime_historyQueries.getHistoryByAnimeId(
                profileProvider.activeProfileId,
                animeId,
                AnimeHistoryMapper::mapHistory,
            )
        }
    }

    override suspend fun resetHistory(historyId: Long) {
        try {
            handler.await { anime_historyQueries.resetHistoryById(profileProvider.activeProfileId, historyId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByAnimeId(animeId: Long) {
        try {
            handler.await { anime_historyQueries.resetHistoryByAnimeId(profileProvider.activeProfileId, animeId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            handler.await { anime_historyQueries.removeAllHistory(profileProvider.activeProfileId) }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: AnimeHistoryUpdate) {
        try {
            handler.await(inTransaction = true) {
                anime_historyQueries.upsertUpdate(
                    watchedAt = historyUpdate.watchedAt,
                    timeWatched = historyUpdate.sessionWatchedDuration,
                    profileId = profileProvider.activeProfileId,
                    episodeId = historyUpdate.episodeId,
                )
                anime_historyQueries.upsertInsert(
                    profileId = profileProvider.activeProfileId,
                    episodeId = historyUpdate.episodeId,
                    watchedAt = historyUpdate.watchedAt,
                    timeWatched = historyUpdate.sessionWatchedDuration,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
