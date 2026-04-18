package eu.kanade.tachiyomi.ui.video.player.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.video.player.coerceToPlaybackDuration
import eu.kanade.tachiyomi.ui.video.player.formatPlaybackTimestamp
import kotlin.math.roundToLong

@Composable
internal fun VideoPlayerTimeline(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    isScrubbing: Boolean,
    onScrubStarted: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveDurationMs = durationMs.coerceAtLeast(0L)
    val clampedPositionMs = positionMs.coerceToPlaybackDuration(effectiveDurationMs)
    val clampedBufferedPositionMs = bufferedPositionMs.coerceToPlaybackDuration(effectiveDurationMs)
    val playedFraction = if (effectiveDurationMs > 0L) {
        clampedPositionMs.toFloat() / effectiveDurationMs.toFloat()
    } else {
        0f
    }
    val bufferedFraction = if (effectiveDurationMs > 0L) {
        clampedBufferedPositionMs.toFloat() / effectiveDurationMs.toFloat()
    } else {
        0f
    }
    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbing) 5.dp else 3.dp,
        animationSpec = tween(140),
        label = "timelineTrackHeight",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (isScrubbing) 14.dp else 10.dp,
        animationSpec = tween(140),
        label = "timelineThumbSize",
    )
    val thumbHaloSize by animateDpAsState(
        targetValue = if (isScrubbing) 24.dp else 0.dp,
        animationSpec = tween(160),
        label = "timelineThumbHaloSize",
    )
    val timelineBarHeight = (if (thumbHaloSize > thumbSize) thumbHaloSize else thumbSize) + 8.dp
    val timeLabel = buildString {
        append(formatPlaybackTimestamp(clampedPositionMs))
        append(" / ")
        append(if (effectiveDurationMs > 0L) formatPlaybackTimestamp(effectiveDurationMs) else "--:--")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isScrubbing) {
            Surface(
                color = Color.Black.copy(alpha = 0.48f),
                shape = CircleShape,
            ) {
                Text(
                    text = formatPlaybackTimestamp(clampedPositionMs),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (isScrubbing) 8.dp else 0.dp),
        ) {
            Text(
                text = timeLabel,
                modifier = Modifier.padding(bottom = 8.dp),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelSmall,
            )

            VideoPlayerTimelineBar(
                playedFraction = playedFraction,
                bufferedFraction = bufferedFraction,
                durationMs = effectiveDurationMs,
                thumbSize = thumbSize,
                thumbHaloSize = thumbHaloSize,
                trackHeight = trackHeight,
                onScrubStarted = onScrubStarted,
                onScrubPositionChange = onScrubPositionChange,
                onScrubFinished = onScrubFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(timelineBarHeight),
            )
        }
    }
}

@Composable
private fun VideoPlayerTimelineBar(
    playedFraction: Float,
    bufferedFraction: Float,
    durationMs: Long,
    thumbSize: Dp,
    thumbHaloSize: Dp,
    trackHeight: Dp,
    onScrubStarted: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedPlayedFraction = playedFraction.coerceIn(0f, 1f)
    val clampedBufferedFraction = bufferedFraction.coerceIn(clampedPlayedFraction, 1f)
    val maxThumbSize = if (thumbHaloSize > thumbSize) thumbHaloSize else thumbSize
    val latestOnScrubStarted by rememberUpdatedState(onScrubStarted)
    val latestOnScrubPositionChange by rememberUpdatedState(onScrubPositionChange)
    val latestOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestMaxThumbSize by rememberUpdatedState(maxThumbSize)
    val activeTrackColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val durationMs = latestDurationMs
                if (durationMs <= 0L) {
                    down.consume()
                    return@awaitEachGesture
                }

                val pointerId = down.id

                fun updateScrubPosition(touchX: Float) {
                    latestOnScrubPositionChange(
                        timelinePositionFromTouch(
                            touchX = touchX,
                            widthPx = size.width,
                            thumbInsetPx = latestMaxThumbSize.toPx() / 2f,
                            durationMs = latestDurationMs,
                        ),
                    )
                }

                latestOnScrubStarted()
                updateScrubPosition(down.position.x)
                down.consume()

                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        updateScrubPosition(change.position.x)
                        change.consume()
                        if (!change.pressed) {
                            break
                        }
                    }
                } finally {
                    latestOnScrubFinished()
                }
            }
        },
    ) {
        val thumbInsetPx = maxThumbSize.toPx() / 2f
        val centerY = size.height / 2f
        val trackStartX = thumbInsetPx
        val trackEndX = size.width - thumbInsetPx
        val trackWidthPx = (trackEndX - trackStartX).coerceAtLeast(0f)
        val playedEndX = trackStartX + (trackWidthPx * clampedPlayedFraction)
        val bufferedEndX = trackStartX + (trackWidthPx * clampedBufferedFraction)
        val strokeWidthPx = trackHeight.toPx()

        drawLine(
            color = Color.White.copy(alpha = 0.28f),
            start = Offset(trackStartX, centerY),
            end = Offset(trackEndX, centerY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.42f),
            start = Offset(trackStartX, centerY),
            end = Offset(bufferedEndX, centerY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = activeTrackColor,
            start = Offset(trackStartX, centerY),
            end = Offset(playedEndX, centerY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
        if (thumbHaloSize > 0.dp) {
            drawCircle(
                color = activeTrackColor.copy(alpha = 0.24f),
                radius = thumbHaloSize.toPx() / 2f,
                center = Offset(playedEndX, centerY),
            )
        }
        drawCircle(
            color = activeTrackColor,
            radius = thumbSize.toPx() / 2f,
            center = Offset(playedEndX, centerY),
        )
    }
}

private fun timelinePositionFromTouch(
    touchX: Float,
    widthPx: Int,
    thumbInsetPx: Float,
    durationMs: Long,
): Long {
    val trackWidthPx = (widthPx.toFloat() - (thumbInsetPx * 2f)).coerceAtLeast(1f)
    val fraction = ((touchX - thumbInsetPx) / trackWidthPx).coerceIn(0f, 1f)
    return (durationMs * fraction)
        .roundToLong()
        .coerceToPlaybackDuration(durationMs)
}
