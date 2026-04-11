package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
