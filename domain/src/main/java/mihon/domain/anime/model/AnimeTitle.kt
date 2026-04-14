package mihon.domain.anime.model

import eu.kanade.tachiyomi.source.model.SAnime
import tachiyomi.domain.anime.model.AnimeTitle

fun AnimeTitle.toSAnime(): SAnime = SAnime.create().also {
    it.url = url
    it.title = title
    it.original_title = originalTitle
    it.country = country
    it.studio = studio
    it.producer = producer
    it.director = director
    it.writer = writer
    it.year = year
    it.duration = duration
    it.description = description
    it.genre = genre?.joinToString()
    it.status = status.toInt()
    it.thumbnail_url = thumbnailUrl
    it.initialized = initialized
}
