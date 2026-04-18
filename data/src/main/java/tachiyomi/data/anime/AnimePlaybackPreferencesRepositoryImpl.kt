package tachiyomi.data.anime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AnimePlaybackPreferencesRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : AnimePlaybackPreferencesRepository {

    override suspend fun getByAnimeId(animeId: Long): AnimePlaybackPreferences? {
        return handler.awaitOneOrNull {
            anime_playback_preferencesQueries.getByAnimeId(
                profileProvider.activeProfileId,
                animeId,
                AnimePlaybackPreferencesMapper::mapPreferences,
            )
        }
    }

    override fun getByAnimeIdAsFlow(animeId: Long): Flow<AnimePlaybackPreferences?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                anime_playback_preferencesQueries.getByAnimeId(
                    profileId,
                    animeId,
                    AnimePlaybackPreferencesMapper::mapPreferences,
                )
            }
        }
    }

    override suspend fun upsert(preferences: AnimePlaybackPreferences) {
        handler.await(inTransaction = true) {
            anime_playback_preferencesQueries.upsertUpdate(
                dubKey = preferences.dubKey,
                streamKey = preferences.streamKey,
                sourceQualityKey = preferences.sourceQualityKey,
                playerQualityMode = AnimePlaybackPreferencesMapper.encodePlayerQualityMode(
                    preferences.playerQualityMode,
                ),
                playerQualityHeight = preferences.playerQualityHeight?.toLong(),
                subtitleOffsetX = preferences.subtitleOffsetX,
                subtitleOffsetY = preferences.subtitleOffsetY,
                subtitleTextSize = preferences.subtitleTextSize,
                subtitleTextColor = preferences.subtitleTextColor?.toLong(),
                subtitleBackgroundColor = preferences.subtitleBackgroundColor?.toLong(),
                subtitleBackgroundOpacity = preferences.subtitleBackgroundOpacity,
                updatedAt = preferences.updatedAt,
                profileId = profileProvider.activeProfileId,
                animeId = preferences.animeId,
            )
            anime_playback_preferencesQueries.upsertInsert(
                profileId = profileProvider.activeProfileId,
                animeId = preferences.animeId,
                dubKey = preferences.dubKey,
                streamKey = preferences.streamKey,
                sourceQualityKey = preferences.sourceQualityKey,
                playerQualityMode = AnimePlaybackPreferencesMapper.encodePlayerQualityMode(
                    preferences.playerQualityMode,
                ),
                playerQualityHeight = preferences.playerQualityHeight?.toLong(),
                subtitleOffsetX = preferences.subtitleOffsetX,
                subtitleOffsetY = preferences.subtitleOffsetY,
                subtitleTextSize = preferences.subtitleTextSize,
                subtitleTextColor = preferences.subtitleTextColor?.toLong(),
                subtitleBackgroundColor = preferences.subtitleBackgroundColor?.toLong(),
                subtitleBackgroundOpacity = preferences.subtitleBackgroundOpacity,
                updatedAt = preferences.updatedAt,
            )
        }
    }
}
