package tachiyomi.data.anime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : AnimeRepository {

    override suspend fun getAnimeById(id: Long): AnimeTitle {
        return handler.awaitOne {
            animesQueries.getAnimeById(id, profileProvider.activeProfileId, AnimeMapper::mapAnime)
        }
    }

    override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                animesQueries.getAnimeById(id, profileId, AnimeMapper::mapAnime)
            }.filterNotNull()
        }
    }

    override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? {
        return handler.awaitOneOrNull {
            animesQueries.getAnimeByUrlAndSource(
                profileProvider.activeProfileId,
                url,
                sourceId,
                AnimeMapper::mapAnime,
            )
        }
    }

    override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                animesQueries.getAnimeByUrlAndSource(
                    profileId,
                    url,
                    sourceId,
                    AnimeMapper::mapAnime,
                )
            }
        }
    }

    override suspend fun getFavorites(): List<AnimeTitle> {
        return handler.awaitList {
            animesQueries.getFavorites(profileProvider.activeProfileId, AnimeMapper::mapAnime)
        }
    }

    override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                animesQueries.getFavorites(profileId, AnimeMapper::mapAnime)
            }
        }
    }

    override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> {
        return handler.awaitList {
            animesQueries.getAllAnime(profileId, AnimeMapper::mapAnime)
        }
    }

    override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean {
        return handler.await {
            animesQueries.updateDisplayName(
                displayName = displayName,
                animeId = animeId,
                profileId = profileProvider.activeProfileId,
            )
            true
        }
    }

    override suspend fun update(update: AnimeTitleUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(animeUpdates: List<AnimeTitleUpdate>): Boolean {
        return try {
            partialUpdate(*animeUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> {
        return handler.await(inTransaction = true) {
            animes.map {
                animesQueries.insertNetworkAnime(
                    profileId = profileProvider.activeProfileId,
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    originalTitle = it.originalTitle,
                    country = it.country,
                    studio = it.studio,
                    producer = it.producer,
                    director = it.director,
                    writer = it.writer,
                    year = it.year,
                    duration = it.duration,
                    description = it.description,
                    genre = it.genre,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    initialized = it.initialized,
                    lastUpdate = it.lastUpdate,
                    dateAdded = it.dateAdded,
                    episodeFlags = it.episodeFlags,
                    coverLastModified = it.coverLastModified,
                    version = it.version,
                    notes = it.notes,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                )
                    .executeAsOne()
                    .let { row -> AnimeMapper.mapAnime(row) }
            }
        }
    }

    override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            animes_categoriesQueries.deleteAnimeCategoryByAnimeId(profileProvider.activeProfileId, animeId)
            categoryIds.forEach { categoryId ->
                animes_categoriesQueries.insert(profileProvider.activeProfileId, animeId, categoryId)
            }
        }
    }

    private suspend fun partialUpdate(vararg animeUpdates: AnimeTitleUpdate) {
        handler.await(inTransaction = true) {
            animeUpdates.forEach { value ->
                animesQueries.update(
                    source = value.source,
                    url = value.url,
                    title = value.title,
                    displayName = value.displayName,
                    originalTitle = value.originalTitle,
                    country = value.country,
                    studio = value.studio,
                    producer = value.producer,
                    director = value.director,
                    writer = value.writer,
                    year = value.year,
                    duration = value.duration,
                    description = value.description,
                    genre = AnimeMapper.encodeGenre(value.genre),
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    initialized = value.initialized,
                    lastUpdate = value.lastUpdate,
                    dateAdded = value.dateAdded,
                    episodeFlags = value.episodeFlags,
                    coverLastModified = value.coverLastModified,
                    animeId = value.id,
                    version = value.version,
                    notes = value.notes,
                    profileId = profileProvider.activeProfileId,
                )
            }
        }
    }
}
