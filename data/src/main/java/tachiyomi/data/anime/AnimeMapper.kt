package tachiyomi.data.anime

import tachiyomi.data.Animes
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.domain.anime.model.AnimeTitle

object AnimeMapper {
    fun mapAnime(anime: Animes): AnimeTitle {
        return mapAnime(
            id = anime._id,
            profileId = anime.profile_id,
            source = anime.source,
            url = anime.url,
            title = anime.title,
            displayName = anime.display_name,
            originalTitle = anime.original_title,
            country = anime.country,
            studio = anime.studio,
            producer = anime.producer,
            director = anime.director,
            writer = anime.writer,
            year = anime.year,
            duration = anime.duration,
            description = anime.description,
            genre = anime.genre,
            status = anime.status,
            thumbnailUrl = anime.thumbnail_url,
            favorite = anime.favorite,
            initialized = anime.initialized,
            lastUpdate = anime.last_update,
            dateAdded = anime.date_added,
            episodeFlags = anime.episode_flags,
            coverLastModified = anime.cover_last_modified,
            lastModifiedAt = anime.last_modified_at,
            favoriteModifiedAt = anime.favorite_modified_at,
            version = anime.version,
            notes = anime.notes,
        )
    }

    fun mapAnime(
        id: Long,
        @Suppress("UNUSED_PARAMETER")
        profileId: Long,
        source: Long,
        url: String,
        title: String,
        displayName: String?,
        originalTitle: String?,
        country: String?,
        studio: String?,
        producer: String?,
        director: String?,
        writer: String?,
        year: String?,
        duration: String?,
        description: String?,
        genre: List<String>?,
        status: Long,
        thumbnailUrl: String?,
        favorite: Boolean,
        initialized: Boolean,
        lastUpdate: Long?,
        dateAdded: Long,
        episodeFlags: Long,
        coverLastModified: Long,
        lastModifiedAt: Long,
        favoriteModifiedAt: Long?,
        version: Long,
        notes: String,
    ): AnimeTitle = AnimeTitle(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate ?: 0L,
        dateAdded = dateAdded,
        episodeFlags = episodeFlags,
        coverLastModified = coverLastModified,
        url = url,
        title = title,
        displayName = displayName,
        originalTitle = originalTitle,
        country = country,
        studio = studio,
        producer = producer,
        director = director,
        writer = writer,
        year = year,
        duration = duration,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        initialized = initialized,
        lastModifiedAt = lastModifiedAt,
        favoriteModifiedAt = favoriteModifiedAt,
        version = version,
        notes = notes,
    )

    fun encodeGenre(genre: List<String>?): String? {
        return genre?.let(StringListColumnAdapter::encode)
    }
}
