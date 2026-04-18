package eu.kanade.tachiyomi.ui.video.player

import eu.kanade.tachiyomi.source.AnimeSource
import eu.kanade.tachiyomi.source.AnimeSubtitleSource
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.source.model.VideoRequest
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideoStreamType
import eu.kanade.tachiyomi.source.model.VideoSubtitle
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.PlayerQualityMode
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.source.service.AnimeSourceManager

class ResolveVideoStreamTest {

    @Test
    fun `prefers HLS stream from resolved source`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)
        val firstStream = VideoStream(
            request = VideoRequest(
                url = "https://cdn.example.com/first.m3u8",
                headers = mapOf("Referer" to "https://example.com"),
            ),
            label = "Auto",
            type = VideoStreamType.HLS,
        )
        val secondStream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/backup.mp4"),
            label = "Backup",
            type = VideoStreamType.PROGRESSIVE,
        )

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FakeAnimeSource(video.source) { listOf(firstStream, secondStream) },
            ),
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        (result as ResolveVideoStream.Result.Success).visibleAnime shouldBe video
        result.ownerAnime shouldBe video
        result.episode shouldBe episode
        result.stream shouldBe firstStream
    }

    @Test
    fun `returns timeout when source manager does not initialize in time`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FakeAnimeSource(video.source) { emptyList() },
                initialized = false,
            ),
            sourceInitTimeoutMs = 1L,
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.SourceLoadTimeout)
    }

    @Test
    fun `returns timeout when stream fetch takes too long`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FakeAnimeSource(video.source) {
                    delay(10)
                    emptyList()
                },
            ),
            streamFetchTimeoutMs = 1L,
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.StreamFetchTimeout)
    }

    @Test
    fun `returns source not found when manager has no matching source`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(source = null),
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.SourceNotFound)
    }

    @Test
    fun `returns no streams when source returns empty list`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FakeAnimeSource(video.source) { emptyList() },
            ),
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.NoStreams)
    }

    @Test
    fun `returns mismatch when episode does not belong to video`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 3L)

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FakeAnimeSource(video.source) { emptyList() },
            ),
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        result shouldBe ResolveVideoStream.Result.Error(ResolveVideoStream.Reason.EpisodeMismatch)
    }

    @Test
    fun `returns subtitles from subtitle-capable source`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)
        val stream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/first.m3u8"),
            label = "Auto",
            type = VideoStreamType.HLS,
        )
        val subtitle = VideoSubtitle(
            request = VideoRequest(url = "https://cdn.example.com/subs.vtt"),
            label = "Russian",
            language = "ru",
            isDefault = true,
        )

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FakeSubtitleAnimeSource(video.source, listOf(stream), listOf(subtitle)),
            ),
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        (result as ResolveVideoStream.Result.Success).subtitles shouldBe listOf(subtitle)
    }

    @Test
    fun `ignores subtitle failures and still returns streams`() = runTest {
        val video = videoTitle(id = 1L, sourceId = 99L)
        val episode = videoEpisode(id = 2L, animeId = 1L)
        val stream = VideoStream(
            request = VideoRequest(url = "https://cdn.example.com/first.m3u8"),
            label = "Auto",
            type = VideoStreamType.HLS,
        )

        val resolver = ResolveVideoStream(
            videoRepository = FakeAnimeRepository(video),
            videoEpisodeRepository = FakeAnimeEpisodeRepository(episode),
            animePlaybackPreferencesRepository = FakeAnimePlaybackPreferencesRepository(),
            videoSourceManager = FakeAnimeSourceManager(
                source = FailingSubtitleAnimeSource(video.source, listOf(stream)),
            ),
        )

        val result = resolver(video.id, episode.id, ownerAnimeId = video.id)

        (result as ResolveVideoStream.Result.Success).stream shouldBe stream
        result.subtitles shouldBe emptyList()
    }

    private fun videoTitle(id: Long, sourceId: Long): AnimeTitle {
        return AnimeTitle.create().copy(
            id = id,
            source = sourceId,
            title = "Video $id",
            initialized = true,
            url = "/video/$id",
        )
    }

    private fun videoEpisode(id: Long, animeId: Long): AnimeEpisode {
        return AnimeEpisode.create().copy(
            id = id,
            animeId = animeId,
            url = "/episode/$id",
            name = "Episode $id",
            dateUpload = 1234L,
            episodeNumber = 1.0,
        )
    }

    private class FakeAnimeRepository(
        private val video: AnimeTitle,
    ) : AnimeRepository {
        override suspend fun getAnimeById(id: Long): AnimeTitle = video.takeIf { it.id == id } ?: error("Missing video")

        override suspend fun getAnimeByIdAsFlow(id: Long) = error("Not used")

        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = error("Not used")

        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long) = error("Not used")

        override suspend fun getFavorites(): List<AnimeTitle> = error("Not used")

        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(emptyList())

        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = error("Not used")

        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean = error("Not used")

        override suspend fun update(update: tachiyomi.domain.anime.model.AnimeTitleUpdate): Boolean = error("Not used")

        override suspend fun updateAll(
            videoUpdates: List<tachiyomi.domain.anime.model.AnimeTitleUpdate>,
        ): Boolean = error("Not used")

        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = error("Not used")

        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) = error("Not used")
    }

    private class FakeAnimeEpisodeRepository(
        private val episode: AnimeEpisode?,
    ) : AnimeEpisodeRepository {
        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> = error("Not used")

        override suspend fun update(episodeUpdate: tachiyomi.domain.anime.model.AnimeEpisodeUpdate) = error("Not used")

        override suspend fun updateAll(
            episodeUpdates: List<tachiyomi.domain.anime.model.AnimeEpisodeUpdate>,
        ) = error("Not used")

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = error("Not used")

        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> = error("Not used")

        override fun getEpisodesByAnimeIdAsFlow(animeId: Long) = error("Not used")

        override fun getEpisodesByAnimeIdsAsFlow(videoIds: List<Long>) = error("Not used")

        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = episode?.takeIf { it.id == id }

        override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? = error("Not used")
    }

    private class FakeAnimeSourceManager(
        private val source: AnimeSource?,
        initialized: Boolean = true,
    ) : AnimeSourceManager {
        override val isInitialized = MutableStateFlow(initialized)
        override val catalogueSources = emptyFlow<List<eu.kanade.tachiyomi.source.AnimeCatalogueSource>>()

        override fun get(sourceKey: Long): AnimeSource? = source?.takeIf { it.id == sourceKey }

        override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.AnimeCatalogueSource>()
    }

    private class FakeAnimeSource(
        override val id: Long,
        private val streams: suspend () -> List<VideoStream>,
    ) : AnimeSource {
        override val name: String = "Fake"

        override suspend fun getAnimeDetails(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getEpisodeList(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getPlaybackData(
            episode: eu.kanade.tachiyomi.source.model.SEpisode,
            selection: VideoPlaybackSelection,
        ): VideoPlaybackData {
            return VideoPlaybackData(
                selection = selection,
                streams = streams(),
            )
        }
    }

    private class FakeSubtitleAnimeSource(
        override val id: Long,
        private val streams: List<VideoStream>,
        private val subtitles: List<VideoSubtitle>,
    ) : AnimeSource, AnimeSubtitleSource {
        override val name: String = "Fake"

        override suspend fun getAnimeDetails(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getEpisodeList(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getPlaybackData(
            episode: eu.kanade.tachiyomi.source.model.SEpisode,
            selection: VideoPlaybackSelection,
        ): VideoPlaybackData {
            return VideoPlaybackData(
                selection = selection,
                streams = streams,
            )
        }

        override suspend fun getSubtitles(
            episode: eu.kanade.tachiyomi.source.model.SEpisode,
            selection: VideoPlaybackSelection,
        ): List<VideoSubtitle> {
            return subtitles
        }
    }

    private class FailingSubtitleAnimeSource(
        override val id: Long,
        private val streams: List<VideoStream>,
    ) : AnimeSource, AnimeSubtitleSource {
        override val name: String = "Fake"

        override suspend fun getAnimeDetails(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getEpisodeList(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getPlaybackData(
            episode: eu.kanade.tachiyomi.source.model.SEpisode,
            selection: VideoPlaybackSelection,
        ): VideoPlaybackData {
            return VideoPlaybackData(
                selection = selection,
                streams = streams,
            )
        }

        override suspend fun getSubtitles(
            episode: eu.kanade.tachiyomi.source.model.SEpisode,
            selection: VideoPlaybackSelection,
        ): List<VideoSubtitle> {
            error("subtitle parsing failed")
        }
    }

    private class FakeAnimePlaybackPreferencesRepository : AnimePlaybackPreferencesRepository {
        override suspend fun getByAnimeId(animeId: Long): AnimePlaybackPreferences? {
            return AnimePlaybackPreferences(
                animeId = animeId,
                dubKey = null,
                streamKey = null,
                sourceQualityKey = null,
                playerQualityMode = PlayerQualityMode.AUTO,
                playerQualityHeight = null,
                updatedAt = 0L,
            )
        }

        override fun getByAnimeIdAsFlow(animeId: Long) = emptyFlow<AnimePlaybackPreferences?>()

        override suspend fun upsert(preferences: AnimePlaybackPreferences) = Unit
    }
}
