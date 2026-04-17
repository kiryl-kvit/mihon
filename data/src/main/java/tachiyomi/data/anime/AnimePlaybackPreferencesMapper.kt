package tachiyomi.data.anime

import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.PlayerQualityMode

object AnimePlaybackPreferencesMapper {
    fun mapPreferences(
        @Suppress("UNUSED_PARAMETER")
        id: Long,
        @Suppress("UNUSED_PARAMETER")
        profileId: Long,
        animeId: Long,
        dubKey: String?,
        streamKey: String?,
        sourceQualityKey: String?,
        playerQualityMode: String,
        playerQualityHeight: Long?,
        subtitleOffsetX: Double?,
        subtitleOffsetY: Double?,
        subtitleTextSize: Double?,
        subtitleTextColor: Long?,
        subtitleBackgroundColor: Long?,
        subtitleBackgroundOpacity: Double?,
        updatedAt: Long,
    ): AnimePlaybackPreferences {
        return AnimePlaybackPreferences(
            animeId = animeId,
            dubKey = dubKey,
            streamKey = streamKey,
            sourceQualityKey = sourceQualityKey,
            playerQualityMode = playerQualityMode.fromDatabaseValue(),
            playerQualityHeight = playerQualityHeight?.toInt(),
            subtitleOffsetX = subtitleOffsetX,
            subtitleOffsetY = subtitleOffsetY,
            subtitleTextSize = subtitleTextSize,
            subtitleTextColor = subtitleTextColor?.toInt(),
            subtitleBackgroundColor = subtitleBackgroundColor?.toInt(),
            subtitleBackgroundOpacity = subtitleBackgroundOpacity,
            updatedAt = updatedAt,
        )
    }

    fun encodePlayerQualityMode(mode: PlayerQualityMode): String {
        return when (mode) {
            PlayerQualityMode.AUTO -> "auto"
            PlayerQualityMode.SPECIFIC_HEIGHT -> "specific_height"
        }
    }

    private fun String.fromDatabaseValue(): PlayerQualityMode {
        return when (this) {
            "specific_height" -> PlayerQualityMode.SPECIFIC_HEIGHT
            else -> PlayerQualityMode.AUTO
        }
    }
}
