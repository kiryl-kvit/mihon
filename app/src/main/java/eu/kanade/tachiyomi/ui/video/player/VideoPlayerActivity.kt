package eu.kanade.tachiyomi.ui.video.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.video.player.components.VideoPlayerLoadingOverlay
import eu.kanade.tachiyomi.ui.video.player.components.VideoPlayerOverlay
import eu.kanade.tachiyomi.ui.video.player.components.VideoPlayerSettingsSheet
import eu.kanade.tachiyomi.ui.video.player.components.VideoPlayerSwitchingOverlay
import eu.kanade.tachiyomi.ui.video.player.components.VideoSubtitleEditorOverlay
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mihon.core.common.CustomPreferences
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.roundToInt

class VideoPlayerActivity : BaseActivity() {

    private val viewModel by viewModels<VideoPlayerViewModel>()
    private val networkHelper: NetworkHelper by lazy { Injekt.get() }
    private val customPreferences: CustomPreferences by lazy { Injekt.get() }
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }
    private var player by mutableStateOf<ExoPlayer?>(null)
    private var progressSaveJob: Job? = null
    private var supportsPictureInPicture = false
    private var pictureInPictureEnabled by mutableStateOf(false)
    private var isInPictureInPictureModeState by mutableStateOf(false)
    private var pendingPictureInPictureOnPause = false
    private var latestPlaybackSnapshot = VideoPlayerPlaybackSnapshot()
    private var playbackBrightnessLevel by mutableStateOf(DEFAULT_GESTURE_LEVEL)
    private var playbackBrightnessOverlayValue by mutableStateOf(0)
    private var playbackVolumeLevel by mutableStateOf(0)
    private var playbackVolumeMaxLevel by mutableStateOf(1)
    private var pictureInPictureActionReceiverRegistered = false
    private var brightnessObserverRegistered = false
    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            if (isUsingSystemBrightness()) {
                syncPlaybackBrightnessState()
            }
        }
    }
    private val pictureInPictureActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PICTURE_IN_PICTURE_TOGGLE_PLAYBACK -> togglePlaybackFromPictureInPicture()
            }
        }
    }

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemUi()

        super.onCreate(savedInstanceState)

        supportsPictureInPicture = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        pictureInPictureEnabled = customPreferences.enableAnimePictureInPicture.get()
        isInPictureInPictureModeState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isInPictureInPictureMode
        } else {
            false
        }
        syncPlaybackBrightnessState()
        syncPlaybackVolumeState()
        registerBrightnessObserverIfNeeded()
        registerPictureInPictureActionReceiverIfNeeded()

        val animeId = intent.extras?.getLong(EXTRA_VIDEO_ID, INVALID_ID) ?: INVALID_ID
        val ownerAnimeId = intent.extras?.getLong(EXTRA_OWNER_VIDEO_ID, animeId) ?: animeId
        val episodeId = intent.extras?.getLong(EXTRA_EPISODE_ID, INVALID_ID) ?: INVALID_ID
        val bypassMerge = intent.extras?.getBoolean(EXTRA_BYPASS_MERGE, false) ?: false
        if (animeId == INVALID_ID || episodeId == INVALID_ID) {
            finish()
            return
        }
        viewModel.init(animeId, episodeId, ownerAnimeId, bypassMerge)

        setComposeContent {
            val state by viewModel.state.collectAsState()

            VideoPlayerScaffold(
                state = state,
                networkHelper = networkHelper,
            )
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onResume() {
        super.onResume()
        pictureInPictureEnabled = customPreferences.enableAnimePictureInPicture.get()
        pendingPictureInPictureOnPause = false
        syncPlaybackBrightnessState()
        syncPlaybackVolumeState(player)
        hideSystemUi()
    }

    @Composable
    private fun VideoPlayerScaffold(
        state: VideoPlayerViewModel.State,
        networkHelper: NetworkHelper,
    ) {
        HideSystemUiEffect()
        VideoPlayerScreen(
            state = state,
            networkHelper = networkHelper,
        )
    }

    @Composable
    @OptIn(markerClass = [UnstableApi::class])
    private fun VideoPlayerScreen(
        state: VideoPlayerViewModel.State,
        networkHelper: NetworkHelper,
    ) {
        when (val current = state) {
            VideoPlayerViewModel.State.Loading -> {
                VideoPlayerLoadingOverlay(modifier = Modifier.fillMaxSize())
            }
            is VideoPlayerViewModel.State.Ready -> {
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.events.collectLatest { event ->
                        when (event) {
                            is VideoPlayerViewModel.Event.ShowMessage -> toast(event.message)
                            is VideoPlayerViewModel.Event.ShowPreviewMessage -> toast(event.message)
                        }
                    }
                }

                val subtitlePayloadKey = remember(current.playback.subtitles) {
                    current.playback.subtitles.joinToString(separator = "||") { subtitleChoiceKey(it) }
                }
                var controlsVisible by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(true) }
                var startupOverlayVisible by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(true) }
                var settingsVisible by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(false) }
                var subtitleEditorVisible by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(false) }
                var subtitleEditorDraft by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf(current.playback.subtitleAppearance)
                }
                var resumePlaybackAfterSubtitleEditor by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(false) }
                var isScrubbing by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(false) }
                var scrubPositionMs by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf(current.resumePositionMs.coerceAtLeast(0L))
                }
                var playbackSnapshot by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf(
                        VideoPlayerPlaybackSnapshot(
                            positionMs = current.resumePositionMs.coerceAtLeast(0L),
                        ),
                    )
                }
                var controllerInteractionSequence by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(0L) }
                var seekFeedbackSequence by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(0L) }
                var seekFeedbackState by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf<VideoPlayerSeekFeedbackState?>(null)
                }
                var ignoreNextGestureSeekTapUp by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf(false)
                }
                var controlsLocked by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf(false)
                }
                var sideGestureFeedbackSequence by remember(
                    current.episodeId,
                    current.streamUrl,
                    subtitlePayloadKey,
                ) { mutableStateOf(0L) }
                var sideGestureFeedbackState by remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    mutableStateOf<VideoPlayerSideGestureFeedbackState?>(null)
                }
                val isInPictureInPictureMode = isInPictureInPictureModeState
                val shouldHideChromeForSeekFeedback = seekFeedbackState?.hidePlayerChrome == true
                val hidePlayerChrome = shouldHideChromeForSeekFeedback || isInPictureInPictureMode
                val showPictureInPictureButton =
                    pictureInPictureEnabled && supportsPictureInPicture &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                val onPreviousEpisode = {
                    controlsVisible = true
                    settingsVisible = false
                    subtitleEditorVisible = false
                    isScrubbing = false
                    controllerInteractionSequence += 1L
                    flushPlaybackState()
                    viewModel.playPreviousEpisode()
                }
                val onNextEpisode = {
                    controlsVisible = true
                    settingsVisible = false
                    subtitleEditorVisible = false
                    isScrubbing = false
                    controllerInteractionSequence += 1L
                    flushPlaybackState()
                    viewModel.playNextEpisode()
                }
                val latestPlayback by rememberUpdatedState(current.playback)
                val currentPlayer = remember(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    buildVideoPlayer(
                        context = context,
                        networkHelper = networkHelper,
                        stream = current.stream,
                        subtitles = current.playback.subtitles,
                    ).also { exoPlayer ->
                        exoPlayer.addListener(
                            object : Player.Listener {
                                override fun onPositionDiscontinuity(
                                    oldPosition: Player.PositionInfo,
                                    newPosition: Player.PositionInfo,
                                    reason: Int,
                                ) {
                                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                                        viewModel.resetPlaybackBaseline(newPosition.positionMs)
                                    }
                                    scrubPositionMs = newPosition.positionMs.coerceAtLeast(0L)
                                    playbackSnapshot = exoPlayer.capturePlaybackSnapshot()
                                    latestPlaybackSnapshot = playbackSnapshot
                                    updatePictureInPictureParams(playbackSnapshot)
                                }

                                override fun onRenderedFirstFrame() {
                                    startupOverlayVisible = false
                                }

                                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                    val latestPlaybackState = latestPlayback
                                    exoPlayer.applySubtitleSelection(latestPlaybackState.currentSubtitle)
                                    viewModel.updateAdaptiveQualities(exoPlayer.availableAdaptiveQualities())
                                    viewModel.updateSubtitleOptions(
                                        exoPlayer.availableSubtitleTracks(latestPlaybackState.subtitles),
                                    )
                                    val resolvedSubtitleSelection = exoPlayer.resolveAppliedSubtitleSelection(
                                        latestPlaybackState.currentSubtitle,
                                        latestPlaybackState.subtitles,
                                    )
                                    if (resolvedSubtitleSelection != latestPlaybackState.currentSubtitle) {
                                        viewModel.selectSubtitle(resolvedSubtitleSelection)
                                    }
                                    playbackSnapshot = exoPlayer.capturePlaybackSnapshot()
                                    latestPlaybackSnapshot = playbackSnapshot
                                    updatePictureInPictureParams(playbackSnapshot)
                                }

                                override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                                    syncPlaybackVolumeState(exoPlayer, deviceInfo)
                                }

                                override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                                    syncPlaybackVolumeState(exoPlayer)
                                }

                                override fun onEvents(player: Player, events: Player.Events) {
                                    playbackSnapshot = exoPlayer.capturePlaybackSnapshot()
                                    latestPlaybackSnapshot = playbackSnapshot
                                    updatePictureInPictureParams(playbackSnapshot)
                                }
                            },
                        )
                        if (current.resumePositionMs > 0L) {
                            exoPlayer.seekTo(current.resumePositionMs)
                        }
                        playbackSnapshot = exoPlayer.capturePlaybackSnapshot()
                        latestPlaybackSnapshot = playbackSnapshot
                        updatePictureInPictureParams(playbackSnapshot)
                    }
                }
                val controllerPlayer = remember(
                    currentPlayer,
                    current.previousEpisodeId,
                    current.nextEpisodeId,
                ) {
                    EpisodeNavigationPlayer(
                        player = currentPlayer,
                        hasPreviousEpisode = current.previousEpisodeId != null,
                        hasNextEpisode = current.nextEpisodeId != null,
                        onPreviousEpisode = onPreviousEpisode,
                        onNextEpisode = onNextEpisode,
                    )
                }

                val registerControllerInteraction: (Boolean) -> Unit = { shouldShowControls ->
                    if (shouldShowControls) {
                        controlsVisible = true
                    }
                    controllerInteractionSequence += 1L
                }
                val triggerSeekFeedback: (VideoPlayerSeekDirection, Boolean) -> Unit = { direction, hidePlayerChrome ->
                    seekFeedbackSequence += 1L
                    val now = System.currentTimeMillis()
                    val previousState = seekFeedbackState
                    val isBurstContinuation = previousState != null &&
                        previousState.direction == direction &&
                        now - previousState.updatedAtMillis <= SEEK_FEEDBACK_BURST_WINDOW_MS
                    seekFeedbackState = VideoPlayerSeekFeedbackState(
                        direction = direction,
                        totalSeconds = if (isBurstContinuation) {
                            previousState.totalSeconds + (SEEK_INCREMENT_MS / 1000L).toInt()
                        } else {
                            (SEEK_INCREMENT_MS / 1000L).toInt()
                        },
                        hidePlayerChrome = hidePlayerChrome,
                        sequence = seekFeedbackSequence,
                        updatedAtMillis = now,
                    )
                }
                val triggerSideGestureFeedback: (
                    VideoPlayerSideGestureType,
                    Int,
                    Int,
                ) -> Unit = { type, level, maxLevel ->
                    sideGestureFeedbackSequence += 1L
                    sideGestureFeedbackState = VideoPlayerSideGestureFeedbackState(
                        type = type,
                        level = level,
                        maxLevel = maxLevel,
                        sequence = sideGestureFeedbackSequence,
                    )
                }
                val applyBrightnessLevel: (Int) -> Unit = { level ->
                    val normalizedLevel = level.coerceIn(MIN_GESTURE_LEVEL, MAX_GESTURE_LEVEL)
                    if (playbackBrightnessLevel != normalizedLevel) {
                        playbackBrightnessLevel = normalizedLevel
                    }
                    val overlayValue = brightnessOverlayLevelFor(normalizedLevel)
                    if (playbackBrightnessOverlayValue != overlayValue) {
                        playbackBrightnessOverlayValue = overlayValue
                    }
                    applyPlaybackBrightnessToWindow(normalizedLevel)
                    triggerSideGestureFeedback(
                        VideoPlayerSideGestureType.Brightness,
                        normalizedLevel,
                        MAX_GESTURE_LEVEL,
                    )
                }
                val applyVolumeLevel: (Int) -> Unit = { level ->
                    val maxLevel = playbackVolumeMaxLevel.coerceAtLeast(1)
                    val normalizedLevel = level.coerceIn(0, maxLevel)
                    if (playbackVolumeLevel != normalizedLevel) {
                        playbackVolumeLevel = normalizedLevel
                    }
                    if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) {
                        currentPlayer.setDeviceVolume(normalizedLevel, 0)
                    } else if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME)) {
                        @Suppress("DEPRECATION")
                        currentPlayer.setDeviceVolume(normalizedLevel)
                    } else {
                        val streamMaxVolume = audioManager.safeMusicStreamMaxVolume()
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            normalizedLevel.coerceIn(0, streamMaxVolume),
                            0,
                        )
                    }
                    triggerSideGestureFeedback(
                        VideoPlayerSideGestureType.Volume,
                        normalizedLevel,
                        maxLevel,
                    )
                }
                val seekBy: (Long) -> Unit = { deltaMs ->
                    val durationMs = playbackSnapshot.durationMs.takeIf { it > 0L }
                        ?: currentPlayer.duration.coerceAtLeast(0L)
                    val basePositionMs = if (isScrubbing) scrubPositionMs else currentPlayer.currentPosition
                    val targetPositionMs = (basePositionMs + deltaMs).coerceToPlaybackDuration(durationMs)

                    isScrubbing = false
                    scrubPositionMs = targetPositionMs
                    playbackSnapshot = playbackSnapshot.copy(positionMs = targetPositionMs)
                    currentPlayer.seekTo(targetPositionMs)
                }
                val resolveSeekDirectionFromTap: (Float, Int) -> VideoPlayerSeekDirection? = { tapX, width ->
                    when {
                        width <= 0 -> null
                        tapX <= width / 3f -> VideoPlayerSeekDirection.Backward
                        tapX >= width * 2f / 3f -> VideoPlayerSeekDirection.Forward
                        else -> null
                    }
                }
                val performGestureSeek: (VideoPlayerSeekDirection) -> Unit = { direction ->
                    controlsVisible = false
                    registerControllerInteraction(false)
                    seekBy(
                        if (direction == VideoPlayerSeekDirection.Backward) {
                            -SEEK_INCREMENT_MS
                        } else {
                            SEEK_INCREMENT_MS
                        },
                    )
                    triggerSeekFeedback(direction, true)
                }
                val togglePlayback = {
                    registerControllerInteraction(true)
                    if (playbackSnapshot.playbackEnded) {
                        currentPlayer.seekTo(0L)
                    }
                    if (currentPlayer.isPlaying) {
                        currentPlayer.pause()
                    } else {
                        currentPlayer.play()
                    }
                }

                LaunchedEffect(current.episodeId, current.streamUrl, subtitlePayloadKey) {
                    startupOverlayVisible = true
                    settingsVisible = false
                    subtitleEditorVisible = false
                    subtitleEditorDraft = current.playback.subtitleAppearance
                    resumePlaybackAfterSubtitleEditor = false
                    controlsLocked = false
                    controlsVisible = !isInPictureInPictureMode
                    isScrubbing = false
                    ignoreNextGestureSeekTapUp = false
                    seekFeedbackState = null
                    sideGestureFeedbackState = null
                    syncPlaybackBrightnessState()
                    applyPlaybackBrightnessToWindow(
                        if (isUsingSystemBrightness()) DEFAULT_GESTURE_LEVEL else playbackBrightnessLevel,
                    )
                    syncPlaybackVolumeState(currentPlayer)
                    scrubPositionMs = current.resumePositionMs.coerceAtLeast(0L)
                    playbackSnapshot = VideoPlayerPlaybackSnapshot(positionMs = scrubPositionMs)
                    controllerInteractionSequence += 1L
                    releasePlayer(persistState = false)
                    player = currentPlayer
                    startProgressSaves(currentPlayer)
                    currentPlayer.applyAdaptiveQuality(current.playback.currentAdaptiveQuality)
                    currentPlayer.applySubtitleSelection(current.playback.currentSubtitle)
                    currentPlayer.playWhenReady = true
                    currentPlayer.prepare()
                    playbackSnapshot = currentPlayer.capturePlaybackSnapshot()
                    latestPlaybackSnapshot = playbackSnapshot
                    updatePictureInPictureParams(playbackSnapshot)
                }

                LaunchedEffect(current.playback.currentAdaptiveQuality, currentPlayer) {
                    currentPlayer.applyAdaptiveQuality(current.playback.currentAdaptiveQuality)
                }

                LaunchedEffect(current.playback.currentSubtitle, currentPlayer) {
                    currentPlayer.applySubtitleSelection(current.playback.currentSubtitle)
                }

                LaunchedEffect(currentPlayer) {
                    while (isActive) {
                        playbackSnapshot = currentPlayer.capturePlaybackSnapshot()
                        latestPlaybackSnapshot = playbackSnapshot
                        updatePictureInPictureParams(playbackSnapshot)
                        if (!isScrubbing) {
                            scrubPositionMs = playbackSnapshot.positionMs
                        }
                        delay(
                            if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || isScrubbing) {
                                PLAYBACK_SNAPSHOT_INTERVAL_MS
                            } else {
                                PAUSED_PLAYBACK_SNAPSHOT_INTERVAL_MS
                            },
                        )
                    }
                }

                LaunchedEffect(
                    controlsVisible,
                    controllerInteractionSequence,
                    playbackSnapshot.isPlaying,
                    playbackSnapshot.isLoading,
                    isScrubbing,
                    settingsVisible,
                    startupOverlayVisible,
                ) {
                    if (
                        !controlsVisible ||
                        !playbackSnapshot.isPlaying ||
                        playbackSnapshot.isLoading ||
                        isScrubbing ||
                        settingsVisible ||
                        startupOverlayVisible
                    ) {
                        return@LaunchedEffect
                    }

                    delay(CONTROLS_AUTO_HIDE_DELAY_MS)
                    controlsVisible = false
                }

                val displayedPositionMs = if (isScrubbing) {
                    scrubPositionMs.coerceToPlaybackDuration(playbackSnapshot.durationMs)
                } else {
                    playbackSnapshot.positionMs
                }
                val latestSettingsVisible by rememberUpdatedState(settingsVisible)
                val latestControlsVisible by rememberUpdatedState(controlsVisible)
                val latestControlsLocked by rememberUpdatedState(controlsLocked)
                val latestRegisterControllerInteraction by rememberUpdatedState(registerControllerInteraction)
                val latestResolveSeekDirectionFromTap by rememberUpdatedState(resolveSeekDirectionFromTap)
                val latestPerformGestureSeek by rememberUpdatedState(performGestureSeek)
                val latestSeekGestureModeActive by rememberUpdatedState(shouldHideChromeForSeekFeedback)
                LaunchedEffect(isInPictureInPictureMode) {
                    if (isInPictureInPictureMode) {
                        controlsLocked = false
                        controlsVisible = false
                        settingsVisible = false
                        subtitleEditorVisible = false
                        isScrubbing = false
                        ignoreNextGestureSeekTapUp = false
                        seekFeedbackState = null
                        sideGestureFeedbackState = null
                    } else {
                        hideSystemUi()
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        factory = { androidContext ->
                            PlayerView(androidContext).apply {
                                player = controllerPlayer
                                setKeepContentOnPlayerReset(true)
                                useController = false
                                setEnableComposeSurfaceSyncWorkaround(true)
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                isClickable = true
                                isFocusable = true
                            }
                        },
                        update = { playerView ->
                            playerView.player = controllerPlayer
                            playerView.setKeepContentOnPlayerReset(true)
                            playerView.useController = false
                            playerView.applySubtitleAppearance(
                                appearance = if (subtitleEditorVisible) {
                                    subtitleEditorDraft
                                } else {
                                    current.playback.subtitleAppearance
                                },
                                editorVisible = subtitleEditorVisible,
                            )
                            val touchSlop = ViewConfiguration.get(playerView.context).scaledTouchSlop
                            var sideGestureState: SideGestureState? = null
                            val gestureDetector = GestureDetector(
                                playerView.context,
                                object : GestureDetector.SimpleOnGestureListener() {
                                    override fun onDown(e: MotionEvent): Boolean = true

                                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                        if (latestControlsLocked) {
                                            if (latestControlsVisible) {
                                                controlsVisible = false
                                            } else {
                                                latestRegisterControllerInteraction(true)
                                            }
                                            return true
                                        }
                                        if (!latestSettingsVisible && !subtitleEditorVisible) {
                                            if (latestSeekGestureModeActive) {
                                                return true
                                            }
                                            if (latestControlsVisible) {
                                                controlsVisible = false
                                            } else {
                                                latestRegisterControllerInteraction(true)
                                            }
                                        }
                                        return true
                                    }

                                    override fun onDoubleTap(e: MotionEvent): Boolean {
                                        if (latestControlsLocked) {
                                            return true
                                        }
                                        if (!latestSettingsVisible && !subtitleEditorVisible) {
                                            val direction = latestResolveSeekDirectionFromTap(e.x, playerView.width)
                                            if (direction != null) {
                                                ignoreNextGestureSeekTapUp = true
                                                latestPerformGestureSeek(direction)
                                                return true
                                            }
                                        }
                                        return false
                                    }
                                },
                            )
                            playerView.setOnTouchListener { _, motionEvent ->
                                if (subtitleEditorVisible) {
                                    return@setOnTouchListener true
                                }

                                if (latestControlsLocked) {
                                    val handled = gestureDetector.onTouchEvent(motionEvent)
                                    if (motionEvent.actionMasked == MotionEvent.ACTION_UP) {
                                        playerView.performClick()
                                    }
                                    return@setOnTouchListener handled
                                }

                                when (motionEvent.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        sideGestureState = SideGestureState(
                                            startX = motionEvent.x,
                                            startY = motionEvent.y,
                                            initialLevel = 0,
                                        )
                                    }

                                    MotionEvent.ACTION_MOVE -> {
                                        val currentSideGestureState = sideGestureState
                                        if (
                                            currentSideGestureState != null &&
                                            !latestSettingsVisible &&
                                            !latestSeekGestureModeActive
                                        ) {
                                            val deltaX = motionEvent.x - currentSideGestureState.startX
                                            val deltaY = motionEvent.y - currentSideGestureState.startY
                                            if (
                                                !currentSideGestureState.active &&
                                                abs(deltaY) > touchSlop &&
                                                abs(deltaY) > abs(deltaX) * SIDE_GESTURE_VERTICAL_RATIO
                                            ) {
                                                val gestureType = if (currentSideGestureState.startX <
                                                    playerView.width / 2f
                                                ) {
                                                    VideoPlayerSideGestureType.Brightness
                                                } else {
                                                    VideoPlayerSideGestureType.Volume
                                                }
                                                sideGestureState = currentSideGestureState.copy(
                                                    type = gestureType,
                                                    initialLevel = if (gestureType ==
                                                        VideoPlayerSideGestureType.Brightness
                                                    ) {
                                                        playbackBrightnessLevel
                                                    } else {
                                                        playbackVolumeLevel
                                                    },
                                                    active = true,
                                                )
                                            }

                                            val activeSideGestureState = sideGestureState
                                            if (activeSideGestureState?.active == true) {
                                                val dragFraction = (
                                                    (activeSideGestureState.startY - motionEvent.y) /
                                                        playerView.height.coerceAtLeast(1)
                                                    ).coerceIn(-1f, 1f)
                                                when (activeSideGestureState.type) {
                                                    VideoPlayerSideGestureType.Brightness -> {
                                                        val targetLevel = activeSideGestureState.initialLevel +
                                                            (dragFraction * MAX_GESTURE_LEVEL).toInt()
                                                        applyBrightnessLevel(targetLevel)
                                                    }

                                                    VideoPlayerSideGestureType.Volume -> {
                                                        val targetLevel = activeSideGestureState.initialLevel +
                                                            (
                                                                dragFraction * playbackVolumeMaxLevel.coerceAtLeast(
                                                                    1,
                                                                )
                                                                ).toInt()
                                                        applyVolumeLevel(targetLevel)
                                                    }

                                                    null -> Unit
                                                }
                                                controlsVisible = false
                                                latestRegisterControllerInteraction(false)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }

                                    MotionEvent.ACTION_UP,
                                    MotionEvent.ACTION_CANCEL,
                                    -> {
                                        if (sideGestureState?.active == true) {
                                            sideGestureState = null
                                            if (motionEvent.actionMasked == MotionEvent.ACTION_UP) {
                                                playerView.performClick()
                                            }
                                            return@setOnTouchListener true
                                        }
                                        sideGestureState = null
                                    }
                                }

                                val handled = gestureDetector.onTouchEvent(motionEvent)
                                if (
                                    !latestSettingsVisible &&
                                    latestSeekGestureModeActive &&
                                    motionEvent.actionMasked == MotionEvent.ACTION_UP
                                ) {
                                    val direction = latestResolveSeekDirectionFromTap(motionEvent.x, playerView.width)
                                    if (direction != null) {
                                        if (ignoreNextGestureSeekTapUp) {
                                            ignoreNextGestureSeekTapUp = false
                                        } else {
                                            latestPerformGestureSeek(direction)
                                        }
                                        return@setOnTouchListener true
                                    }
                                }
                                if (motionEvent.actionMasked == MotionEvent.ACTION_UP && !handled) {
                                    playerView.performClick()
                                }
                                handled
                            }
                        },
                    )

                    ReaderContentOverlay(
                        brightness = playbackBrightnessOverlayValue,
                        color = null,
                        colorBlendMode = null,
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (!isInPictureInPictureMode && !subtitleEditorVisible) {
                        VideoPlayerOverlay(
                            visible = controlsVisible,
                            locked = controlsLocked,
                            videoTitle = current.videoTitle,
                            episodeName = current.episodeName,
                            playbackSnapshot = playbackSnapshot,
                            displayedPositionMs = displayedPositionMs,
                            isScrubbing = isScrubbing,
                            hasPreviousEpisode = current.previousEpisodeId != null,
                            hasNextEpisode = current.nextEpisodeId != null,
                            seekFeedbackState = seekFeedbackState,
                            sideGestureFeedbackState = sideGestureFeedbackState,
                            hideChromeForSeekFeedback = hidePlayerChrome,
                            onSeekFeedbackDismissed = {
                                ignoreNextGestureSeekTapUp = false
                                seekFeedbackState = null
                            },
                            onSideGestureFeedbackDismissed = {
                                sideGestureFeedbackState = null
                            },
                            onBack = ::finish,
                            showPictureInPictureButton = showPictureInPictureButton,
                            onEnterPictureInPicture = ::enterPictureInPictureFromControls,
                            onToggleLock = {
                                controlsLocked = !controlsLocked
                                controlsVisible = true
                                settingsVisible = false
                                isScrubbing = false
                                registerControllerInteraction(true)
                            },
                            onOpenSettings = {
                                settingsVisible = true
                                registerControllerInteraction(true)
                            },
                            onPreviousEpisode = onPreviousEpisode,
                            onSeekBackward = {
                                registerControllerInteraction(true)
                                seekBy(-SEEK_INCREMENT_MS)
                                triggerSeekFeedback(VideoPlayerSeekDirection.Backward, false)
                            },
                            onTogglePlayback = togglePlayback,
                            onSeekForward = {
                                registerControllerInteraction(true)
                                seekBy(SEEK_INCREMENT_MS)
                                triggerSeekFeedback(VideoPlayerSeekDirection.Forward, false)
                            },
                            onNextEpisode = onNextEpisode,
                            onScrubStarted = {
                                registerControllerInteraction(true)
                                isScrubbing = true
                                scrubPositionMs = playbackSnapshot.positionMs
                            },
                            onScrubPositionChange = { positionMs ->
                                scrubPositionMs = positionMs.coerceToPlaybackDuration(playbackSnapshot.durationMs)
                            },
                            onScrubFinished = {
                                val targetPositionMs = scrubPositionMs.coerceToPlaybackDuration(
                                    playbackSnapshot.durationMs,
                                )
                                isScrubbing = false
                                scrubPositionMs = targetPositionMs
                                playbackSnapshot = playbackSnapshot.copy(positionMs = targetPositionMs)
                                latestPlaybackSnapshot = playbackSnapshot
                                currentPlayer.seekTo(targetPositionMs)
                                registerControllerInteraction(true)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (startupOverlayVisible && !isInPictureInPictureMode && !subtitleEditorVisible) {
                        VideoPlayerLoadingOverlay(modifier = Modifier.fillMaxSize())
                    }

                    if (current.isSourceSwitching && !isInPictureInPictureMode && !subtitleEditorVisible) {
                        VideoPlayerSwitchingOverlay(modifier = Modifier.align(Alignment.TopCenter))
                    }

                    if (settingsVisible && !isInPictureInPictureMode && !subtitleEditorVisible) {
                        VideoPlayerSettingsSheet(
                            playback = current.playback,
                            onDismissRequest = {
                                settingsVisible = false
                                registerControllerInteraction(true)
                            },
                            onApplySourceSelection = { selection ->
                                persistPlayback(currentPlayer)
                                viewModel.applySourceSelection(selection)
                            },
                            onPreviewSourceSelection = viewModel::previewSourceSelection,
                            onSelectAdaptiveQuality = viewModel::selectAdaptiveQuality,
                            onSelectSubtitle = viewModel::selectSubtitle,
                            onOpenSubtitleSettings = {
                                settingsVisible = false
                                subtitleEditorDraft = current.playback.subtitleAppearance
                                resumePlaybackAfterSubtitleEditor = currentPlayer.isPlaying
                                currentPlayer.pause()
                                subtitleEditorVisible = true
                            },
                        )
                    }

                    if (subtitleEditorVisible && !isInPictureInPictureMode) {
                        VideoSubtitleEditorOverlay(
                            draftAppearance = subtitleEditorDraft,
                            previewCues = currentPlayer.subtitlePreviewCues().ifEmpty {
                                listOf(
                                    Cue.Builder()
                                        .setText(stringResource(MR.strings.anime_playback_subtitle_sample))
                                        .build(),
                                )
                            },
                            previewText = currentPlayer.subtitlePreviewText()
                                ?: stringResource(MR.strings.anime_playback_subtitle_sample),
                            onDraftChange = { subtitleEditorDraft = it.normalized() },
                            onDismissRequest = {
                                subtitleEditorVisible = false
                                if (resumePlaybackAfterSubtitleEditor) {
                                    currentPlayer.play()
                                }
                            },
                            onReset = { subtitleEditorDraft = VideoSubtitleAppearance() },
                            onDone = {
                                val normalizedAppearance = subtitleEditorDraft.normalized()
                                subtitleEditorVisible = false
                                viewModel.updateSubtitleAppearance(normalizedAppearance)
                                if (resumePlaybackAfterSubtitleEditor) {
                                    currentPlayer.play()
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            is VideoPlayerViewModel.State.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column {
                        Text(
                            text = "Unable to open video",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = current.message,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        flushPlaybackState()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (
            event.action == android.view.KeyEvent.ACTION_UP &&
            (
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
                )
        ) {
            syncPlaybackVolumeState(player)
        }
        return handled
    }

    override fun onUserLeaveHint() {
        pendingPictureInPictureOnPause = canAutoEnterPictureInPicture()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            pendingPictureInPictureOnPause &&
            !enterPictureInPictureIfAvailable()
        ) {
            pendingPictureInPictureOnPause = false
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPictureInPictureModeState = isInPictureInPictureMode
        pendingPictureInPictureOnPause = false
        if (!isInPictureInPictureMode) {
            hideSystemUi()
        }
    }

    override fun onStop() {
        if (!isInPictureInPictureModeState && !pendingPictureInPictureOnPause) {
            pendingPictureInPictureOnPause = false
            player?.pause()
        }
        flushPlaybackState()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterBrightnessObserver()
        unregisterPictureInPictureActionReceiver()
        resetPlaybackBrightness()
        releasePlayer(persistState = false)
        super.onDestroy()
    }

    private fun startProgressSaves(player: ExoPlayer) {
        progressSaveJob?.cancel()
        progressSaveJob = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                persistPlayback(player)
            }
        }
    }

    private fun flushPlaybackState() {
        player?.let(::persistPlayback)
    }

    private fun persistPlayback(player: ExoPlayer) {
        viewModel.persistPlayback(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
        )
    }

    private fun releasePlayer(persistState: Boolean = true) {
        progressSaveJob?.cancel()
        progressSaveJob = null
        if (persistState) {
            flushPlaybackState()
        }
        player?.release()
        player = null
    }

    @Composable
    private fun HideSystemUiEffect() {
        LaunchedEffect(Unit) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        windowInsetsController.hide(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
    }

    private fun syncPlaybackBrightnessState() {
        val screenBrightness = window.attributes.screenBrightness
        if (screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            playbackBrightnessLevel = readSystemBrightnessLevel()
            playbackBrightnessOverlayValue = 0
        } else {
            playbackBrightnessLevel = when {
                screenBrightness <= MIN_POSITIVE_BRIGHTNESS -> MIN_GESTURE_LEVEL
                else -> (screenBrightness * MAX_GESTURE_LEVEL)
                    .roundToInt()
                    .coerceIn(MIN_GESTURE_LEVEL, MAX_GESTURE_LEVEL)
            }
            playbackBrightnessOverlayValue = brightnessOverlayLevelFor(playbackBrightnessLevel)
        }
    }

    private fun applyPlaybackBrightnessToWindow(level: Int) {
        val normalizedLevel = level.coerceIn(MIN_GESTURE_LEVEL, MAX_GESTURE_LEVEL)
        val screenBrightness = when {
            isUsingSystemBrightness(level) -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            normalizedLevel > 0 -> normalizedLevel / MAX_GESTURE_LEVEL.toFloat()
            else -> MIN_POSITIVE_BRIGHTNESS
        }
        window.attributes = window.attributes.apply {
            this.screenBrightness = screenBrightness
        }
    }

    private fun readSystemBrightnessLevel(): Int {
        val brightnessValue = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(DEFAULT_SYSTEM_BRIGHTNESS)
        return ((brightnessValue / SYSTEM_BRIGHTNESS_MAX.toFloat()) * MAX_GESTURE_LEVEL)
            .roundToInt()
            .coerceIn(MIN_GESTURE_LEVEL, MAX_GESTURE_LEVEL)
    }

    private fun syncPlaybackVolumeState(
        player: ExoPlayer? = null,
        deviceInfo: DeviceInfo? = player?.deviceInfo,
    ) {
        val currentPlayer = player ?: this.player
        val currentDeviceInfo = deviceInfo ?: currentPlayer?.deviceInfo
        val maxLevel = currentDeviceInfo?.maxVolume
            ?.takeIf { it > 0 }
            ?: audioManager.safeMusicStreamMaxVolume()
        playbackVolumeMaxLevel = maxLevel.coerceAtLeast(1)
        playbackVolumeLevel = when {
            currentPlayer != null && currentPlayer.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME) -> {
                currentPlayer.getDeviceVolume().coerceIn(0, playbackVolumeMaxLevel)
            }
            else -> {
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    .coerceIn(0, playbackVolumeMaxLevel)
            }
        }
    }

    private fun resetPlaybackBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun isUsingSystemBrightness(level: Int = playbackBrightnessLevel): Boolean {
        return level == DEFAULT_GESTURE_LEVEL &&
            window.attributes.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    private fun registerBrightnessObserverIfNeeded() {
        if (brightnessObserverRegistered) return

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver,
        )
        brightnessObserverRegistered = true
    }

    private fun unregisterBrightnessObserver() {
        if (!brightnessObserverRegistered) return

        contentResolver.unregisterContentObserver(brightnessObserver)
        brightnessObserverRegistered = false
    }

    private fun registerPictureInPictureActionReceiverIfNeeded() {
        if (pictureInPictureActionReceiverRegistered) return

        ContextCompat.registerReceiver(
            this,
            pictureInPictureActionReceiver,
            IntentFilter(ACTION_PICTURE_IN_PICTURE_TOGGLE_PLAYBACK),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        pictureInPictureActionReceiverRegistered = true
    }

    private fun unregisterPictureInPictureActionReceiver() {
        if (!pictureInPictureActionReceiverRegistered) return

        unregisterReceiver(pictureInPictureActionReceiver)
        pictureInPictureActionReceiverRegistered = false
    }

    private fun togglePlaybackFromPictureInPicture() {
        val currentPlayer = player ?: return

        if (latestPlaybackSnapshot.playbackEnded) {
            currentPlayer.seekTo(0L)
        }

        if (currentPlayer.isPlaying) {
            currentPlayer.pause()
        } else {
            currentPlayer.play()
        }

        latestPlaybackSnapshot = currentPlayer.capturePlaybackSnapshot()
        updatePictureInPictureParams(latestPlaybackSnapshot)
    }

    private fun enterPictureInPictureFromControls() {
        enterPictureInPictureIfAvailable()
    }

    private fun enterPictureInPictureIfAvailable(): Boolean {
        if (!canUsePictureInPicture()) return false

        val params = buildPictureInPictureParams(latestPlaybackSnapshot)
        pendingPictureInPictureOnPause = true
        setPictureInPictureParams(params)
        return enterPictureInPictureMode(params).also { entered ->
            if (!entered) {
                pendingPictureInPictureOnPause = false
            }
        }
    }

    private fun canAutoEnterPictureInPicture(): Boolean {
        return canUsePictureInPicture() &&
            latestPlaybackSnapshot.isPlaying &&
            !latestPlaybackSnapshot.isLoading
    }

    private fun canUsePictureInPicture(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            supportsPictureInPicture &&
            pictureInPictureEnabled &&
            !isInPictureInPictureModeState
    }

    private fun updatePictureInPictureParams(snapshot: VideoPlayerPlaybackSnapshot) {
        if (!supportsPictureInPicture || !pictureInPictureEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        setPictureInPictureParams(buildPictureInPictureParams(snapshot))
    }

    private fun buildPictureInPictureParams(snapshot: VideoPlayerPlaybackSnapshot): PictureInPictureParams {
        val paramsBuilder = PictureInPictureParams.Builder()
            .setAspectRatio(resolvePictureInPictureAspectRatio(snapshot))
            .setActions(buildPictureInPictureActions(snapshot))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            paramsBuilder.setAutoEnterEnabled(snapshot.isPlaying && !snapshot.isLoading)
        }

        return paramsBuilder.build()
    }

    private fun resolvePictureInPictureAspectRatio(snapshot: VideoPlayerPlaybackSnapshot): Rational {
        val videoSize = player?.videoSize
        val width = videoSize?.width ?: 0
        val height = videoSize?.height ?: 0

        return if (width > 0 && height > 0) {
            Rational(width, height)
        } else if (snapshot.durationMs > 0L) {
            DEFAULT_PICTURE_IN_PICTURE_ASPECT_RATIO
        } else {
            DEFAULT_PICTURE_IN_PICTURE_ASPECT_RATIO
        }
    }

    private fun buildPictureInPictureActions(snapshot: VideoPlayerPlaybackSnapshot): List<RemoteAction> {
        val isPlaying = snapshot.isPlaying && !snapshot.playbackEnded
        val title = if (isPlaying) {
            stringResource(MR.strings.action_pause)
        } else {
            stringResource(MR.strings.action_play)
        }
        val iconRes = if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_PICTURE_IN_PICTURE_TOGGLE_PLAYBACK).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return listOf(
            RemoteAction(
                Icon.createWithResource(this, iconRes),
                title,
                title,
                pendingIntent,
            ),
        )
    }

    companion object {
        private const val ACTION_PICTURE_IN_PICTURE_TOGGLE_PLAYBACK =
            "eu.kanade.tachiyomi.ui.video.player.action.TOGGLE_PLAYBACK"
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_OWNER_VIDEO_ID = "owner_video_id"
        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val EXTRA_BYPASS_MERGE = "bypass_merge"
        private const val INVALID_ID = -1L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
        private const val CONTROLS_AUTO_HIDE_DELAY_MS = 3_000L
        private const val PLAYBACK_SNAPSHOT_INTERVAL_MS = 250L
        private const val PAUSED_PLAYBACK_SNAPSHOT_INTERVAL_MS = 750L
        private const val SEEK_INCREMENT_MS = 5_000L
        private const val SEEK_FEEDBACK_BURST_WINDOW_MS = 900L
        private const val MIN_GESTURE_LEVEL = 0
        private const val MAX_GESTURE_LEVEL = 100
        private const val DEFAULT_GESTURE_LEVEL = 50
        private const val DEFAULT_SYSTEM_BRIGHTNESS = 127
        private const val SYSTEM_BRIGHTNESS_MAX = 255
        private const val MIN_POSITIVE_BRIGHTNESS = 0.01f
        private const val SIDE_GESTURE_VERTICAL_RATIO = 1.35f
        private val DEFAULT_PICTURE_IN_PICTURE_ASPECT_RATIO = Rational(16, 9)

        fun newIntent(
            context: Context,
            animeId: Long,
            episodeId: Long,
            ownerAnimeId: Long = animeId,
            bypassMerge: Boolean = false,
        ): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_VIDEO_ID, animeId)
                putExtra(EXTRA_OWNER_VIDEO_ID, ownerAnimeId)
                putExtra(EXTRA_EPISODE_ID, episodeId)
                putExtra(EXTRA_BYPASS_MERGE, bypassMerge)
            }
        }
    }
}

private data class SideGestureState(
    val startX: Float,
    val startY: Float,
    val type: VideoPlayerSideGestureType? = null,
    val initialLevel: Int,
    val active: Boolean = false,
)

private fun brightnessOverlayLevelFor(level: Int): Int {
    return if (level <= 0) -75 else 0
}

private fun AudioManager.safeMusicStreamMaxVolume(): Int =
    getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

@OptIn(markerClass = [UnstableApi::class])
private class EpisodeNavigationPlayer(
    player: Player,
    private val hasPreviousEpisode: Boolean,
    private val hasNextEpisode: Boolean,
    private val onPreviousEpisode: () -> Unit,
    private val onNextEpisode: () -> Unit,
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands()
            .buildUpon()
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPreviousEpisode)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousEpisode)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNextEpisode)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextEpisode)
            .removeIf(Player.COMMAND_SEEK_TO_PREVIOUS, !hasPreviousEpisode)
            .removeIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, !hasPreviousEpisode)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT, !hasNextEpisode)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, !hasNextEpisode)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> hasPreviousEpisode
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> hasNextEpisode
            else -> super.isCommandAvailable(command)
        }
    }

    override fun hasPreviousMediaItem(): Boolean = hasPreviousEpisode

    override fun hasNextMediaItem(): Boolean = hasNextEpisode

    override fun seekToPreviousMediaItem() {
        if (hasPreviousEpisode) {
            onPreviousEpisode()
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    override fun seekToPrevious() {
        if (hasPreviousEpisode) {
            onPreviousEpisode()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNextMediaItem() {
        if (hasNextEpisode) {
            onNextEpisode()
        } else {
            super.seekToNextMediaItem()
        }
    }

    override fun seekToNext() {
        if (hasNextEpisode) {
            onNextEpisode()
        } else {
            super.seekToNext()
        }
    }
}
