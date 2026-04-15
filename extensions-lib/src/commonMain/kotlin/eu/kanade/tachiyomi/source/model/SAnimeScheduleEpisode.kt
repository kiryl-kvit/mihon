package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable

@Serializable
data class SAnimeScheduleEpisode(
    val seasonNumber: Int? = null,
    val episodeNumber: Float? = null,
    val title: String? = null,
    val airDate: Long,
    val statusText: String? = null,
    val isAvailable: Boolean? = null,
)
