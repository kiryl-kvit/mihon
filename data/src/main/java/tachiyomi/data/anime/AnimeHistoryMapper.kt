package tachiyomi.data.anime

import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

object AnimeHistoryMapper {
    fun mapHistory(
        id: Long,
        episodeId: Long,
        watchedAt: Date?,
        watchedDuration: Long,
    ): AnimeHistory = AnimeHistory(
        id = id,
        episodeId = episodeId,
        watchedAt = watchedAt,
        watchedDuration = watchedDuration,
    )

    fun mapHistoryWithRelations(
        id: Long,
        episodeId: Long,
        animeId: Long,
        title: String,
        episodeName: String,
        watchedAt: Date?,
        watchedDuration: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
    ): AnimeHistoryWithRelations = AnimeHistoryWithRelations(
        id = id,
        episodeId = episodeId,
        animeId = animeId,
        title = title,
        episodeName = episodeName,
        watchedAt = watchedAt,
        watchedDuration = watchedDuration,
        coverData = MangaCover(
            mangaId = animeId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
