package tachiyomi.data.anime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AnimePlaybackStateRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : AnimePlaybackStateRepository {

    override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? {
        return handler.awaitOneOrNull {
            anime_playback_stateQueries.getByEpisodeId(
                profileProvider.activeProfileId,
                episodeId,
                AnimePlaybackStateMapper::mapState,
            )
        }
    }

    override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<AnimePlaybackState?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                anime_playback_stateQueries.getByEpisodeId(
                    profileId,
                    episodeId,
                    AnimePlaybackStateMapper::mapState,
                )
            }
        }
    }

    override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<AnimePlaybackState>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                anime_playback_stateQueries.getByAnimeId(
                    profileId = profileId,
                    animeId = animeId,
                    mapper = AnimePlaybackStateMapper::mapState,
                )
            }
        }
    }

    override suspend fun upsert(state: AnimePlaybackState) {
        handler.await(inTransaction = true) {
            anime_playback_stateQueries.upsertUpdate(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
            )
            anime_playback_stateQueries.upsertInsert(
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
            )
        }
    }

    override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) {
        handler.await(inTransaction = true) {
            anime_playback_stateQueries.upsertUpdate(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
            )
            anime_playback_stateQueries.upsertInsert(
                profileId = profileProvider.activeProfileId,
                episodeId = state.episodeId,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                completed = state.completed,
                lastWatchedAt = state.lastWatchedAt,
            )
            anime_episodesQueries.update(
                animeId = null,
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
