package eu.kanade.tachiyomi.ui.video.player

import androidx.lifecycle.SavedStateHandle
import eu.kanade.tachiyomi.source.model.VideoRequest
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoStreamType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.video.model.VideoHistory
import tachiyomi.domain.video.model.VideoHistoryWithRelations
import tachiyomi.domain.video.model.VideoHistoryUpdate
import tachiyomi.domain.video.model.VideoPlaybackState
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.repository.VideoHistoryRepository
import tachiyomi.domain.video.repository.VideoPlaybackStateRepository

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `init exposes resume position from saved playback state`() = runTest(dispatcher) {
        val playbackRepository = FakeVideoPlaybackStateRepository(
            existingState = VideoPlaybackState(
                episodeId = 2L,
                positionMs = 12_345L,
                durationMs = 99_999L,
                completed = false,
                lastWatchedAt = 500L,
            ),
        )
        val historyRepository = FakeVideoHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(videoId = 1L, episodeId = 2L),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(videoId = 1L, episodeId = 2L)
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.episodeId shouldBe 2L
        state.resumePositionMs shouldBe 12_345L
        state.streamUrl shouldBe "https://cdn.example.com/video.m3u8"
        playbackRepository.requestedEpisodeIds shouldBe listOf(2L)
        historyRepository.upserts.size shouldBe 0
    }

    @Test
    fun `persist playback writes playback state and history delta`() = runTest(dispatcher) {
        val playbackRepository = FakeVideoPlaybackStateRepository(existingState = null)
        val historyRepository = FakeVideoHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(videoId = 1L, episodeId = 2L),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(videoId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.persistPlayback(positionMs = 15_000L, durationMs = 100_000L)
        advanceUntilIdle()

        playbackRepository.upserts.single().episodeId shouldBe 2L
        playbackRepository.upserts.single().positionMs shouldBe 15_000L
        playbackRepository.upserts.single().durationMs shouldBe 100_000L
        playbackRepository.upserts.single().completed shouldBe false
        historyRepository.upserts.single().episodeId shouldBe 2L
        historyRepository.upserts.single().sessionWatchedDuration shouldBe 15_000L
    }

    @Test
    fun `reset playback baseline prevents duplicate history after seek`() = runTest(dispatcher) {
        val playbackRepository = FakeVideoPlaybackStateRepository(existingState = null)
        val historyRepository = FakeVideoHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(videoId = 1L, episodeId = 2L),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(videoId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.persistPlayback(positionMs = 30_000L, durationMs = 100_000L)
        advanceUntilIdle()
        viewModel.resetPlaybackBaseline(positionMs = 10_000L)
        viewModel.persistPlayback(positionMs = 12_000L, durationMs = 100_000L)
        advanceUntilIdle()

        historyRepository.upserts.size shouldBe 2
        historyRepository.upserts[0].sessionWatchedDuration shouldBe 30_000L
        historyRepository.upserts[1].sessionWatchedDuration shouldBe 2_000L
    }

    private fun fakeResolver(videoId: Long, episodeId: Long): VideoStreamResolver {
        val video = VideoTitle.create().copy(
            id = videoId,
            source = 99L,
            title = "Video $videoId",
            initialized = true,
            url = "/video/$videoId",
        )
        val episode = VideoEpisode.create().copy(
            id = episodeId,
            videoId = videoId,
            url = "/episode/$episodeId",
            name = "Episode $episodeId",
            episodeNumber = 1.0,
        )
        val stream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/video.m3u8"),
            label = "Auto",
            type = VideoStreamType.HLS,
        )

        return VideoStreamResolver { _, _ ->
            ResolveVideoStream.Result.Success(video = video, episode = episode, stream = stream)
        }
    }

    private class FakeVideoPlaybackStateRepository(
        private val existingState: VideoPlaybackState?,
    ) : VideoPlaybackStateRepository {
        val requestedEpisodeIds = mutableListOf<Long>()
        val upserts = mutableListOf<VideoPlaybackState>()

        override suspend fun getByEpisodeId(episodeId: Long): VideoPlaybackState? {
            requestedEpisodeIds += episodeId
            return existingState?.takeIf { it.episodeId == episodeId }
        }

        override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<VideoPlaybackState?> = emptyFlow()

        override fun getByVideoIdAsFlow(videoId: Long): Flow<List<VideoPlaybackState>> {
            return flowOf(existingState?.let(::listOf) ?: emptyList())
        }

        override suspend fun upsert(state: VideoPlaybackState) {
            error("Not used")
        }

        override suspend fun upsertAndSyncEpisodeState(state: VideoPlaybackState) {
            upserts += state
        }
    }

    private class FakeVideoHistoryRepository : VideoHistoryRepository {
        val upserts = mutableListOf<VideoHistoryUpdate>()

        override fun getHistory(query: String): Flow<List<VideoHistoryWithRelations>> = emptyFlow()

        override suspend fun getLastHistory(): VideoHistoryWithRelations? = error("Not used")

        override fun getLastHistoryAsFlow(): Flow<VideoHistoryWithRelations?> = emptyFlow()

        override suspend fun getTotalWatchedDuration(): Long = error("Not used")

        override suspend fun getHistoryByVideoId(videoId: Long): List<VideoHistory> = error("Not used")

        override suspend fun resetHistory(historyId: Long) = error("Not used")

        override suspend fun resetHistoryByVideoId(videoId: Long) = error("Not used")

        override suspend fun deleteAllHistory(): Boolean = error("Not used")

        override suspend fun upsertHistory(historyUpdate: VideoHistoryUpdate) {
            upserts += historyUpdate
        }
    }
}
