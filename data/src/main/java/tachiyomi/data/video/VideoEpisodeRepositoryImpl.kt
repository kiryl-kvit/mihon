package tachiyomi.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoEpisodeUpdate
import tachiyomi.domain.video.repository.VideoEpisodeRepository

@OptIn(ExperimentalCoroutinesApi::class)
class VideoEpisodeRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : VideoEpisodeRepository {

    override suspend fun addAll(episodes: List<VideoEpisode>): List<VideoEpisode> {
        return try {
            handler.await(inTransaction = true) {
                episodes.map { episode ->
                    video_episodesQueries.insert(
                        profileId = profileProvider.activeProfileId,
                        videoId = episode.videoId,
                        url = episode.url,
                        name = episode.name,
                        watched = episode.watched,
                        completed = episode.completed,
                        episodeNumber = episode.episodeNumber,
                        sourceOrder = episode.sourceOrder,
                        dateFetch = episode.dateFetch,
                        dateUpload = episode.dateUpload,
                        version = episode.version,
                    )
                    val lastInsertId = video_episodesQueries.selectLastInsertedRowId().executeAsOne()
                    episode.copy(id = lastInsertId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(episodeUpdate: VideoEpisodeUpdate) {
        partialUpdate(episodeUpdate)
    }

    override suspend fun updateAll(episodeUpdates: List<VideoEpisodeUpdate>) {
        partialUpdate(*episodeUpdates.toTypedArray())
    }

    override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) {
        try {
            handler.await { video_episodesQueries.removeEpisodesWithIds(profileProvider.activeProfileId, episodeIds) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getEpisodesByVideoId(videoId: Long): List<VideoEpisode> {
        return handler.awaitList {
            video_episodesQueries.getEpisodesByVideoId(
                profileProvider.activeProfileId,
                videoId,
                VideoEpisodeMapper::mapEpisode,
            )
        }
    }

    override fun getEpisodesByVideoIdAsFlow(videoId: Long): Flow<List<VideoEpisode>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                video_episodesQueries.getEpisodesByVideoId(
                    profileId,
                    videoId,
                    VideoEpisodeMapper::mapEpisode,
                )
            }
        }
    }

    override fun getEpisodesByVideoIdsAsFlow(videoIds: List<Long>): Flow<List<VideoEpisode>> {
        if (videoIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())

        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                video_episodesQueries.getEpisodesByVideoIds(
                    profileId = profileId,
                    videoIds = videoIds,
                    mapper = VideoEpisodeMapper::mapEpisode,
                )
            }
        }
    }

    override suspend fun getEpisodeById(id: Long): VideoEpisode? {
        return handler.awaitOneOrNull {
            video_episodesQueries.getEpisodeById(id, profileProvider.activeProfileId, VideoEpisodeMapper::mapEpisode)
        }
    }

    override suspend fun getEpisodeByUrlAndVideoId(url: String, videoId: Long): VideoEpisode? {
        return handler.awaitOneOrNull {
            video_episodesQueries.getEpisodeByUrlAndVideoId(
                profileProvider.activeProfileId,
                url,
                videoId,
                VideoEpisodeMapper::mapEpisode,
            )
        }
    }

    private suspend fun partialUpdate(vararg episodeUpdates: VideoEpisodeUpdate) {
        handler.await(inTransaction = true) {
            episodeUpdates.forEach { episodeUpdate ->
                video_episodesQueries.update(
                    videoId = episodeUpdate.videoId,
                    url = episodeUpdate.url,
                    name = episodeUpdate.name,
                    watched = episodeUpdate.watched,
                    completed = episodeUpdate.completed,
                    episodeNumber = episodeUpdate.episodeNumber,
                    sourceOrder = episodeUpdate.sourceOrder,
                    dateFetch = episodeUpdate.dateFetch,
                    dateUpload = episodeUpdate.dateUpload,
                    episodeId = episodeUpdate.id,
                    version = episodeUpdate.version,
                    profileId = profileProvider.activeProfileId,
                )
            }
        }
    }
}
