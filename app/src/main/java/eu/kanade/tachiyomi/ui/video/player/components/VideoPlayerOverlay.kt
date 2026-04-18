package eu.kanade.tachiyomi.ui.video.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerPlaybackSnapshot
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerSeekFeedbackState

private val overlayBarsSlideAnimationSpec = tween<IntOffset>(190)
private val overlayBarsFadeAnimationSpec = tween<Float>(125)
private val overlayCenterFadeAnimationSpec = tween<Float>(110)
private const val OVERLAY_UI_WIDTH_FRACTION = 0.8f

@Composable
internal fun VideoPlayerOverlay(
    visible: Boolean,
    videoTitle: String,
    episodeName: String,
    playbackSnapshot: VideoPlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    isScrubbing: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekFeedbackState: VideoPlayerSeekFeedbackState?,
    hideChromeForSeekFeedback: Boolean,
    onSeekFeedbackDismissed: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onSeekBackward: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekForward: () -> Unit,
    onNextEpisode: () -> Unit,
    onScrubStarted: () -> Unit,
    onScrubPositionChange: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chromeVisible = visible && !hideChromeForSeekFeedback

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = overlayBarsSlideAnimationSpec,
                ) + fadeIn(animationSpec = overlayBarsFadeAnimationSpec),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = overlayBarsSlideAnimationSpec,
                ) + fadeOut(animationSpec = overlayBarsFadeAnimationSpec),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(modifier = Modifier.fillMaxWidth(OVERLAY_UI_WIDTH_FRACTION)) {
                        VideoPlayerTopBar(
                            videoTitle = videoTitle,
                            episodeName = episodeName,
                            onBack = onBack,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = chromeVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = overlayBarsSlideAnimationSpec,
                ) + fadeIn(animationSpec = overlayBarsFadeAnimationSpec),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = overlayBarsSlideAnimationSpec,
                ) + fadeOut(animationSpec = overlayBarsFadeAnimationSpec),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.fillMaxWidth(OVERLAY_UI_WIDTH_FRACTION)) {
                        VideoPlayerTimeline(
                            positionMs = displayedPositionMs,
                            durationMs = playbackSnapshot.durationMs,
                            bufferedPositionMs = playbackSnapshot.bufferedPositionMs,
                            isScrubbing = isScrubbing,
                            onScrubStarted = onScrubStarted,
                            onScrubPositionChange = onScrubPositionChange,
                            onScrubFinished = onScrubFinished,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(animationSpec = overlayCenterFadeAnimationSpec) +
                scaleIn(
                    animationSpec = tween(120),
                    initialScale = 0.94f,
                ),
            exit = fadeOut(animationSpec = overlayCenterFadeAnimationSpec) +
                scaleOut(
                    animationSpec = tween(100),
                    targetScale = 0.97f,
                ),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.24f),
                                Color.Black.copy(alpha = 0.1f),
                                Color.Transparent,
                            ),
                            radius = 380f,
                        ),
                    )
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.fillMaxWidth(OVERLAY_UI_WIDTH_FRACTION)) {
                    VideoPlayerCenterControls(
                        isPlaying = playbackSnapshot.isPlaying,
                        hasPreviousEpisode = hasPreviousEpisode,
                        hasNextEpisode = hasNextEpisode,
                        onPreviousEpisode = onPreviousEpisode,
                        onSeekBackward = onSeekBackward,
                        onTogglePlayback = onTogglePlayback,
                        onSeekForward = onSeekForward,
                        onNextEpisode = onNextEpisode,
                        modifier = Modifier.background(Color.Transparent),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = overlayBarsFadeAnimationSpec),
            exit = fadeOut(animationSpec = overlayBarsFadeAnimationSpec),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.03f),
                                Color.Black.copy(alpha = 0.18f),
                            ),
                        ),
                    ),
            )
        }

        VideoPlayerSeekFeedback(
            feedbackState = seekFeedbackState,
            onDismissed = onSeekFeedbackDismissed,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
