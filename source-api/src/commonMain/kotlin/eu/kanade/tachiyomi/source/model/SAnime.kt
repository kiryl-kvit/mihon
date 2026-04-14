@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SAnime : Serializable {

    var url: String

    var title: String

    var original_title: String?

    var country: String?

    var studio: String?

    var producer: String?

    var director: String?

    var writer: String?

    var year: String?

    var duration: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.original_title = original_title
        it.country = country
        it.studio = studio
        it.producer = producer
        it.director = director
        it.writer = writer
        it.year = year
        it.duration = duration
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.update_strategy = update_strategy
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val CANCELLED = 3
        const val ON_HIATUS = 4

        fun create(): SAnime {
            return SAnimeImpl()
        }
    }
}
