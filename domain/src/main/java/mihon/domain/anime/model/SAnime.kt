package mihon.domain.anime.model

import eu.kanade.tachiyomi.source.model.SAnime
import tachiyomi.domain.anime.model.AnimeTitle

fun SAnime.toDomainAnime(sourceId: Long): AnimeTitle {
    return AnimeTitle.create().copy(
        source = sourceId,
        url = url,
        title = title,
        originalTitle = original_title,
        country = country,
        studio = studio,
        producer = producer,
        director = director,
        writer = writer,
        year = year,
        duration = duration,
        description = description,
        genre = getGenres(),
        status = status.toLong(),
        thumbnailUrl = thumbnail_url,
        initialized = initialized,
    )
}
