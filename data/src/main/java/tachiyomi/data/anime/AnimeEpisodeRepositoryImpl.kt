package tachiyomi.data.anime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeEpisodeRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : AnimeEpisodeRepository {

    override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> {
        return try {
            handler.await(inTransaction = true) {
                episodes.map { episode ->
                    anime_episodesQueries.insert(
                        profileId = profileProvider.activeProfileId,
                        animeId = episode.animeId,
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
                    val lastInsertId = anime_episodesQueries.selectLastInsertedRowId().executeAsOne()
                    episode.copy(id = lastInsertId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(episodeUpdate: AnimeEpisodeUpdate) {
        partialUpdate(episodeUpdate)
    }

    override suspend fun updateAll(episodeUpdates: List<AnimeEpisodeUpdate>) {
        partialUpdate(*episodeUpdates.toTypedArray())
    }

    override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) {
        try {
            handler.await { anime_episodesQueries.removeEpisodesWithIds(profileProvider.activeProfileId, episodeIds) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> {
        return handler.awaitList {
            anime_episodesQueries.getEpisodesByAnimeId(
                profileProvider.activeProfileId,
                animeId,
                AnimeEpisodeMapper::mapEpisode,
            )
        }
    }

    override fun getEpisodesByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeEpisode>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                anime_episodesQueries.getEpisodesByAnimeId(
                    profileId,
                    animeId,
                    AnimeEpisodeMapper::mapEpisode,
                )
            }
        }
    }

    override fun getEpisodesByAnimeIdsAsFlow(animeIds: List<Long>): Flow<List<AnimeEpisode>> {
        if (animeIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())

        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                anime_episodesQueries.getEpisodesByAnimeIds(
                    profileId = profileId,
                    animeIds = animeIds,
                    mapper = AnimeEpisodeMapper::mapEpisode,
                )
            }
        }
    }

    override suspend fun getEpisodeById(id: Long): AnimeEpisode? {
        return handler.awaitOneOrNull {
            anime_episodesQueries.getEpisodeById(id, profileProvider.activeProfileId, AnimeEpisodeMapper::mapEpisode)
        }
    }

    override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? {
        return handler.awaitOneOrNull {
            anime_episodesQueries.getEpisodeByUrlAndAnimeId(
                profileProvider.activeProfileId,
                url,
                animeId,
                AnimeEpisodeMapper::mapEpisode,
            )
        }
    }

    private suspend fun partialUpdate(vararg episodeUpdates: AnimeEpisodeUpdate) {
        handler.await(inTransaction = true) {
            episodeUpdates.forEach { episodeUpdate ->
                anime_episodesQueries.update(
                    animeId = episodeUpdate.animeId,
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
