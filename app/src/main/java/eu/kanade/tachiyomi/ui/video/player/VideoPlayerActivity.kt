package eu.kanade.tachiyomi.ui.video.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import android.os.Build
import android.os.Bundle
import android.provider.Browser
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class VideoPlayerActivity : BaseActivity() {

    private val viewModel by viewModels<VideoPlayerViewModel>()
    private val networkHelper: NetworkHelper by lazy { Injekt.get() }
    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }
    private var player by mutableStateOf<ExoPlayer?>(null)
    private var progressSaveJob: Job? = null

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemUi()

        super.onCreate(savedInstanceState)

        val animeId = intent.extras?.getLong(EXTRA_VIDEO_ID, INVALID_ID) ?: INVALID_ID
        val episodeId = intent.extras?.getLong(EXTRA_EPISODE_ID, INVALID_ID) ?: INVALID_ID
        if (animeId == INVALID_ID || episodeId == INVALID_ID) {
            finish()
            return
        }
        viewModel.init(animeId, episodeId)

        setComposeContent {
            val state by viewModel.state.collectAsState()

            VideoPlayerScaffold(
                state = state,
                animeId = animeId,
                episodeId = episodeId,
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
        hideSystemUi()
    }

    @Composable
    private fun VideoPlayerScaffold(
        state: VideoPlayerViewModel.State,
        animeId: Long,
        episodeId: Long,
        networkHelper: NetworkHelper,
    ) {
        HideSystemUiEffect()
        VideoPlayerScreen(
            state = state,
            animeId = animeId,
            episodeId = episodeId,
            networkHelper = networkHelper,
        )
    }

    @Composable
    private fun VideoPlayerScreen(
        state: VideoPlayerViewModel.State,
        animeId: Long,
        episodeId: Long,
        networkHelper: NetworkHelper,
    ) {
        when (val current = state) {
            VideoPlayerViewModel.State.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Loading video stream...",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "Video ID: $animeId\nEpisode ID: $episodeId",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is VideoPlayerViewModel.State.Ready -> {
                val context = LocalContext.current
                var controlsVisible by remember(current.streamUrl) { mutableStateOf(false) }
                val currentPlayer = remember(current.streamUrl) {
                    buildVideoPlayer(
                        context = context,
                        networkHelper = networkHelper,
                        stream = current.stream,
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
                                }
                            },
                        )
                        if (current.resumePositionMs > 0L) {
                            exoPlayer.seekTo(current.resumePositionMs)
                        }
                        exoPlayer.playWhenReady = true
                    }
                }

                LaunchedEffect(context, current.streamUrl) {
                    releasePlayer(persistState = false)
                    player = currentPlayer
                    startProgressSaves(currentPlayer)
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
                                useController = true
                                controllerAutoShow = true
                                setControllerVisibilityListener(PlayerControlView.VisibilityListener { visibility ->
                                    controlsVisible = visibility == View.VISIBLE
                                })
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                        update = { playerView ->
                            playerView.player = player
                        },
                    )

                    if (controlsVisible) {
                        VideoPlayerInfoOverlay(
                            modifier = Modifier.align(Alignment.TopStart),
                            videoTitle = current.videoTitle,
                            episodeName = current.episodeName,
                            onBack = ::finish,
                            onOpenExternal = { openInExternalPlayer(current.stream) },
                        )
                    }
                }
            }
            is VideoPlayerViewModel.State.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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

    @Composable
    private fun VideoPlayerInfoOverlay(
        modifier: Modifier = Modifier,
        videoTitle: String,
        episodeName: String,
        onBack: () -> Unit,
        onOpenExternal: () -> Unit,
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = videoTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episodeName,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = onOpenExternal) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "Open externally",
                    tint = Color.White,
                )
            }
        }
    }

    override fun onPause() {
        player?.pause()
        flushPlaybackState()
        super.onPause()
    }

    override fun onStop() {
        flushPlaybackState()
        super.onStop()
    }

    override fun onDestroy() {
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

    private fun openInExternalPlayer(stream: eu.kanade.tachiyomi.source.model.VideoStream) {
        val uri = android.net.Uri.parse(stream.request.url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, stream.mimeType ?: stream.type.toExternalMimeType())
            putExtra(
                Browser.EXTRA_HEADERS,
                android.os.Bundle().apply {
                    stream.request.headers.forEach { (key, value) ->
                        putString(key, value)
                    }
                },
            )
        }
        try {
            startActivity(Intent.createChooser(intent, null))
        } catch (_: ActivityNotFoundException) {
            toast(stringResource(MR.strings.anime_source_compatibility_note))
        } catch (e: Throwable) {
            toast(e.message ?: stringResource(MR.strings.anime_source_compatibility_note))
        }
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val INVALID_ID = -1L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

        fun newIntent(context: Context, animeId: Long, episodeId: Long): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_VIDEO_ID, animeId)
                putExtra(EXTRA_EPISODE_ID, episodeId)
            }
        }
    }
}

private fun eu.kanade.tachiyomi.source.model.VideoStreamType.toExternalMimeType(): String {
    return when (this) {
        eu.kanade.tachiyomi.source.model.VideoStreamType.HLS -> "application/vnd.apple.mpegurl"
        eu.kanade.tachiyomi.source.model.VideoStreamType.DASH -> "application/dash+xml"
        eu.kanade.tachiyomi.source.model.VideoStreamType.PROGRESSIVE -> "video/*"
        eu.kanade.tachiyomi.source.model.VideoStreamType.UNKNOWN -> "video/*"
    }
}
