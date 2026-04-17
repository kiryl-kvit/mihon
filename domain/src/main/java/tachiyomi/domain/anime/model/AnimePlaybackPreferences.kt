package tachiyomi.domain.anime.model

data class AnimePlaybackPreferences(
    val animeId: Long,
    val dubKey: String?,
    val streamKey: String?,
    val sourceQualityKey: String?,
    val playerQualityMode: PlayerQualityMode,
    val playerQualityHeight: Int?,
    val subtitleOffsetX: Double? = null,
    val subtitleOffsetY: Double? = null,
    val subtitleTextSize: Double? = null,
    val subtitleTextColor: Int? = null,
    val subtitleBackgroundColor: Int? = null,
    val subtitleBackgroundOpacity: Double? = null,
    val updatedAt: Long,
)

enum class PlayerQualityMode {
    AUTO,
    SPECIFIC_HEIGHT,
}
