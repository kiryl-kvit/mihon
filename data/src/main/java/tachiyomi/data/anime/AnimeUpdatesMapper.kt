package tachiyomi.data.anime

import tachiyomi.domain.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.manga.model.MangaCover

object AnimeUpdatesMapper {
    fun mapUpdatesWithRelations(
        @Suppress("UNUSED_PARAMETER")
        profileId: Long,
        animeId: Long,
        animeTitle: String,
        episodeId: Long,
        episodeName: String,
        episodeUrl: String,
        watched: Boolean,
        completed: Boolean,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        dateFetch: Long,
    ): AnimeUpdatesWithRelations = AnimeUpdatesWithRelations(
        animeId = animeId,
        animeTitle = animeTitle,
        episodeId = episodeId,
        episodeName = episodeName,
        episodeUrl = episodeUrl,
        watched = watched,
        completed = completed,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = animeId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
