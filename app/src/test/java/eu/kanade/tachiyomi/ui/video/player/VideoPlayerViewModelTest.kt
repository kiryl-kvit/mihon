package eu.kanade.tachiyomi.ui.video.player

import androidx.lifecycle.SavedStateHandle
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.source.model.VideoRequest
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoStreamType
import eu.kanade.tachiyomi.source.model.VideoSubtitle
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.PlayerQualityMode
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository

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
        val playbackRepository = FakeAnimePlaybackStateRepository(
            existingState = AnimePlaybackState(
                episodeId = 2L,
                positionMs = 12_345L,
                durationMs = 99_999L,
                completed = false,
                lastWatchedAt = 500L,
            ),
        )
        val historyRepository = FakeAnimeHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                episodes = listOf(
                    videoEpisode(id = 1L, animeId = 1L, sourceOrder = 0L),
                    videoEpisode(id = 2L, animeId = 1L, sourceOrder = 1L),
                    videoEpisode(id = 3L, animeId = 1L, sourceOrder = 2L),
                ),
            ),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.episodeId shouldBe 2L
        state.previousEpisodeId shouldBe 1L
        state.nextEpisodeId shouldBe 3L
        state.resumePositionMs shouldBe 12_345L
        state.streamUrl shouldBe "https://cdn.example.com/video.m3u8"
        playbackRepository.requestedEpisodeIds shouldBe listOf(2L)
        historyRepository.upserts.size shouldBe 0
    }

    @Test
    fun `persist playback writes playback state and history delta`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
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
    fun `persist playback updates resume position in ready state`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.persistPlayback(positionMs = 45_000L, durationMs = 100_000L)

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.resumePositionMs shouldBe 45_000L
    }

    @Test
    fun `reset playback baseline prevents duplicate history after seek`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
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

    @Test
    fun `session playback speed restores from saved state`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(mapOf("session_playback_speed" to 1.5f)),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.playback.sessionPlaybackSpeed shouldBe 1.5f
    }

    @Test
    fun `play next episode resolves adjacent episode in source order`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = RecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                episodes = listOf(
                    videoEpisode(id = 20L, animeId = 1L, sourceOrder = 2L),
                    videoEpisode(id = 10L, animeId = 1L, sourceOrder = 1L),
                    videoEpisode(id = 30L, animeId = 1L, sourceOrder = 3L),
                ),
            ),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 10L)
        advanceUntilIdle()
        viewModel.playNextEpisode()
        advanceUntilIdle()

        resolver.requests shouldBe listOf(10L, 20L)
        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.episodeId shouldBe 20L
        state.previousEpisodeId shouldBe 10L
        state.nextEpisodeId shouldBe 30L
    }

    @Test
    fun `session playback speed survives next episode navigation`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = RecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                episodes = listOf(
                    videoEpisode(id = 10L, animeId = 1L, sourceOrder = 1L),
                    videoEpisode(id = 20L, animeId = 1L, sourceOrder = 2L),
                ),
            ),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 10L)
        advanceUntilIdle()
        viewModel.updateSessionPlaybackSpeed(1.25f)
        viewModel.playNextEpisode()
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.episodeId shouldBe 20L
        state.playback.sessionPlaybackSpeed shouldBe 1.25f
    }

    @Test
    fun `play next episode uses merged sequence when bypassMerge is false`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = RecordingVideoStreamResolver()
        val getAnimeWithEpisodes = mockk<GetAnimeWithEpisodes>()
        coEvery { getAnimeWithEpisodes.awaitAnime(100L) } returns AnimeTitle.create().copy(
            id = 100L,
            title = "Merged",
            url = "/anime/100",
            episodeFlags = tachiyomi.domain.anime.model.AnimeTitle.EPISODE_SORT_DESC or
                tachiyomi.domain.anime.model.AnimeTitle.EPISODE_SORTING_NUMBER,
        )
        coEvery { getAnimeWithEpisodes.awaitEpisodes(id = 100L, bypassMerge = false) } returns listOf(
            videoEpisode(id = 10L, animeId = 1L, sourceOrder = 1L, episodeNumber = 1.0),
            videoEpisode(id = 30L, animeId = 1L, sourceOrder = 2L, episodeNumber = 2.0),
            videoEpisode(id = 20L, animeId = 2L, sourceOrder = 1L, episodeNumber = 1.0),
        )

        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                episodes = listOf(
                    videoEpisode(id = 10L, animeId = 1L, sourceOrder = 1L),
                    videoEpisode(id = 20L, animeId = 2L, sourceOrder = 1L),
                    videoEpisode(id = 30L, animeId = 1L, sourceOrder = 2L),
                ),
            ),
            getAnimeWithEpisodes = getAnimeWithEpisodes,
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 100L, episodeId = 10L, ownerAnimeId = 1L, bypassMerge = false)
        advanceUntilIdle()
        viewModel.playNextEpisode()
        advanceUntilIdle()

        resolver.requests shouldBe listOf(10L, 30L)
        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.episodeId shouldBe 30L
        state.previousEpisodeId shouldBe 10L
        state.nextEpisodeId shouldBe null
    }

    @Test
    fun `play next episode stays on owner sequence when bypassMerge is true`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = RecordingVideoStreamResolver()
        val getAnimeWithEpisodes = mockk<GetAnimeWithEpisodes>()
        coEvery { getAnimeWithEpisodes.awaitAnime(1L) } returns AnimeTitle.create().copy(
            id = 1L,
            title = "Owner",
            url = "/anime/1",
        )
        coEvery { getAnimeWithEpisodes.awaitEpisodes(id = 1L, bypassMerge = true) } returns listOf(
            videoEpisode(id = 10L, animeId = 1L, sourceOrder = 1L),
            videoEpisode(id = 30L, animeId = 1L, sourceOrder = 2L),
        )

        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                episodes = listOf(
                    videoEpisode(id = 10L, animeId = 1L, sourceOrder = 1L),
                    videoEpisode(id = 20L, animeId = 2L, sourceOrder = 1L),
                    videoEpisode(id = 30L, animeId = 1L, sourceOrder = 2L),
                ),
            ),
            getAnimeWithEpisodes = getAnimeWithEpisodes,
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 100L, episodeId = 10L, ownerAnimeId = 1L, bypassMerge = true)
        advanceUntilIdle()
        viewModel.playNextEpisode()
        advanceUntilIdle()

        resolver.requests shouldBe listOf(10L, 30L)
        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.episodeId shouldBe 30L
        state.previousEpisodeId shouldBe 10L
        state.nextEpisodeId shouldBe null
    }

    @Test
    fun `apply source selection resolves once and preserves resume position`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(
            existingState = AnimePlaybackState(
                episodeId = 2L,
                positionMs = 12_345L,
                durationMs = 60_000L,
                completed = false,
                lastWatchedAt = 500L,
            ),
        )
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository()
        val resolver = RecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.applySourceSelection(
            VideoPlaybackSelection(
                dubKey = "dub-2",
                sourceQualityKey = "1080p",
            ),
        )
        advanceUntilIdle()

        resolver.requests shouldBe listOf(2L, 2L)
        resolver.selections shouldBe listOf(
            null,
            VideoPlaybackSelection(
                dubKey = "dub-2",
                sourceQualityKey = "1080p",
            ),
        )
        preferencesRepository.upserts.single().sourceQualityKey shouldBe "1080p"
        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.resumePositionMs shouldBe 12_345L
    }

    @Test
    fun `apply source selection uses latest persisted position`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(
            existingState = AnimePlaybackState(
                episodeId = 2L,
                positionMs = 12_345L,
                durationMs = 60_000L,
                completed = false,
                lastWatchedAt = 500L,
            ),
        )
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository()
        val resolver = RecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()
        viewModel.persistPlayback(positionMs = 45_000L, durationMs = 60_000L)
        advanceUntilIdle()

        viewModel.applySourceSelection(
            VideoPlaybackSelection(
                dubKey = "dub-2",
                sourceQualityKey = "1080p",
            ),
        )
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.resumePositionMs shouldBe 45_000L
    }

    @Test
    fun `preview source selection updates preview qualities without changing active playback`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = PreviewAwareRecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = RecordingAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.playback.sourceSelection.dubKey shouldBe null
        state.playback.preview.selection shouldBe VideoPlaybackSelection(dubKey = "dub-2")
        state.playback.displayedPlaybackData.sourceQualities.map { it.key } shouldBe listOf("720p", "480p")
        state.playback.preview.subtitles?.map(VideoSubtitle::label) shouldBe listOf("English")
    }

    @Test
    fun `preview source selection reuses cache for repeated dub toggles`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = PreviewAwareRecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = RecordingAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceUntilIdle()
        viewModel.previewSourceSelection(VideoPlaybackSelection())
        advanceUntilIdle()
        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceUntilIdle()

        resolver.selections shouldBe listOf(
            null,
            VideoPlaybackSelection(dubKey = "dub-2"),
        )
    }

    @Test
    fun `preview source selection exposes loading state before qualities arrive`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = DelayedPreviewAwareRecordingVideoStreamResolver(delayMs = 1_000L)
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = RecordingAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceTimeBy(1)

        val loadingState = viewModel.state.value as VideoPlayerViewModel.State.Ready
        loadingState.playback.preview.selection shouldBe VideoPlaybackSelection(dubKey = "dub-2")
        loadingState.playback.preview.isLoading shouldBe true
        loadingState.playback.preview.playbackData shouldBe null
        loadingState.playback.preview.subtitles shouldBe null

        advanceUntilIdle()

        val resolvedState = viewModel.state.value as VideoPlayerViewModel.State.Ready
        resolvedState.playback.preview.isLoading shouldBe false
        resolvedState.playback.preview.playbackData?.sourceQualities?.map { it.key } shouldBe listOf("720p", "480p")
        resolvedState.playback.preview.subtitles?.map(VideoSubtitle::label) shouldBe listOf("English")
    }

    @Test
    fun `preview source selection does not change active subtitle selection`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = PreviewAwareRecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = RecordingAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()
        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.playback.currentSubtitle shouldBe VideoPlayerSubtitleSelection.None
        state.playback.preview.subtitles?.map(VideoSubtitle::label) shouldBe listOf("English")
    }

    @Test
    fun `apply reuses cached preview result for same selection`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository()
        val resolver = PreviewAwareRecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()
        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2", sourceQualityKey = "720p"))
        advanceUntilIdle()

        viewModel.applySourceSelection(VideoPlaybackSelection(dubKey = "dub-2", sourceQualityKey = "720p"))
        advanceUntilIdle()

        resolver.selections shouldBe listOf(
            null,
            VideoPlaybackSelection(dubKey = "dub-2", sourceQualityKey = "720p"),
        )
        preferencesRepository.upserts.last().sourceQualityKey shouldBe "720p"
        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.playback.sourceSelection.dubKey shouldBe "dub-2"
        state.playback.preview.playbackData shouldBe null
    }

    @Test
    fun `cached apply source selection uses latest persisted position`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(
            existingState = AnimePlaybackState(
                episodeId = 2L,
                positionMs = 12_345L,
                durationMs = 60_000L,
                completed = false,
                lastWatchedAt = 500L,
            ),
        )
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository()
        val resolver = PreviewAwareRecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()
        viewModel.previewSourceSelection(VideoPlaybackSelection(dubKey = "dub-2", sourceQualityKey = "720p"))
        advanceUntilIdle()
        viewModel.persistPlayback(positionMs = 45_000L, durationMs = 60_000L)
        advanceUntilIdle()

        viewModel.applySourceSelection(VideoPlaybackSelection(dubKey = "dub-2", sourceQualityKey = "720p"))
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.resumePositionMs shouldBe 45_000L
    }

    @Test
    fun `adaptive quality persistence keeps preferred source quality after fallback resolve`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository(
            existing = AnimePlaybackPreferences(
                animeId = 1L,
                dubKey = null,
                streamKey = null,
                sourceQualityKey = "1080p",
                playerQualityMode = PlayerQualityMode.AUTO,
                playerQualityHeight = null,
                updatedAt = 0L,
            ),
        )
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fallbackQualityResolver(),
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        val readyState = viewModel.state.value as VideoPlayerViewModel.State.Ready
        readyState.playback.sourceSelection.sourceQualityKey shouldBe "720p"
        readyState.playback.preferredSourceQualityKey shouldBe "1080p"

        viewModel.selectAdaptiveQuality(VideoAdaptiveQualityPreference.SpecificHeight(720))
        advanceUntilIdle()

        preferencesRepository.upserts.last().sourceQualityKey shouldBe "1080p"
    }

    @Test
    fun `subtitle appearance persistence keeps existing source preferences`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository(
            existing = AnimePlaybackPreferences(
                animeId = 1L,
                dubKey = "dub-1",
                streamKey = "stream-1",
                sourceQualityKey = "1080p",
                playerQualityMode = PlayerQualityMode.AUTO,
                playerQualityHeight = null,
                updatedAt = 0L,
            ),
        )
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.updateSubtitleAppearance(
            VideoSubtitleAppearance(
                offsetX = 0.12f,
                offsetY = -0.18f,
                textSize = 0.07f,
            ),
        )
        advanceUntilIdle()

        preferencesRepository.upserts.last().dubKey shouldBe null
        preferencesRepository.upserts.last().streamKey shouldBe "Auto"
        preferencesRepository.upserts.last().sourceQualityKey shouldBe null
        preferencesRepository.upserts.last().subtitleOffsetX?.toFloat() shouldBe 0.12f
        preferencesRepository.upserts.last().subtitleOffsetY?.toFloat() shouldBe -0.18f
    }

    @Test
    fun `source selection persistence keeps existing subtitle appearance`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository(
            existing = AnimePlaybackPreferences(
                animeId = 1L,
                dubKey = null,
                streamKey = null,
                sourceQualityKey = "720p",
                playerQualityMode = PlayerQualityMode.AUTO,
                playerQualityHeight = null,
                subtitleOffsetX = 0.15,
                subtitleOffsetY = -0.2,
                subtitleTextSize = 0.08,
                subtitleTextColor = 0xFFFFFF99.toInt(),
                subtitleBackgroundColor = android.graphics.Color.BLACK,
                subtitleBackgroundOpacity = 0.5,
                updatedAt = 0L,
            ),
        )
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = fakeResolver(animeId = 1L, episodeId = 2L),
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.applySourceSelection(VideoPlaybackSelection(dubKey = "dub-2", sourceQualityKey = "1080p"))
        advanceUntilIdle()

        preferencesRepository.upserts.last().subtitleOffsetX?.toFloat() shouldBe 0.15f
        preferencesRepository.upserts.last().subtitleOffsetY?.toFloat() shouldBe -0.2f
        preferencesRepository.upserts.last().subtitleTextSize?.toFloat() shouldBe 0.08f
        preferencesRepository.upserts.last().subtitleBackgroundOpacity?.toFloat() shouldBe 0.5f
    }

    @Test
    fun `apply source selection keeps ready state while switching`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val preferencesRepository = RecordingAnimePlaybackPreferencesRepository()
        val resolver = DelayedRecordingVideoStreamResolver(delayMs = 1_000L)
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = preferencesRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        viewModel.applySourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceTimeBy(1)

        val switchingState = viewModel.state.value as VideoPlayerViewModel.State.Ready
        switchingState.isSourceSwitching shouldBe true

        advanceUntilIdle()

        val finalState = viewModel.state.value as VideoPlayerViewModel.State.Ready
        finalState.isSourceSwitching shouldBe false
    }

    @Test
    fun `init defaults to source subtitle mode when available`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = subtitleAwareResolver(),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.playback.currentSubtitle shouldBe VideoPlayerSubtitleSelection.External(
            VideoSubtitle(
                request = VideoRequest(url = "https://cdn.example.com/subtitles-ru.vtt"),
                label = "Russian",
                language = "ru",
                isDefault = true,
            ),
        )
    }

    @Test
    fun `apply source selection preserves drafted external subtitle`() = runTest(dispatcher) {
        val playbackRepository = FakeAnimePlaybackStateRepository(existingState = null)
        val historyRepository = FakeAnimeHistoryRepository()
        val resolver = PreviewAwareRecordingVideoStreamResolver()
        val viewModel = VideoPlayerViewModel(
            savedState = SavedStateHandle(),
            resolveVideoStream = resolver,
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(episodes = emptyList()),
            videoPlaybackStateRepository = playbackRepository,
            videoHistoryRepository = historyRepository,
            resolveDispatcher = dispatcher,
            persistenceDispatcher = dispatcher,
        )

        viewModel.init(animeId = 1L, episodeId = 2L)
        advanceUntilIdle()

        val subtitle = VideoSubtitle(
            request = VideoRequest(url = "https://cdn.example.com/english.vtt"),
            label = "English",
            language = "en",
            isDefault = true,
        )
        viewModel.selectSubtitle(VideoPlayerSubtitleSelection.External(subtitle))
        viewModel.applySourceSelection(VideoPlaybackSelection(dubKey = "dub-2"))
        advanceUntilIdle()

        val state = viewModel.state.value as VideoPlayerViewModel.State.Ready
        state.playback.currentSubtitle shouldBe VideoPlayerSubtitleSelection.External(subtitle)
    }

    private fun videoEpisode(
        id: Long,
        animeId: Long,
        sourceOrder: Long,
        episodeNumber: Double = sourceOrder.toDouble(),
    ): AnimeEpisode {
        return AnimeEpisode.create().copy(
            id = id,
            animeId = animeId,
            url = "/episode/$id",
            name = "Episode $id",
            sourceOrder = sourceOrder,
            episodeNumber = episodeNumber,
        )
    }

    private fun fakeResolver(animeId: Long, episodeId: Long): VideoStreamResolver {
        val video = AnimeTitle.create().copy(
            id = animeId,
            source = 99L,
            title = "Video $animeId",
            initialized = true,
            url = "/video/$animeId",
        )
        val episode = AnimeEpisode.create().copy(
            id = episodeId,
            animeId = animeId,
            url = "/episode/$episodeId",
            name = "Episode $episodeId",
            episodeNumber = 1.0,
        )
        val stream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/video.m3u8"),
            label = "Auto",
            type = VideoStreamType.HLS,
        )

        return object : VideoStreamResolver {
            override suspend fun invoke(
                animeId: Long,
                episodeId: Long,
                ownerAnimeId: Long,
                selection: VideoPlaybackSelection?,
            ): ResolveVideoStream.Result {
                return ResolveVideoStream.Result.Success(
                    visibleAnime = video,
                    ownerAnime = video,
                    episode = episode,
                    playbackData = VideoPlaybackData(
                        selection = selection ?: VideoPlaybackSelection(),
                        streams = listOf(stream),
                    ),
                    stream = stream,
                    subtitles = emptyList(),
                    savedPreferences = AnimePlaybackPreferences(
                        animeId = animeId,
                        dubKey = null,
                        streamKey = null,
                        sourceQualityKey = null,
                        playerQualityMode = PlayerQualityMode.AUTO,
                        playerQualityHeight = null,
                        updatedAt = 0L,
                    ),
                )
            }
        }
    }

    private fun fallbackQualityResolver(): VideoStreamResolver {
        val video = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Video 1",
            initialized = true,
            url = "/video/1",
        )
        val episode = AnimeEpisode.create().copy(
            id = 2L,
            animeId = 1L,
            url = "/episode/2",
            name = "Episode 2",
            episodeNumber = 1.0,
        )
        val fallbackStream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/video-720.m3u8"),
            label = "720p",
            type = VideoStreamType.HLS,
        )

        return object : VideoStreamResolver {
            override suspend fun invoke(
                animeId: Long,
                episodeId: Long,
                ownerAnimeId: Long,
                selection: VideoPlaybackSelection?,
            ): ResolveVideoStream.Result {
                return ResolveVideoStream.Result.Success(
                    visibleAnime = video,
                    ownerAnime = video,
                    episode = episode,
                    playbackData = VideoPlaybackData(
                        selection = VideoPlaybackSelection(
                            dubKey = selection?.dubKey,
                            sourceQualityKey = "720p",
                        ),
                        sourceQualities = listOf(
                            eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "720p", label = "720p"),
                            eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "480p", label = "480p"),
                        ),
                        streams = listOf(fallbackStream),
                    ),
                    stream = fallbackStream,
                    subtitles = emptyList(),
                    savedPreferences = AnimePlaybackPreferences(
                        animeId = animeId,
                        dubKey = null,
                        streamKey = null,
                        sourceQualityKey = "1080p",
                        playerQualityMode = PlayerQualityMode.AUTO,
                        playerQualityHeight = null,
                        updatedAt = 0L,
                    ),
                )
            }
        }
    }

    private fun subtitleAwareResolver(): VideoStreamResolver {
        val video = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Video 1",
            initialized = true,
            url = "/video/1",
        )
        val episode = AnimeEpisode.create().copy(
            id = 2L,
            animeId = 1L,
            url = "/episode/2",
            name = "Episode 2",
            episodeNumber = 1.0,
        )
        val stream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/video.m3u8"),
            label = "Auto",
            type = VideoStreamType.HLS,
        )
        val subtitle = VideoSubtitle(
            request = VideoRequest(url = "https://cdn.example.com/subtitles-ru.vtt"),
            label = "Russian",
            language = "ru",
            isDefault = true,
        )

        return object : VideoStreamResolver {
            override suspend fun invoke(
                animeId: Long,
                episodeId: Long,
                ownerAnimeId: Long,
                selection: VideoPlaybackSelection?,
            ): ResolveVideoStream.Result {
                return ResolveVideoStream.Result.Success(
                    visibleAnime = video,
                    ownerAnime = video,
                    episode = episode,
                    playbackData = VideoPlaybackData(
                        selection = selection ?: VideoPlaybackSelection(),
                        streams = listOf(stream),
                    ),
                    stream = stream,
                    subtitles = listOf(subtitle),
                    savedPreferences = AnimePlaybackPreferences(
                        animeId = animeId,
                        dubKey = null,
                        streamKey = null,
                        sourceQualityKey = null,
                        playerQualityMode = PlayerQualityMode.AUTO,
                        playerQualityHeight = null,
                        updatedAt = 0L,
                    ),
                )
            }
        }
    }

    private class FakeAnimePlaybackPreferencesRepository : AnimePlaybackPreferencesRepository {
        override suspend fun getByAnimeId(animeId: Long): AnimePlaybackPreferences? = null

        override fun getByAnimeIdAsFlow(animeId: Long): Flow<AnimePlaybackPreferences?> = emptyFlow()

        override suspend fun upsert(preferences: AnimePlaybackPreferences) = Unit
    }

    private class RecordingAnimePlaybackPreferencesRepository(
        private val existing: AnimePlaybackPreferences? = null,
    ) : AnimePlaybackPreferencesRepository {
        val upserts = mutableListOf<AnimePlaybackPreferences>()

        override suspend fun getByAnimeId(animeId: Long): AnimePlaybackPreferences? = existing

        override fun getByAnimeIdAsFlow(animeId: Long): Flow<AnimePlaybackPreferences?> = flowOf(existing)

        override suspend fun upsert(preferences: AnimePlaybackPreferences) {
            upserts += preferences
        }
    }

    private class FakeAnimeEpisodeRepository(
        private val episodes: List<AnimeEpisode>,
    ) : AnimeEpisodeRepository {
        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> = error("Not used")

        override suspend fun update(episodeUpdate: tachiyomi.domain.anime.model.AnimeEpisodeUpdate) = error("Not used")

        override suspend fun updateAll(episodeUpdates: List<tachiyomi.domain.anime.model.AnimeEpisodeUpdate>) = error(
            "Not used",
        )

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = error("Not used")

        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> {
            return episodes.filter { it.animeId == animeId }
        }

        override fun getEpisodesByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeEpisode>> {
            return flowOf(episodes.filter { it.animeId == animeId })
        }

        override fun getEpisodesByAnimeIdsAsFlow(animeIds: List<Long>): Flow<List<AnimeEpisode>> = flowOf(
            episodes.filter {
                it.animeId in
                    animeIds
            },
        )

        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = episodes.firstOrNull { it.id == id }

        override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? {
            return episodes.firstOrNull { it.url == url && it.animeId == animeId }
        }
    }

    private class FakeAnimePlaybackStateRepository(
        private val existingState: AnimePlaybackState?,
    ) : AnimePlaybackStateRepository {
        val requestedEpisodeIds = mutableListOf<Long>()
        val upserts = mutableListOf<AnimePlaybackState>()

        override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? {
            requestedEpisodeIds += episodeId
            return existingState?.takeIf { it.episodeId == episodeId }
        }

        override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<AnimePlaybackState?> = emptyFlow()

        override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<AnimePlaybackState>> {
            return flowOf(existingState?.let(::listOf) ?: emptyList())
        }

        override suspend fun upsert(state: AnimePlaybackState) {
            error("Not used")
        }

        override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) {
            upserts += state
        }
    }

    private class FakeAnimeHistoryRepository : AnimeHistoryRepository {
        val upserts = mutableListOf<AnimeHistoryUpdate>()

        override fun getHistory(query: String): Flow<List<AnimeHistoryWithRelations>> = emptyFlow()

        override suspend fun getLastHistory(): AnimeHistoryWithRelations? = error("Not used")

        override fun getLastHistoryAsFlow(): Flow<AnimeHistoryWithRelations?> = emptyFlow()

        override suspend fun getTotalWatchedDuration(): Long = error("Not used")

        override suspend fun getHistoryByAnimeId(animeId: Long): List<AnimeHistory> = error("Not used")

        override suspend fun resetHistory(historyId: Long) = error("Not used")

        override suspend fun resetHistoryByAnimeId(animeId: Long) = error("Not used")

        override suspend fun deleteAllHistory(): Boolean = error("Not used")

        override suspend fun upsertHistory(historyUpdate: AnimeHistoryUpdate) {
            upserts += historyUpdate
        }
    }

    private class RecordingVideoStreamResolver : VideoStreamResolver {
        val requests = mutableListOf<Long>()
        val selections = mutableListOf<VideoPlaybackSelection?>()

        override suspend fun invoke(
            animeId: Long,
            episodeId: Long,
            ownerAnimeId: Long,
            selection: VideoPlaybackSelection?,
        ): ResolveVideoStream.Result {
            requests += episodeId
            selections += selection
            val video = AnimeTitle.create().copy(
                id = animeId,
                source = 99L,
                title = "Video $animeId",
                initialized = true,
                url = "/video/$animeId",
            )
            val episode = AnimeEpisode.create().copy(
                id = episodeId,
                animeId = animeId,
                url = "/episode/$episodeId",
                name = "Episode $episodeId",
                episodeNumber = episodeId.toDouble(),
            )
            val stream = VideoStream(
                request = VideoRequest(url = "https://cdn.example.com/$episodeId.m3u8"),
                label = "Auto",
                type = VideoStreamType.HLS,
            )

            return ResolveVideoStream.Result.Success(
                visibleAnime = video,
                ownerAnime = video,
                episode = episode,
                playbackData = VideoPlaybackData(
                    selection = selection ?: VideoPlaybackSelection(),
                    streams = listOf(stream),
                ),
                stream = stream,
                subtitles = emptyList(),
                savedPreferences = AnimePlaybackPreferences(
                    animeId = animeId,
                    dubKey = null,
                    streamKey = null,
                    sourceQualityKey = null,
                    playerQualityMode = PlayerQualityMode.AUTO,
                    playerQualityHeight = null,
                    updatedAt = 0L,
                ),
            )
        }
    }

    private class PreviewAwareRecordingVideoStreamResolver : VideoStreamResolver {
        val selections = mutableListOf<VideoPlaybackSelection?>()

        override suspend fun invoke(
            animeId: Long,
            episodeId: Long,
            ownerAnimeId: Long,
            selection: VideoPlaybackSelection?,
        ): ResolveVideoStream.Result {
            selections += selection
            val video = AnimeTitle.create().copy(
                id = animeId,
                source = 99L,
                title = "Video $animeId",
                initialized = true,
                url = "/video/$animeId",
            )
            val episode = AnimeEpisode.create().copy(
                id = episodeId,
                animeId = animeId,
                url = "/episode/$episodeId",
                name = "Episode $episodeId",
                episodeNumber = episodeId.toDouble(),
            )
            val qualityLabel = if (selection?.dubKey == "dub-2") "720p" else "1080p"
            val stream = VideoStream(
                request = VideoRequest(url = "https://cdn.example.com/$episodeId-$qualityLabel.m3u8"),
                label = qualityLabel,
                type = VideoStreamType.HLS,
            )
            val sourceQualities = if (selection?.dubKey == "dub-2") {
                listOf(
                    eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "720p", label = "720p"),
                    eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "480p", label = "480p"),
                )
            } else {
                listOf(
                    eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "1080p", label = "1080p"),
                    eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "720p", label = "720p"),
                )
            }
            val subtitles = if (selection?.dubKey == "dub-2") {
                listOf(
                    VideoSubtitle(
                        request = VideoRequest(url = "https://cdn.example.com/english.vtt"),
                        label = "English",
                        language = "en",
                        isDefault = true,
                    ),
                )
            } else {
                emptyList()
            }

            return ResolveVideoStream.Result.Success(
                visibleAnime = video,
                ownerAnime = video,
                episode = episode,
                playbackData = VideoPlaybackData(
                    selection = selection ?: VideoPlaybackSelection(),
                    dubs = listOf(
                        eu.kanade.tachiyomi.source.model.VideoPlaybackOption(key = "dub-2", label = "Dub 2"),
                    ),
                    sourceQualities = sourceQualities,
                    streams = listOf(stream),
                ),
                stream = stream,
                subtitles = subtitles,
                savedPreferences = AnimePlaybackPreferences(
                    animeId = animeId,
                    dubKey = null,
                    streamKey = null,
                    sourceQualityKey = selection?.sourceQualityKey,
                    playerQualityMode = PlayerQualityMode.AUTO,
                    playerQualityHeight = null,
                    updatedAt = 0L,
                ),
            )
        }
    }

    private class DelayedPreviewAwareRecordingVideoStreamResolver(
        private val delayMs: Long,
    ) : VideoStreamResolver {
        private val delegate = PreviewAwareRecordingVideoStreamResolver()

        override suspend fun invoke(
            animeId: Long,
            episodeId: Long,
            ownerAnimeId: Long,
            selection: VideoPlaybackSelection?,
        ): ResolveVideoStream.Result {
            kotlinx.coroutines.delay(delayMs)
            return delegate.invoke(animeId, episodeId, ownerAnimeId, selection)
        }
    }

    private class DelayedRecordingVideoStreamResolver(
        private val delayMs: Long,
    ) : VideoStreamResolver {
        override suspend fun invoke(
            animeId: Long,
            episodeId: Long,
            ownerAnimeId: Long,
            selection: VideoPlaybackSelection?,
        ): ResolveVideoStream.Result {
            kotlinx.coroutines.delay(delayMs)
            val video = AnimeTitle.create().copy(
                id = animeId,
                source = 99L,
                title = "Video $animeId",
                initialized = true,
                url = "/video/$animeId",
            )
            val episode = AnimeEpisode.create().copy(
                id = episodeId,
                animeId = animeId,
                url = "/episode/$episodeId",
                name = "Episode $episodeId",
                episodeNumber = 1.0,
            )
            val stream = VideoStream(
                request = VideoRequest(url = "https://cdn.example.com/delayed-$episodeId.m3u8"),
                label = "Auto",
                type = VideoStreamType.HLS,
            )

            return ResolveVideoStream.Result.Success(
                visibleAnime = video,
                ownerAnime = video,
                episode = episode,
                playbackData = VideoPlaybackData(
                    selection = selection ?: VideoPlaybackSelection(),
                    streams = listOf(stream),
                ),
                stream = stream,
                subtitles = emptyList(),
                savedPreferences = AnimePlaybackPreferences(
                    animeId = animeId,
                    dubKey = selection?.dubKey,
                    streamKey = null,
                    sourceQualityKey = selection?.sourceQualityKey,
                    playerQualityMode = PlayerQualityMode.AUTO,
                    playerQualityHeight = null,
                    updatedAt = 0L,
                ),
            )
        }
    }
}
