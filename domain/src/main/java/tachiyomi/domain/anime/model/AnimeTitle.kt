package tachiyomi.domain.anime.model

import androidx.compose.runtime.Immutable
import tachiyomi.core.common.preference.TriState
import java.io.Serializable

@Immutable
data class AnimeTitle(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val dateAdded: Long,
    val episodeFlags: Long,
    val coverLastModified: Long,
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

    val sorting: Long
        get() = episodeFlags and EPISODE_SORTING_MASK

    val displayMode: Long
        get() = episodeFlags and EPISODE_DISPLAY_MASK

    val unwatchedFilterRaw: Long
        get() = episodeFlags and EPISODE_UNWATCHED_MASK

    val startedFilterRaw: Long
        get() = episodeFlags and EPISODE_STARTED_MASK

    val unwatchedFilter: TriState
        get() = when (unwatchedFilterRaw) {
            EPISODE_SHOW_UNWATCHED -> TriState.ENABLED_IS
            EPISODE_SHOW_WATCHED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    val startedFilter: TriState
        get() = when (startedFilterRaw) {
            EPISODE_SHOW_STARTED -> TriState.ENABLED_IS
            EPISODE_SHOW_NOT_STARTED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }

    fun sortDescending(): Boolean {
        return episodeFlags and EPISODE_SORT_DIR_MASK == EPISODE_SORT_DESC
    }

    companion object {
        const val SHOW_ALL = 0x00000000L

        const val EPISODE_SORT_DESC = 0x00000000L
        const val EPISODE_SORT_ASC = 0x00000001L
        const val EPISODE_SORT_DIR_MASK = 0x00000001L

        const val EPISODE_SHOW_UNWATCHED = 0x00000002L
        const val EPISODE_SHOW_WATCHED = 0x00000004L
        const val EPISODE_UNWATCHED_MASK = 0x00000006L

        const val EPISODE_SHOW_STARTED = 0x00000008L
        const val EPISODE_SHOW_NOT_STARTED = 0x00000010L
        const val EPISODE_STARTED_MASK = 0x00000018L

        const val EPISODE_SORTING_SOURCE = 0x00000000L
        const val EPISODE_SORTING_NUMBER = 0x00000100L
        const val EPISODE_SORTING_UPLOAD_DATE = 0x00000200L
        const val EPISODE_SORTING_ALPHABET = 0x00000300L
        const val EPISODE_SORTING_MASK = 0x00000300L

        const val EPISODE_DISPLAY_NAME = 0x00000000L
        const val EPISODE_DISPLAY_NUMBER = 0x00100000L
        const val EPISODE_DISPLAY_MASK = 0x00100000L

        fun create() = AnimeTitle(
            id = -1L,
            source = -1L,
            favorite = false,
            lastUpdate = 0L,
            dateAdded = 0L,
            episodeFlags = 0L,
            coverLastModified = 0L,
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
