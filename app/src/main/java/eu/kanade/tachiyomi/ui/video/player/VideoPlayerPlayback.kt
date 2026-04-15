package eu.kanade.tachiyomi.ui.video.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoStreamType

@OptIn(markerClass = [UnstableApi::class])
internal fun buildVideoPlayer(
    context: Context,
    networkHelper: NetworkHelper,
    stream: VideoStream,
): ExoPlayer {
    val requestHeaders = stream.request.headers
    val userAgent = requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        ?: networkHelper.defaultUserAgentProvider()

    val okHttpDataSourceFactory = OkHttpDataSource.Factory(networkHelper.client)
        .setUserAgent(userAgent)
        .setDefaultRequestProperties(requestHeaders)
    val dataSourceFactory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

    return ExoPlayer.Builder(context, mediaSourceFactory)
        .build()
        .apply {
            setMediaItem(stream.toMediaItem())
        }
}

internal fun VideoStream.toMediaItem(): MediaItem {
    return MediaItem.Builder()
        .setUri(request.url)
        .setMimeType(mimeType ?: type.toMimeType())
        .build()
}

internal fun ExoPlayer.availableAdaptiveQualities(): List<VideoAdaptiveQualityOption> {
    val groups = currentTracks.groups
        .filter { it.getType() == C.TRACK_TYPE_VIDEO && it.isSupported() }
    if (groups.isEmpty()) return emptyList()

    val heights = groups
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .mapNotNull { index -> group.getTrackFormat(index).height.takeIf { it > 0 } }
        }
        .distinct()
        .sorted()

    return buildList {
        add(VideoAdaptiveQualityOption(label = "Auto", preference = VideoAdaptiveQualityPreference.Auto))
        heights.forEach { height ->
            add(
                VideoAdaptiveQualityOption(
                    label = "${height}p",
                    preference = VideoAdaptiveQualityPreference.SpecificHeight(height),
                ),
            )
        }
    }
}

internal fun ExoPlayer.applyAdaptiveQuality(preference: VideoAdaptiveQualityPreference) {
    val builder = trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .setForceLowestBitrate(false)
        .setForceHighestSupportedBitrate(false)

    when (preference) {
        VideoAdaptiveQualityPreference.Auto -> {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        is VideoAdaptiveQualityPreference.SpecificHeight -> {
            val override = currentTracks.groups
                .asSequence()
                .filter { it.getType() == C.TRACK_TYPE_VIDEO && it.isSupported() }
                .mapNotNull { group -> preferredTrackOverride(group, preference.height) }
                .minByOrNull { it.first }
                ?.second

            if (override != null) {
                builder.setOverrideForType(override)
            } else {
                builder.setMaxVideoSize(Int.MAX_VALUE, preference.height)
            }
        }
    }

    trackSelectionParameters = builder.build()
}

private fun preferredTrackOverride(group: Tracks.Group, targetHeight: Int): Pair<Int, TrackSelectionOverride>? {
    val candidate = (0 until group.length)
        .filter { group.isTrackSupported(it) }
        .mapNotNull { index ->
            val height = group.getTrackFormat(index).height.takeIf { it > 0 } ?: return@mapNotNull null
            index to height
        }
        .minByOrNull { (_, height) -> kotlin.math.abs(height - targetHeight) }
        ?: return null

    val trackIndex = candidate.first
    val heightDistance = kotlin.math.abs(candidate.second - targetHeight)
    return heightDistance to TrackSelectionOverride(group.getMediaTrackGroup(), trackIndex)
}

private fun VideoStreamType.toMimeType(): String? {
    return when (this) {
        VideoStreamType.HLS -> MimeTypes.APPLICATION_M3U8
        VideoStreamType.DASH -> MimeTypes.APPLICATION_MPD
        VideoStreamType.PROGRESSIVE -> MimeTypes.VIDEO_MP4
        VideoStreamType.UNKNOWN -> null
    }
}
