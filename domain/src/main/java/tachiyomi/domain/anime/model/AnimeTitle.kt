package tachiyomi.domain.anime.model

import androidx.compose.runtime.Immutable
import java.io.Serializable

@Immutable
data class AnimeTitle(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val dateAdded: Long,
    val url: String,
    val title: String,
    val displayName: String?,
    val originalTitle: String?,
    val country: String?,
    val studio: String?,
    val producer: String?,
    val director: String?,
    val writer: String?,
    val year: String?,
    val duration: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val initialized: Boolean,
    val lastModifiedAt: Long,
    val favoriteModifiedAt: Long?,
    val version: Long,
    val notes: String,
) : Serializable {

    val displayTitle: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: title

    companion object {
        fun create() = AnimeTitle(
            id = -1L,
            source = -1L,
            favorite = false,
            lastUpdate = 0L,
            nextUpdate = 0L,
            dateAdded = 0L,
            url = "",
            title = "",
            displayName = null,
            originalTitle = null,
            country = null,
            studio = null,
            producer = null,
            director = null,
            writer = null,
            year = null,
            duration = null,
            description = null,
            genre = null,
            status = 0L,
            thumbnailUrl = null,
            initialized = false,
            lastModifiedAt = 0L,
            favoriteModifiedAt = null,
            version = 0L,
            notes = "",
        )
    }
}
