package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.PlayerQualityMode

@Serializable
data class BackupAnimePlaybackPreferences(
    @ProtoNumber(1) var dubKey: String? = null,
    @ProtoNumber(2) var streamKey: String? = null,
    @ProtoNumber(3) var sourceQualityKey: String? = null,
    @ProtoNumber(4) var playerQualityMode: String = "auto",
    @ProtoNumber(5) var playerQualityHeight: Int? = null,
    @ProtoNumber(6) var updatedAt: Long = 0,
    @ProtoNumber(7) var subtitleOffsetX: Double? = null,
    @ProtoNumber(8) var subtitleOffsetY: Double? = null,
    @ProtoNumber(9) var subtitleTextSize: Double? = null,
    @ProtoNumber(10) var subtitleTextColor: Int? = null,
    @ProtoNumber(11) var subtitleBackgroundColor: Int? = null,
    @ProtoNumber(12) var subtitleBackgroundOpacity: Double? = null,
) {
    fun toPlaybackPreferences(): AnimePlaybackPreferences {
        return AnimePlaybackPreferences(
            animeId = -1L,
            dubKey = dubKey,
            streamKey = streamKey,
            sourceQualityKey = sourceQualityKey,
            playerQualityMode = when (playerQualityMode) {
                "specific_height" -> PlayerQualityMode.SPECIFIC_HEIGHT
                else -> PlayerQualityMode.AUTO
            },
            playerQualityHeight = playerQualityHeight,
            subtitleOffsetX = subtitleOffsetX,
            subtitleOffsetY = subtitleOffsetY,
            subtitleTextSize = subtitleTextSize,
            subtitleTextColor = subtitleTextColor,
            subtitleBackgroundColor = subtitleBackgroundColor,
            subtitleBackgroundOpacity = subtitleBackgroundOpacity,
            updatedAt = updatedAt,
        )
    }
}
