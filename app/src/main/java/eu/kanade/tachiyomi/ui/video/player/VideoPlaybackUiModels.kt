package eu.kanade.tachiyomi.ui.video.player

import android.graphics.Color
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackOption
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoSubtitle
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.PlayerQualityMode

data class VideoPlaybackUiState(
    val sourceSelection: VideoPlaybackSelection,
    val preferredSourceQualityKey: String?,
    val currentStream: VideoStream,
    val subtitles: List<VideoSubtitle>,
    val currentStreamLabel: String,
    val streamOptions: List<VideoPlaybackOption>,
    val playbackData: VideoPlaybackData,
    val preview: VideoPlaybackPreviewState = VideoPlaybackPreviewState(),
    val adaptiveQualities: List<VideoAdaptiveQualityOption> = emptyList(),
    val currentAdaptiveQuality: VideoAdaptiveQualityPreference = VideoAdaptiveQualityPreference.Auto,
    val subtitleOptions: List<VideoPlayerSubtitleOption> = emptyList(),
    val currentSubtitle: VideoPlayerSubtitleSelection = VideoPlayerSubtitleSelection.None,
    val subtitleAppearance: VideoSubtitleAppearance = VideoSubtitleAppearance(),
) {
    val showsAdaptiveQualitySelector: Boolean
        get() = playbackData.sourceQualities.isEmpty() && adaptiveQualities.size > 1

    val hasSubtitleSelection: Boolean
        get() = subtitles.isNotEmpty() || subtitleOptions.any { it.selection is VideoPlayerSubtitleSelection.Embedded }

    val displayedPlaybackData: VideoPlaybackData
        get() = preview.playbackData ?: playbackData

    val isPreviewLoading: Boolean
        get() = preview.isLoading

    val persistedSourceSelection: VideoPlaybackSelection
        get() = sourceSelection.copy(
            sourceQualityKey = preferredSourceQualityKey ?: sourceSelection.sourceQualityKey,
        )
}

data class VideoPlaybackPreviewState(
    val selection: VideoPlaybackSelection? = null,
    val playbackData: VideoPlaybackData? = null,
    val subtitles: List<VideoSubtitle>? = null,
    val isLoading: Boolean = false,
)

sealed interface VideoAdaptiveQualityPreference {
    data object Auto : VideoAdaptiveQualityPreference

    data class SpecificHeight(val height: Int) : VideoAdaptiveQualityPreference

    fun toPlayerQualityMode(): PlayerQualityMode {
        return when (this) {
            Auto -> PlayerQualityMode.AUTO
            is SpecificHeight -> PlayerQualityMode.SPECIFIC_HEIGHT
        }
    }

    fun heightOrNull(): Int? {
        return when (this) {
            Auto -> null
            is SpecificHeight -> height
        }
    }
}

data class VideoAdaptiveQualityOption(
    val label: String,
    val preference: VideoAdaptiveQualityPreference,
)

data class VideoSubtitleAppearance(
    val offsetX: Float = DEFAULT_SUBTITLE_OFFSET_X,
    val offsetY: Float = DEFAULT_SUBTITLE_OFFSET_Y,
    val textSize: Float = DEFAULT_SUBTITLE_TEXT_SIZE,
    val textColor: Int = DEFAULT_SUBTITLE_TEXT_COLOR,
    val backgroundColor: Int = DEFAULT_SUBTITLE_BACKGROUND_COLOR,
    val backgroundOpacity: Float = DEFAULT_SUBTITLE_BACKGROUND_OPACITY,
) {
    val isCustomized: Boolean
        get() = normalized() != VideoSubtitleAppearance()

    fun normalized(): VideoSubtitleAppearance {
        return copy(
            offsetX = offsetX.coerceIn(MIN_SUBTITLE_OFFSET_X, MAX_SUBTITLE_OFFSET_X),
            offsetY = offsetY.coerceIn(MIN_SUBTITLE_OFFSET_Y, MAX_SUBTITLE_OFFSET_Y),
            textSize = textSize.coerceIn(MIN_SUBTITLE_TEXT_SIZE, MAX_SUBTITLE_TEXT_SIZE),
            backgroundOpacity = backgroundOpacity.coerceIn(0f, 1f),
        )
    }
}

data class VideoPlayerSubtitleOption(
    val key: String,
    val label: String,
    val selection: VideoPlayerSubtitleSelection,
)

sealed interface VideoPlayerSubtitleSelection {
    data object None : VideoPlayerSubtitleSelection

    data object Default : VideoPlayerSubtitleSelection

    data class External(val subtitle: VideoSubtitle) : VideoPlayerSubtitleSelection

    data class Embedded(
        val groupIndex: Int,
        val trackIndex: Int,
        val key: String,
        val label: String,
        val language: String?,
        val isDefault: Boolean,
        val isForced: Boolean,
    ) : VideoPlayerSubtitleSelection
}

internal fun VideoPlaybackSelection.withSelectedStream(streamKey: String?): VideoPlaybackSelection {
    return copy(streamKey = streamKey)
}

internal fun VideoPlaybackSelection.withSelectedDub(
    dubKey: String?,
    originalSelection: VideoPlaybackSelection,
): VideoPlaybackSelection {
    return copy(
        dubKey = dubKey,
        streamKey = null,
    ).restoringOriginalStreamIfCompatible(originalSelection)
}

internal fun VideoPlaybackSelection.withSelectedSourceQuality(
    sourceQualityKey: String?,
    originalSelection: VideoPlaybackSelection,
): VideoPlaybackSelection {
    return copy(
        sourceQualityKey = sourceQualityKey,
        streamKey = null,
    ).restoringOriginalStreamIfCompatible(originalSelection)
}

private fun VideoPlaybackSelection.restoringOriginalStreamIfCompatible(
    originalSelection: VideoPlaybackSelection,
): VideoPlaybackSelection {
    return if (dubKey == originalSelection.dubKey && sourceQualityKey == originalSelection.sourceQualityKey) {
        copy(streamKey = originalSelection.streamKey)
    } else {
        this
    }
}

internal fun defaultSubtitleSelection(subtitles: List<VideoSubtitle>): VideoPlayerSubtitleSelection {
    return when {
        subtitles.isNotEmpty() -> {
            val subtitle = subtitles.firstOrNull(VideoSubtitle::isDefault) ?: subtitles.first()
            VideoPlayerSubtitleSelection.External(subtitle)
        }
        else -> VideoPlayerSubtitleSelection.None
    }
}

internal fun resolveSourceSubtitleSelection(
    requested: VideoPlayerSubtitleSelection?,
    subtitles: List<VideoSubtitle>,
): VideoPlayerSubtitleSelection {
    return when (requested) {
        null -> defaultSubtitleSelection(subtitles)
        VideoPlayerSubtitleSelection.None -> VideoPlayerSubtitleSelection.None
        VideoPlayerSubtitleSelection.Default -> defaultSubtitleSelection(subtitles)
        is VideoPlayerSubtitleSelection.External -> {
            subtitles.firstOrNull { subtitleChoiceKey(it) == subtitleChoiceKey(requested.subtitle) }
                ?.let(VideoPlayerSubtitleSelection::External)
                ?: defaultSubtitleSelection(subtitles)
        }
        is VideoPlayerSubtitleSelection.Embedded -> requested
    }
}

internal fun resolvePreviewSubtitleSelection(
    requested: VideoPlayerSubtitleSelection,
    subtitles: List<VideoSubtitle>,
): VideoPlayerSubtitleSelection {
    return when (requested) {
        is VideoPlayerSubtitleSelection.Embedded -> defaultSubtitleSelection(subtitles)
        else -> resolveSourceSubtitleSelection(requested, subtitles)
    }
}

internal fun externalSubtitleOptions(subtitles: List<VideoSubtitle>): List<VideoPlayerSubtitleOption> {
    return buildList {
        add(
            VideoPlayerSubtitleOption(
                key = OFF_SUBTITLE_KEY,
                label = "Off",
                selection = VideoPlayerSubtitleSelection.None,
            ),
        )
        subtitles.forEach { subtitle ->
            add(
                VideoPlayerSubtitleOption(
                    key = subtitleChoiceKey(subtitle),
                    label = subtitle.label.ifBlank {
                        subtitle.language?.takeIf(String::isNotBlank) ?: "Subtitle"
                    },
                    selection = VideoPlayerSubtitleSelection.External(subtitle),
                ),
            )
        }
    }.distinctBy(VideoPlayerSubtitleOption::key)
}

internal fun subtitleSelectionKey(selection: VideoPlayerSubtitleSelection): String {
    return when (selection) {
        VideoPlayerSubtitleSelection.None -> OFF_SUBTITLE_KEY
        VideoPlayerSubtitleSelection.Default -> OFF_SUBTITLE_KEY
        is VideoPlayerSubtitleSelection.External -> subtitleChoiceKey(selection.subtitle)
        is VideoPlayerSubtitleSelection.Embedded -> selection.key
    }
}

internal fun AnimePlaybackPreferences.toAdaptiveQualityPreference(): VideoAdaptiveQualityPreference {
    return when (playerQualityMode) {
        PlayerQualityMode.AUTO -> VideoAdaptiveQualityPreference.Auto
        PlayerQualityMode.SPECIFIC_HEIGHT ->
            playerQualityHeight
                ?.let(VideoAdaptiveQualityPreference::SpecificHeight)
                ?: VideoAdaptiveQualityPreference.Auto
    }
}

internal fun AnimePlaybackPreferences.toSubtitleAppearance(): VideoSubtitleAppearance {
    return VideoSubtitleAppearance(
        offsetX = subtitleOffsetX?.toFloat() ?: DEFAULT_SUBTITLE_OFFSET_X,
        offsetY = subtitleOffsetY?.toFloat() ?: DEFAULT_SUBTITLE_OFFSET_Y,
        textSize = subtitleTextSize?.toFloat() ?: DEFAULT_SUBTITLE_TEXT_SIZE,
        textColor = subtitleTextColor ?: DEFAULT_SUBTITLE_TEXT_COLOR,
        backgroundColor = subtitleBackgroundColor ?: DEFAULT_SUBTITLE_BACKGROUND_COLOR,
        backgroundOpacity = subtitleBackgroundOpacity?.toFloat() ?: DEFAULT_SUBTITLE_BACKGROUND_OPACITY,
    ).normalized()
}

internal fun VideoSubtitleAppearance.toPersistedOffsetX(): Double? {
    val normalized = normalized()
    return normalized.offsetX.toDouble().takeIf { normalized.isCustomized }
}

internal fun VideoSubtitleAppearance.toPersistedOffsetY(): Double? {
    val normalized = normalized()
    return normalized.offsetY.toDouble().takeIf { normalized.isCustomized }
}

internal fun VideoSubtitleAppearance.toPersistedTextSize(): Double? {
    val normalized = normalized()
    return normalized.textSize.toDouble().takeIf { normalized.isCustomized }
}

internal fun VideoSubtitleAppearance.toPersistedTextColor(): Int? {
    val normalized = normalized()
    return normalized.textColor.takeIf { normalized.isCustomized }
}

internal fun VideoSubtitleAppearance.toPersistedBackgroundColor(): Int? {
    val normalized = normalized()
    return normalized.backgroundColor.takeIf { normalized.isCustomized }
}

internal fun VideoSubtitleAppearance.toPersistedBackgroundOpacity(): Double? {
    val normalized = normalized()
    return normalized.backgroundOpacity.toDouble().takeIf { normalized.isCustomized }
}

internal const val DEFAULT_SUBTITLE_TEXT_SIZE = 0.0533f
internal const val DEFAULT_SUBTITLE_BOTTOM_PADDING_FRACTION = 0.08f
internal const val DEFAULT_SUBTITLE_BACKGROUND_OPACITY = 0.72f
internal const val DEFAULT_SUBTITLE_OFFSET_X = 0f
internal const val DEFAULT_SUBTITLE_OFFSET_Y = 0f
internal const val MIN_SUBTITLE_TEXT_SIZE = 0.03f
internal const val MAX_SUBTITLE_TEXT_SIZE = 0.12f
internal const val MIN_SUBTITLE_OFFSET_X = -0.45f
internal const val MAX_SUBTITLE_OFFSET_X = 0.45f
internal const val MIN_SUBTITLE_OFFSET_Y = -0.78f
internal const val MAX_SUBTITLE_OFFSET_Y = 0.25f
internal const val DEFAULT_SUBTITLE_TEXT_COLOR = Color.WHITE
internal const val DEFAULT_SUBTITLE_BACKGROUND_COLOR = Color.BLACK

internal fun subtitleBaselineTop(containerHeight: Int, subtitleHeight: Int): Float {
    return (containerHeight - subtitleHeight - (containerHeight * DEFAULT_SUBTITLE_BOTTOM_PADDING_FRACTION))
        .coerceAtLeast(0f)
}
