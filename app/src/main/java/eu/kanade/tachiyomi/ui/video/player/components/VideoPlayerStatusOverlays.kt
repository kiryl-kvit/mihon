package eu.kanade.tachiyomi.ui.video.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerSideGestureFeedbackState
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerSideGestureType
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private const val SIDE_GESTURE_FEEDBACK_VISIBLE_DURATION_MS = 900L

@Composable
internal fun VideoPlayerLoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.84f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
internal fun VideoPlayerSwitchingOverlay(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 24.dp)
            .background(Color.Black.copy(alpha = 0.72f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = Color.White,
            strokeWidth = 2.dp,
        )
        Text(
            text = "Switching source...",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun VideoPlayerSideGestureFeedback(
    feedbackState: VideoPlayerSideGestureFeedbackState?,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayedFeedback by remember { mutableStateOf<VideoPlayerSideGestureFeedbackState?>(null) }
    var visible by remember { mutableStateOf(false) }
    val latestFeedbackState by rememberUpdatedState(feedbackState)
    val latestOnDismissed by rememberUpdatedState(onDismissed)

    LaunchedEffect(feedbackState?.sequence) {
        if (feedbackState == null) {
            visible = false
            displayedFeedback = null
            return@LaunchedEffect
        }

        displayedFeedback = feedbackState
        visible = true
        delay(SIDE_GESTURE_FEEDBACK_VISIBLE_DURATION_MS)
        if (latestFeedbackState?.sequence == feedbackState.sequence) {
            visible = false
            delay(120L)
            if (latestFeedbackState?.sequence == feedbackState.sequence) {
                displayedFeedback = null
                latestOnDismissed()
            }
        }
    }

    Box(modifier = modifier) {
        val activeFeedback = displayedFeedback ?: return@Box
        val alignment = when (activeFeedback.type) {
            VideoPlayerSideGestureType.Brightness -> Alignment.CenterStart
            VideoPlayerSideGestureType.Volume -> Alignment.CenterEnd
        }
        VideoPlayerChromeContainer(
            modifier = Modifier.fillMaxSize(),
            matchParentHeight = true,
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier
                    .align(alignment)
                    .padding(horizontal = 12.dp),
                enter = fadeIn(animationSpec = tween(100)),
                exit = fadeOut(animationSpec = tween(120)),
            ) {
                VideoPlayerSideGestureFeedbackCard(feedbackState = activeFeedback)
            }
        }
    }
}

@Composable
internal fun VideoPlayerLockedOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoPlayerTransportButton(
        onClick = onUnlock,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = stringResource(MR.strings.anime_playback_unlock_controls),
                tint = Color.White,
            )
        },
    )
}

private val sideGestureIndicatorHeight = 122.dp
private val sideGestureIndicatorWidth = 6.dp

@Composable
private fun VideoPlayerSideGestureFeedbackCard(
    feedbackState: VideoPlayerSideGestureFeedbackState,
    modifier: Modifier = Modifier,
) {
    val progress = if (feedbackState.maxLevel > 0) {
        feedbackState.level / feedbackState.maxLevel.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)
    val icon = when (feedbackState.type) {
        VideoPlayerSideGestureType.Brightness -> Icons.Outlined.Brightness6
        VideoPlayerSideGestureType.Volume -> {
            if (feedbackState.level ==
                0
            ) {
                Icons.AutoMirrored.Outlined.VolumeOff
            } else {
                Icons.AutoMirrored.Outlined.VolumeUp
            }
        }
    }
    val isIconLeading = feedbackState.type == VideoPlayerSideGestureType.Brightness

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isIconLeading) {
            SideGestureIcon(icon = icon)
        }
        VerticalGestureProgressBar(progress = progress)
        if (isIconLeading) {
            SideGestureIcon(icon = icon)
        }
    }
}

@Composable
private fun SideGestureIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.28f), shape = RoundedCornerShape(999.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun VerticalGestureProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = sideGestureIndicatorWidth, height = sideGestureIndicatorHeight)
            .background(Color.White.copy(alpha = 0.28f), shape = RoundedCornerShape(999.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = sideGestureIndicatorWidth,
                    height = sideGestureIndicatorHeight * progress.coerceIn(0f, 1f),
                )
                .background(Color.White, shape = RoundedCornerShape(999.dp)),
        )
    }
}
