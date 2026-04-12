package eu.kanade.tachiyomi.ui.video.player

import eu.kanade.tachiyomi.source.VideoSource
import eu.kanade.tachiyomi.source.model.VideoRequest
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoStreamType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.VideoSourceManager
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.repository.VideoEpisodeRepository
import tachiyomi.domain.video.repository.VideoRepository

class ResolveVideoStreamTest {

    @Test
    fun `returns first stream from resolved source`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, videoId = 1L)
        val firstStream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/first.m3u8", headers = mapOf("Referer" to "https://example.com")),
            label = "Auto",
            type = VideoStreamType.HLS,
        )
        val secondStream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/backup.mp4"),
            label = "Backup",
            type = VideoStreamType.PROGRESSIVE,
        )

        val resolver = ResolveVideoStream(
            videoRepository = FakeVideoRepository(video),
            videoEpisodeRepository = FakeVideoEpisodeRepository(episode),
            videoSourceManager = FakeVideoSourceManager(
                source = FakeVideoSource(video.source) { listOf(firstStream, secondStream) },
            ),
        )

        val result = resolver(video.id, episode.id)

        (result as ResolveVideoStream.Result.Success).video shouldBe video
        result.episode shouldBe episode
        result.stream shouldBe firstStream
    }

    @Test
    fun `returns source not found when manager has no matching source`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, videoId = 1L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeVideoRepository(video),
            videoEpisodeRepository = FakeVideoEpisodeRepository(episode),
            videoSourceManager = FakeVideoSourceManager(source = null),
        )

        val result = resolver(video.id, episode.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.SourceNotFound)
    }

    @Test
    fun `returns no streams when source returns empty list`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, videoId = 1L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeVideoRepository(video),
            videoEpisodeRepository = FakeVideoEpisodeRepository(episode),
            videoSourceManager = FakeVideoSourceManager(
                source = FakeVideoSource(video.source) { emptyList() },
            ),
        )

        val result = resolver(video.id, episode.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.NoStreams)
    }

    @Test
    fun `returns mismatch when episode does not belong to video`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, videoId = 3L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeVideoRepository(video),
            videoEpisodeRepository = FakeVideoEpisodeRepository(episode),
            videoSourceManager = FakeVideoSourceManager(
                source = FakeVideoSource(video.source) { emptyList() },
            ),
        )

        val result = resolver(video.id, episode.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.EpisodeMismatch)
    }

    private fun videoTitle(id: Long, sourceId: Long): VideoTitle {
        return VideoTitle.create().copy(
            id = id,
            source = sourceId,
            title = "Video $id",
            initialized = true,
            url = "/video/$id",
        )
    }

    private fun videoEpisode(id: Long, videoId: Long): VideoEpisode {
        return VideoEpisode.create().copy(
            id = id,
            videoId = videoId,
            url = "/episode/$id",
            name = "Episode $id",
            dateUpload = 1234L,
            episodeNumber = 1.0,
        )
    }

    private class FakeVideoRepository(
        private val video: VideoTitle,
    ) : VideoRepository {
        override suspend fun getVideoById(id: Long): VideoTitle = video.takeIf { it.id == id } ?: error("Missing video")

        override suspend fun getVideoByIdAsFlow(id: Long) = error("Not used")

        override suspend fun getVideoByUrlAndSourceId(url: String, sourceId: Long): VideoTitle? = error("Not used")

        override fun getVideoByUrlAndSourceIdAsFlow(url: String, sourceId: Long) = error("Not used")

        override suspend fun getFavorites(): List<VideoTitle> = error("Not used")

        override fun getFavoritesAsFlow(): Flow<List<VideoTitle>> = flowOf(emptyList())

        override suspend fun getAllVideosByProfile(profileId: Long): List<VideoTitle> = error("Not used")

        override suspend fun update(update: tachiyomi.domain.video.model.VideoTitleUpdate): Boolean = error("Not used")

        override suspend fun updateAll(videoUpdates: List<tachiyomi.domain.video.model.VideoTitleUpdate>): Boolean = error("Not used")

        override suspend fun insertNetworkVideo(videos: List<VideoTitle>): List<VideoTitle> = error("Not used")

        override suspend fun setVideoCategories(videoId: Long, categoryIds: List<Long>) = error("Not used")
    }

    private class FakeVideoEpisodeRepository(
        private val episode: VideoEpisode?,
    ) : VideoEpisodeRepository {
        override suspend fun addAll(episodes: List<VideoEpisode>): List<VideoEpisode> = error("Not used")

        override suspend fun update(episodeUpdate: tachiyomi.domain.video.model.VideoEpisodeUpdate) = error("Not used")

        override suspend fun updateAll(episodeUpdates: List<tachiyomi.domain.video.model.VideoEpisodeUpdate>) = error("Not used")

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = error("Not used")

        override suspend fun getEpisodesByVideoId(videoId: Long): List<VideoEpisode> = error("Not used")

        override fun getEpisodesByVideoIdAsFlow(videoId: Long) = error("Not used")

        override fun getEpisodesByVideoIdsAsFlow(videoIds: List<Long>) = error("Not used")

        override suspend fun getEpisodeById(id: Long): VideoEpisode? = episode?.takeIf { it.id == id }

        override suspend fun getEpisodeByUrlAndVideoId(url: String, videoId: Long): VideoEpisode? = error("Not used")
    }

    private class FakeVideoSourceManager(
        private val source: VideoSource?,
    ) : VideoSourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = emptyFlow<List<eu.kanade.tachiyomi.source.VideoCatalogueSource>>()

        override fun get(sourceKey: Long): VideoSource? = source?.takeIf { it.id == sourceKey }

        override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.VideoCatalogueSource>()
    }

    private class FakeVideoSource(
        override val id: Long,
        private val streams: suspend () -> List<VideoStream>,
    ) : VideoSource {
        override val name: String = "Fake"

        override suspend fun getVideoDetails(video: eu.kanade.tachiyomi.source.model.SVideo) = error("Not used")

        override suspend fun getEpisodeList(video: eu.kanade.tachiyomi.source.model.SVideo) = error("Not used")

        override suspend fun getStreamList(episode: eu.kanade.tachiyomi.source.model.SEpisode): List<VideoStream> = streams()
    }
}
