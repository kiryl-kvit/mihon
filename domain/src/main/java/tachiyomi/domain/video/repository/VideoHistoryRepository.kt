package tachiyomi.domain.video.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.video.model.VideoHistory
import tachiyomi.domain.video.model.VideoHistoryUpdate
import tachiyomi.domain.video.model.VideoHistoryWithRelations

interface VideoHistoryRepository {

    fun getHistory(query: String): Flow<List<VideoHistoryWithRelations>>

    suspend fun getLastHistory(): VideoHistoryWithRelations?

    fun getLastHistoryAsFlow(): Flow<VideoHistoryWithRelations?>

    suspend fun getTotalWatchedDuration(): Long

    suspend fun getHistoryByVideoId(videoId: Long): List<VideoHistory>

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByVideoId(videoId: Long)

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(historyUpdate: VideoHistoryUpdate)
}
