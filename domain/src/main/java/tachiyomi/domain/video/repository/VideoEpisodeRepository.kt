package tachiyomi.domain.video.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoEpisodeUpdate

interface VideoEpisodeRepository {

    suspend fun addAll(episodes: List<VideoEpisode>): List<VideoEpisode>

    suspend fun update(episodeUpdate: VideoEpisodeUpdate)

    suspend fun updateAll(episodeUpdates: List<VideoEpisodeUpdate>)

    suspend fun removeEpisodesWithIds(episodeIds: List<Long>)

    suspend fun getEpisodesByVideoId(videoId: Long): List<VideoEpisode>

    fun getEpisodesByVideoIdAsFlow(videoId: Long): Flow<List<VideoEpisode>>

    fun getEpisodesByVideoIdsAsFlow(videoIds: List<Long>): Flow<List<VideoEpisode>>

    suspend fun getEpisodeById(id: Long): VideoEpisode?

    suspend fun getEpisodeByUrlAndVideoId(url: String, videoId: Long): VideoEpisode?
}
