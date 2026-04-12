package tachiyomi.domain.video.interactor

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.repository.VideoRepository

class GetVideo(
    private val videoRepository: VideoRepository,
) {

    suspend fun await(id: Long): VideoTitle? {
        return try {
            videoRepository.getVideoById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    suspend fun subscribe(id: Long): Flow<VideoTitle> {
        return videoRepository.getVideoByIdAsFlow(id)
    }

    fun subscribe(url: String, sourceId: Long): Flow<VideoTitle?> {
        return videoRepository.getVideoByUrlAndSourceIdAsFlow(url, sourceId)
    }
}
