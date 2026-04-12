package tachiyomi.domain.video.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.model.VideoTitleUpdate

interface VideoRepository {

    suspend fun getVideoById(id: Long): VideoTitle

    suspend fun getVideoByIdAsFlow(id: Long): Flow<VideoTitle>

    suspend fun getVideoByUrlAndSourceId(url: String, sourceId: Long): VideoTitle?

    fun getVideoByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<VideoTitle?>

    suspend fun getFavorites(): List<VideoTitle>

    fun getFavoritesAsFlow(): Flow<List<VideoTitle>>

    suspend fun getAllVideosByProfile(profileId: Long): List<VideoTitle>

    suspend fun update(update: VideoTitleUpdate): Boolean

    suspend fun updateAll(videoUpdates: List<VideoTitleUpdate>): Boolean

    suspend fun insertNetworkVideo(videos: List<VideoTitle>): List<VideoTitle>

    suspend fun setVideoCategories(videoId: Long, categoryIds: List<Long>)
}
