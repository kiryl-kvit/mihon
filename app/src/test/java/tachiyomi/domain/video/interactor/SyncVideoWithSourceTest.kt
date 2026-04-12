package tachiyomi.domain.video.interactor

import eu.kanade.tachiyomi.source.VideoCatalogueSource
import eu.kanade.tachiyomi.source.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.SVideo
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideosPage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.VideoSourceManager
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoEpisodeUpdate
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.model.VideoTitleUpdate
import tachiyomi.domain.video.repository.VideoEpisodeRepository
import tachiyomi.domain.video.repository.VideoRepository

class SyncVideoWithSourceTest {

    @Test
    fun `sync updates video details and upserts episodes`() = runTest {
        val localVideo = VideoTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/videos/1",
            title = "Old title",
            initialized = false,
        )
        val existingEpisode = VideoEpisode.create().copy(
            id = 10L,
            videoId = localVideo.id,
            url = "/videos/1/episodes/1",
            name = "Old episode",
            watched = true,
            completed = true,
            dateUpload = 1L,
            episodeNumber = 5.0,
            sourceOrder = 7L,
        )
        val source = FakeVideoSource(
            details = SVideo.create().also {
                it.url = localVideo.url
                it.title = "New title"
                it.description = "New description"
                it.genre = "Action, Drama"
                it.thumbnail_url = "https://cdn.example.com/video.jpg"
                it.initialized = true
            },
            episodes = listOf(
                SEpisode.create().also {
                    it.url = existingEpisode.url
                    it.name = "Episode 1"
                    it.date_upload = 123L
                    it.episode_number = 1f
                },
                SEpisode.create().also {
                    it.url = "/videos/1/episodes/2"
                    it.name = "Episode 2"
                    it.date_upload = 456L
                    it.episode_number = 2f
                },
            ),
        )
        val videoRepository = FakeVideoRepository(localVideo)
        val episodeRepository = FakeVideoEpisodeRepository(listOf(existingEpisode))

        SyncVideoWithSource(
            videoRepository = videoRepository,
            videoEpisodeRepository = episodeRepository,
            videoSourceManager = FakeVideoSourceManager(source),
        )(localVideo)

        videoRepository.updates.single() shouldBe VideoTitleUpdate(
            id = localVideo.id,
            title = "New title",
            description = "New description",
            genre = listOf("Action", "Drama"),
            thumbnailUrl = "https://cdn.example.com/video.jpg",
            initialized = true,
        )

        episodeRepository.updates.single() shouldBe VideoEpisodeUpdate(
            id = existingEpisode.id,
            name = "Episode 1",
            dateUpload = 123L,
            episodeNumber = 1.0,
            sourceOrder = 0L,
        )
        episodeRepository.inserts shouldHaveSize 1
        episodeRepository.inserts.single().videoId shouldBe localVideo.id
        episodeRepository.inserts.single().url shouldBe "/videos/1/episodes/2"
        episodeRepository.inserts.single().name shouldBe "Episode 2"
        episodeRepository.inserts.single().episodeNumber shouldBe 2.0
        episodeRepository.inserts.single().sourceOrder shouldBe 1L
    }

    private class FakeVideoRepository(
        private val video: VideoTitle,
    ) : VideoRepository {
        val updates = mutableListOf<VideoTitleUpdate>()

        override suspend fun getVideoById(id: Long): VideoTitle = video

        override suspend fun getVideoByIdAsFlow(id: Long): Flow<VideoTitle> = emptyFlow()

        override suspend fun getVideoByUrlAndSourceId(url: String, sourceId: Long): VideoTitle? = error("Not used")

        override fun getVideoByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<VideoTitle?> = emptyFlow()

        override suspend fun getFavorites(): List<VideoTitle> = error("Not used")

        override fun getFavoritesAsFlow(): Flow<List<VideoTitle>> = flowOf(emptyList())

        override suspend fun getAllVideosByProfile(profileId: Long): List<VideoTitle> = error("Not used")

        override suspend fun update(update: VideoTitleUpdate): Boolean {
            updates += update
            return true
        }

        override suspend fun updateAll(videoUpdates: List<VideoTitleUpdate>): Boolean = error("Not used")

        override suspend fun insertNetworkVideo(videos: List<VideoTitle>): List<VideoTitle> = error("Not used")

        override suspend fun setVideoCategories(videoId: Long, categoryIds: List<Long>) = error("Not used")
    }

    private class FakeVideoEpisodeRepository(
        existingEpisodes: List<VideoEpisode>,
    ) : VideoEpisodeRepository {
        private val episodes = existingEpisodes.associateBy { it.url }
        val inserts = mutableListOf<VideoEpisode>()
        val updates = mutableListOf<VideoEpisodeUpdate>()

        override suspend fun addAll(episodes: List<VideoEpisode>): List<VideoEpisode> {
            inserts += episodes
            return episodes
        }

        override suspend fun update(episodeUpdate: VideoEpisodeUpdate) = error("Not used")

        override suspend fun updateAll(episodeUpdates: List<VideoEpisodeUpdate>) {
            updates += episodeUpdates
        }

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = error("Not used")

        override suspend fun getEpisodesByVideoId(videoId: Long): List<VideoEpisode> = episodes.values.toList()

        override fun getEpisodesByVideoIdAsFlow(videoId: Long): Flow<List<VideoEpisode>> = emptyFlow()

        override fun getEpisodesByVideoIdsAsFlow(videoIds: List<Long>): Flow<List<VideoEpisode>> = emptyFlow()

        override suspend fun getEpisodeById(id: Long): VideoEpisode? = error("Not used")

        override suspend fun getEpisodeByUrlAndVideoId(url: String, videoId: Long): VideoEpisode? = error("Not used")
    }

    private class FakeVideoSourceManager(
        private val source: VideoSource,
    ) : VideoSourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)

        override val catalogueSources: Flow<List<VideoCatalogueSource>> = emptyFlow()

        override fun get(sourceKey: Long): VideoSource? = source.takeIf { it.id == sourceKey }

        override fun getCatalogueSources(): List<VideoCatalogueSource> = emptyList()
    }

    private class FakeVideoSource(
        private val details: SVideo,
        private val episodes: List<SEpisode>,
    ) : VideoCatalogueSource {
        override val id: Long = 99L
        override val name: String = "Fake"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true

        override suspend fun getPopularVideos(page: Int): VideosPage = error("Not used")

        override suspend fun getSearchVideos(page: Int, query: String, filters: FilterList): VideosPage = error("Not used")

        override suspend fun getLatestUpdates(page: Int): VideosPage = error("Not used")

        override fun getFilterList(): FilterList = FilterList()

        override suspend fun getVideoDetails(video: SVideo): SVideo = details

        override suspend fun getEpisodeList(video: SVideo): List<SEpisode> = episodes

        override suspend fun getStreamList(episode: SEpisode): List<VideoStream> = error("Not used")
    }
}
