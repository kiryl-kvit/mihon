package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoSubtitle(
    val request: VideoRequest,
    val label: String,
    val language: String? = null,
    val mimeType: String? = null,
    val key: String = "",
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
)
