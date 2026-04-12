package tachiyomi.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.video.model.VideoHistory
import tachiyomi.domain.video.model.VideoHistoryUpdate
import tachiyomi.domain.video.model.VideoHistoryWithRelations
import tachiyomi.domain.video.repository.VideoHistoryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class VideoHistoryRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : VideoHistoryRepository {

    override fun getHistory(query: String): Flow<List<VideoHistoryWithRelations>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                video_historyQueries.getHistory(profileId, query, VideoHistoryMapper::mapHistoryWithRelations)
            }
        }
    }

    override suspend fun getLastHistory(): VideoHistoryWithRelations? {
        return handler.awaitOneOrNull {
            video_historyQueries.getLatestHistory(profileProvider.activeProfileId, VideoHistoryMapper::mapHistoryWithRelations)
        }
    }

    override fun getLastHistoryAsFlow(): Flow<VideoHistoryWithRelations?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                video_historyQueries.getLatestHistory(profileId, VideoHistoryMapper::mapHistoryWithRelations)
            }
        }
    }

    override suspend fun getTotalWatchedDuration(): Long {
        return handler.awaitOne { video_historyQueries.getWatchedDuration(profileProvider.activeProfileId) }
    }

    override suspend fun getHistoryByVideoId(videoId: Long): List<VideoHistory> {
        return handler.awaitList {
            video_historyQueries.getHistoryByVideoId(profileProvider.activeProfileId, videoId, VideoHistoryMapper::mapHistory)
        }
    }

    override suspend fun resetHistory(historyId: Long) {
        try {
            handler.await { video_historyQueries.resetHistoryById(profileProvider.activeProfileId, historyId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByVideoId(videoId: Long) {
        try {
            handler.await { video_historyQueries.resetHistoryByVideoId(profileProvider.activeProfileId, videoId) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            handler.await { video_historyQueries.removeAllHistory(profileProvider.activeProfileId) }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: VideoHistoryUpdate) {
        try {
            handler.await(inTransaction = true) {
                video_historyQueries.upsertUpdate(
                    watchedAt = historyUpdate.watchedAt,
                    timeWatched = historyUpdate.sessionWatchedDuration,
                    profileId = profileProvider.activeProfileId,
                    episodeId = historyUpdate.episodeId,
                )
                video_historyQueries.upsertInsert(
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
