package eu.kanade.tachiyomi.ui.video.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.widget.ImageButton
import android.view.View
import android.os.Build
import android.os.Bundle
import android.provider.Browser
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.SettingsChipRow
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
                // Keep controller visibility state stable so the PlayerView listener stays in sync
                // when the current stream changes after applying dub or quality selections.
                var controlsVisible by remember { mutableStateOf(false) }
                var startupOverlayVisible by remember(current.episodeId, current.streamUrl) { mutableStateOf(true) }
                var settingsVisible by remember(current.episodeId, current.streamUrl) { mutableStateOf(false) }
                val currentPlayer = remember(current.episodeId, current.streamUrl) {
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

                                override fun onRenderedFirstFrame() {
                                    startupOverlayVisible = false
                                }

                                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                    viewModel.updateAdaptiveQualities(exoPlayer.availableAdaptiveQualities())
                                }
                            },
                        )
                        if (current.resumePositionMs > 0L) {
                            exoPlayer.seekTo(current.resumePositionMs)
                        }
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
                        onPreviousEpisode = {
                            flushPlaybackState()
                            viewModel.playPreviousEpisode()
                        },
                        onNextEpisode = {
                            flushPlaybackState()
                            viewModel.playNextEpisode()
                        },
                    )
                }

                LaunchedEffect(current.episodeId, current.streamUrl) {
                    startupOverlayVisible = true
                    releasePlayer(persistState = false)
                    player = currentPlayer
                    startProgressSaves(currentPlayer)
                    currentPlayer.applyAdaptiveQuality(current.playback.currentAdaptiveQuality)
                    currentPlayer.playWhenReady = true
                    currentPlayer.prepare()
                }

                LaunchedEffect(current.playback.currentAdaptiveQuality, currentPlayer) {
                    currentPlayer.applyAdaptiveQuality(current.playback.currentAdaptiveQuality)
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
                                useController = true
                                setControllerAutoShow(true)
                                setShowPreviousButton(true)
                                setShowNextButton(true)
                                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                    controlsVisible = visibility == View.VISIBLE
                                })
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                                findViewById<ImageButton?>(androidx.media3.ui.R.id.exo_prev)?.apply {
                                    isEnabled = current.previousEpisodeId != null
                                    alpha = if (current.previousEpisodeId != null) 1f else 0.38f
                                }
                                findViewById<ImageButton?>(androidx.media3.ui.R.id.exo_next)?.apply {
                                    isEnabled = current.nextEpisodeId != null
                                    alpha = if (current.nextEpisodeId != null) 1f else 0.38f
                                }
                            }
                        },
                        update = { playerView ->
                            playerView.player = controllerPlayer
                            playerView.setKeepContentOnPlayerReset(true)
                            playerView.setShowPreviousButton(true)
                            playerView.setShowNextButton(true)
                            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                            playerView.findViewById<ImageButton?>(androidx.media3.ui.R.id.exo_prev)?.apply {
                                isEnabled = current.previousEpisodeId != null
                                alpha = if (current.previousEpisodeId != null) 1f else 0.38f
                            }
                            playerView.findViewById<ImageButton?>(androidx.media3.ui.R.id.exo_next)?.apply {
                                isEnabled = current.nextEpisodeId != null
                                alpha = if (current.nextEpisodeId != null) 1f else 0.38f
                            }
                        },
                    )

                    if (startupOverlayVisible) {
                        VideoPlayerLoadingOverlay(modifier = Modifier.fillMaxSize())
                    }

                    if (current.isSourceSwitching) {
                        VideoPlayerSwitchingOverlay(modifier = Modifier.align(Alignment.TopCenter))
                    }

                    if (controlsVisible) {
                        VideoPlayerInfoOverlay(
                            modifier = Modifier.align(Alignment.TopStart),
                            videoTitle = current.videoTitle,
                            episodeName = current.episodeName,
                            onBack = ::finish,
                            onOpenSettings = { settingsVisible = true },
                            onOpenExternal = { openInExternalPlayer(current.stream) },
                        )
                    }

                    if (settingsVisible) {
                        VideoPlayerSettingsSheet(
                            playback = current.playback,
                            onDismissRequest = { settingsVisible = false },
                            onApplySourceSelection = viewModel::applySourceSelection,
                            onPreviewSourceSelection = viewModel::previewSourceSelection,
                            onSelectAdaptiveQuality = viewModel::selectAdaptiveQuality,
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
    private fun VideoPlayerLoadingOverlay(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.background(Color.Black.copy(alpha = 0.84f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
    }

    @Composable
    private fun VideoPlayerSwitchingOverlay(modifier: Modifier = Modifier) {
        Row(
            modifier = modifier
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
    private fun VideoPlayerInfoOverlay(
        modifier: Modifier = Modifier,
        videoTitle: String,
        episodeName: String,
        onBack: () -> Unit,
        onOpenSettings: () -> Unit,
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
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(MR.strings.label_settings),
                    tint = Color.White,
                )
            }
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

    @Composable
    private fun VideoPlayerSettingsSheet(
        playback: VideoPlaybackUiState,
        onDismissRequest: () -> Unit,
        onApplySourceSelection: (VideoPlaybackSelection) -> Unit,
        onPreviewSourceSelection: (VideoPlaybackSelection) -> Unit,
        onSelectAdaptiveQuality: (VideoAdaptiveQualityPreference) -> Unit,
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val originalSelection = remember(playback.sourceSelection, playback.preferredSourceQualityKey) {
            playback.sourceSelection.copy(sourceQualityKey = playback.preferredSourceQualityKey ?: playback.sourceSelection.sourceQualityKey)
        }
        var draftSelection by remember(originalSelection) { mutableStateOf(originalSelection) }
        val draftDubMatchesActive = draftSelection.dubKey == playback.sourceSelection.dubKey
        val previewSelection = playback.preview.selection
        val previewMatchesDraft = previewSelection?.dubKey == draftSelection.dubKey && !draftDubMatchesActive
        val sourceQualityOptions = if (draftDubMatchesActive) {
            playback.playbackData.sourceQualities
        } else {
            playback.preview.playbackData?.sourceQualities.orEmpty()
        }
        val qualityOptionsLoading = !draftDubMatchesActive &&
            playback.isPreviewLoading &&
            previewSelection?.dubKey == draftSelection.dubKey &&
            sourceQualityOptions.isEmpty()
        val hasPendingSourceChanges = draftSelection != originalSelection
        val streamOptionsEnabled = draftDubMatchesActive &&
            draftSelection.sourceQualityKey == playback.sourceSelection.sourceQualityKey

        LaunchedEffect(draftSelection.dubKey) {
            if (draftDubMatchesActive) {
                onPreviewSourceSelection(playback.sourceSelection)
            } else {
                onPreviewSourceSelection(draftSelection)
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (playback.playbackData.dubs.isNotEmpty()) {
                    item {
                        PlaybackOptionRow(
                            options = playback.playbackData.dubs,
                            titleRes = MR.strings.anime_playback_dub,
                            selectedKey = draftSelection.dubKey,
                            onSelect = { draftSelection = draftSelection.withSelectedDub(it, originalSelection) },
                        )
                    }
                }

                if (playback.streamOptions.size > 1) {
                    item {
                        PlaybackOptionRow(
                            options = playback.streamOptions,
                            titleRes = MR.strings.anime_playback_stream,
                            selectedKey = draftSelection.streamKey,
                            enabled = streamOptionsEnabled,
                            onSelect = { draftSelection = draftSelection.withSelectedStream(it) },
                        )
                    }
                }

                if (qualityOptionsLoading || sourceQualityOptions.isNotEmpty()) {
                    item {
                        if (qualityOptionsLoading) {
                            LoadingPlaybackOptionRow(titleRes = MR.strings.anime_playback_source_quality)
                        } else {
                            PlaybackOptionRow(
                                options = sourceQualityOptions,
                                titleRes = MR.strings.anime_playback_source_quality,
                                selectedKey = draftSelection.sourceQualityKey,
                                onSelect = { draftSelection = draftSelection.withSelectedSourceQuality(it, originalSelection) },
                            )
                        }
                    }
                }

                if (playback.showsAdaptiveQualitySelector) {
                    item {
                        SettingsChipRow(MR.strings.anime_playback_quality) {
                            playback.adaptiveQualities.forEach { option ->
                                FilterChip(
                                    selected = option.preference == playback.currentAdaptiveQuality,
                                    onClick = { onSelectAdaptiveQuality(option.preference) },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Button(
                            enabled = hasPendingSourceChanges,
                            onClick = {
                                onApplySourceSelection(draftSelection)
                                onDismissRequest()
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_apply))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaybackOptionRow(
        options: List<eu.kanade.tachiyomi.source.model.VideoPlaybackOption>,
        titleRes: dev.icerock.moko.resources.StringResource,
        selectedKey: String?,
        enabled: Boolean = true,
        onSelect: (String?) -> Unit,
    ) {
        SettingsChipRow(titleRes) {
            options.forEach { option ->
                FilterChip(
                    enabled = enabled,
                    selected = option.key == selectedKey,
                    onClick = { onSelect(option.key) },
                    label = { Text(option.label) },
                )
            }
        }
    }

    @Composable
    private fun LoadingPlaybackOptionRow(
        titleRes: dev.icerock.moko.resources.StringResource,
    ) {
        SettingsChipRow(titleRes) {
            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(text = "Loading qualities...")
            }
        }
    }

    private fun hideSystemUi() {
        windowInsetsController.hide(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
    }

    private fun openInExternalPlayer(stream: eu.kanade.tachiyomi.source.model.VideoStream) {
        val uri = stream.request.url.toUri()
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
        private const val EXTRA_OWNER_VIDEO_ID = "owner_video_id"
        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val EXTRA_BYPASS_MERGE = "bypass_merge"
        private const val INVALID_ID = -1L
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L

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

private fun eu.kanade.tachiyomi.source.model.VideoStreamType.toExternalMimeType(): String {
    return when (this) {
        eu.kanade.tachiyomi.source.model.VideoStreamType.HLS -> "application/vnd.apple.mpegurl"
        eu.kanade.tachiyomi.source.model.VideoStreamType.DASH -> "application/dash+xml"
        eu.kanade.tachiyomi.source.model.VideoStreamType.PROGRESSIVE -> "video/*"
        eu.kanade.tachiyomi.source.model.VideoStreamType.UNKNOWN -> "video/*"
    }
}

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
