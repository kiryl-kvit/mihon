package tachiyomi.domain.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate

interface AnimeRepository {

    suspend fun getAnimeById(id: Long): AnimeTitle

    suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle>

    suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle?

    fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?>

    suspend fun getFavorites(): List<AnimeTitle>

    fun getFavoritesAsFlow(): Flow<List<AnimeTitle>>

    suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle>

    suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean

    suspend fun update(update: AnimeTitleUpdate): Boolean

    suspend fun updateAll(animeUpdates: List<AnimeTitleUpdate>): Boolean

    suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle>

    suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>)
}
