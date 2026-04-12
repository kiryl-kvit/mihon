package tachiyomi.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.video.model.VideoPlaybackState
import tachiyomi.domain.video.repository.VideoPlaybackStateRepository

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackStateRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : VideoPlaybackStateRepository {

    override suspend fun getByEpisodeId(episodeId: Long): VideoPlaybackState? {
        return handler.awaitOneOrNull {
            video_playback_stateQueries.getByEpisodeId(
                profileProvider.activeProfileId,
                episodeId,
                VideoPlaybackStateMapper::mapState,
            )
        }
    }

    override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<VideoPlaybackState?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                video_playback_stateQueries.getByEpisodeId(
                    profileId,
                    episodeId,
                    VideoPlaybackStateMapper::mapState,
                )
            }
        }
    }

    override fun getByVideoIdAsFlow(videoId: Long): Flow<List<VideoPlaybackState>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                video_playback_stateQueries.getByVideoId(
                    profileId = profileId,
                    videoId = videoId,
                    mapper = VideoPlaybackStateMapper::mapState,
                )
            }
        }
    }

    override suspend fun upsert(state: VideoPlaybackState) {
        handler.await(inTransaction = true) {
            video_playback_stateQueries.upsertUpdate(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
            )
            video_playback_stateQueries.upsertInsert(
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
            )
        }
    }

    override suspend fun upsertAndSyncEpisodeState(state: VideoPlaybackState) {
        handler.await(inTransaction = true) {
            video_playback_stateQueries.upsertUpdate(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
            )
            video_playback_stateQueries.upsertInsert(
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
            )
            video_episodesQueries.update(
                videoId = null,
                url = null,
                name = null,
                watched = true,
                completed = state.completed,
                episodeNumber = null,
                sourceOrder = null,
                dateFetch = null,
                dateUpload = null,
                episodeId = state.episodeId,
                version = null,
                profileId = profileProvider.activeProfileId,
            )
        }
    }
}
