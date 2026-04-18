package tachiyomi.domain.anime.interactor

import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.AnimeSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.source.service.AnimeSourceManager

class SyncAnimeWithSourceTest {

    @Test
    fun `sync updates video details and upserts episodes`() = runTest {
        val localVideo = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/animes/1",
            title = "Old title",
            initialized = false,
        )
        val existingEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = localVideo.id,
            url = "/animes/1/episodes/1",
            name = "Old episode",
            watched = true,
            completed = true,
            dateUpload = 1L,
            episodeNumber = 5.0,
            sourceOrder = 7L,
        )
        val source = FakeAnimeSource(
            details = SAnime.create().also {
                it.url = localVideo.url
                it.title = "New title"
                it.original_title = "Original title"
                it.country = "Japan"
                it.studio = "Studio A"
                it.producer = "Producer A"
                it.director = "Director A"
                it.writer = "Writer A"
                it.year = "2026"
                it.duration = "24 min."
                it.description = "New description"
                it.genre = "Action, Drama"
                it.status = SAnime.ONGOING
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
                    it.url = "/animes/1/episodes/2"
                    it.name = "Episode 2"
                    it.date_upload = 456L
                    it.episode_number = 2f
                },
            ),
        )
        val videoRepository = FakeAnimeRepository(localVideo)
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(existingEpisode))

        SyncAnimeWithSource(
            animeRepository = videoRepository,
            animeEpisodeRepository = episodeRepository,
            animeSourceManager = FakeAnimeSourceManager(source),
            now = { 10_000L },
        )(localVideo) shouldBe SyncAnimeWithSource.SyncResult(
            insertedEpisodes = episodeRepository.insertResults,
            updatedEpisodes = 1,
            removedEpisodes = 0,
            hasMetadataChanges = true,
        )

        videoRepository.updates shouldContain AnimeTitleUpdate(
            id = localVideo.id,
            title = "New title",
            originalTitle = "Original title",
            country = "Japan",
            studio = "Studio A",
            producer = "Producer A",
            director = "Director A",
            writer = "Writer A",
            year = "2026",
            duration = "24 min.",
            description = "New description",
            genre = listOf("Action", "Drama"),
            status = SAnime.ONGOING.toLong(),
            thumbnailUrl = "https://cdn.example.com/video.jpg",
            coverLastModified = 10_000L,
            initialized = true,
        )
        videoRepository.updates.last() shouldBe AnimeTitleUpdate(
            id = localVideo.id,
            lastUpdate = 10_000L,
        )

        episodeRepository.updates.single() shouldBe AnimeEpisodeUpdate(
            id = existingEpisode.id,
            name = "Episode 1",
            dateUpload = 123L,
            episodeNumber = 1.0,
            sourceOrder = 0L,
        )
        episodeRepository.inserts shouldHaveSize 1
        episodeRepository.inserts.single().animeId shouldBe localVideo.id
        episodeRepository.inserts.single().url shouldBe "/animes/1/episodes/2"
        episodeRepository.inserts.single().name shouldBe "Episode 2"
        episodeRepository.inserts.single().episodeNumber shouldBe 2.0
        episodeRepository.inserts.single().sourceOrder shouldBe 1L
        episodeRepository.removals shouldBe emptyList()
    }

    @Test
    fun `sync reuses existing episodes when source urls change and removes stale duplicates`() = runTest {
        val localVideo = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/animes/1",
            title = "Video",
            initialized = true,
        )
        val staleEpisode1 = AnimeEpisode.create().copy(
            id = 10L,
            animeId = localVideo.id,
            url = "/animes/1/episode-1-old",
            name = "Episode 1",
            watched = true,
            sourceOrder = 0L,
            episodeNumber = 1.0,
        )
        val duplicateEpisode1 = AnimeEpisode.create().copy(
            id = 11L,
            animeId = localVideo.id,
            url = "/animes/1/episode-1-new",
            name = "Episode 1",
            sourceOrder = 0L,
            episodeNumber = 1.0,
        )
        val staleEpisode2 = AnimeEpisode.create().copy(
            id = 20L,
            animeId = localVideo.id,
            url = "/animes/1/episode-2-old",
            name = "Episode 2",
            sourceOrder = 1L,
            episodeNumber = 2.0,
        )
        val duplicateEpisode2 = AnimeEpisode.create().copy(
            id = 21L,
            animeId = localVideo.id,
            url = "/animes/1/episode-2-new",
            name = "Episode 2",
            completed = true,
            sourceOrder = 1L,
            episodeNumber = 2.0,
        )
        val source = FakeAnimeSource(
            details = SAnime.create().also {
                it.url = localVideo.url
                it.title = localVideo.title
            },
            episodes = listOf(
                SEpisode.create().also {
                    it.url = duplicateEpisode1.url
                    it.name = duplicateEpisode1.name
                    it.episode_number = duplicateEpisode1.episodeNumber.toFloat()
                },
                SEpisode.create().also {
                    it.url = duplicateEpisode2.url
                    it.name = duplicateEpisode2.name
                    it.episode_number = duplicateEpisode2.episodeNumber.toFloat()
                },
            ),
        )
        val episodeRepository = FakeAnimeEpisodeRepository(
            listOf(staleEpisode1, duplicateEpisode1, staleEpisode2, duplicateEpisode2),
        )
        val videoRepository = FakeAnimeRepository(localVideo)

        SyncAnimeWithSource(
            animeRepository = videoRepository,
            animeEpisodeRepository = episodeRepository,
            animeSourceManager = FakeAnimeSourceManager(source),
            now = { 20_000L },
        )(localVideo) shouldBe SyncAnimeWithSource.SyncResult(
            insertedEpisodes = emptyList(),
            updatedEpisodes = 1,
            removedEpisodes = 2,
            hasMetadataChanges = false,
        )

        videoRepository.updates.single() shouldBe AnimeTitleUpdate(
            id = localVideo.id,
            lastUpdate = 20_000L,
        )

        episodeRepository.inserts shouldBe emptyList()
        episodeRepository.updates shouldContainExactly listOf(
            AnimeEpisodeUpdate(
                id = staleEpisode1.id,
                url = duplicateEpisode1.url,
            ),
        )
        episodeRepository.removals.flatten().sorted() shouldContainExactly listOf(11L, 20L)
    }

    @Test
    fun `sync keeps existing cover version when thumbnail url is unchanged`() = runTest {
        val localVideo = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/animes/1",
            title = "Video",
            initialized = true,
            thumbnailUrl = "https://cdn.example.com/video.jpg",
            coverLastModified = 5_000L,
        )
        val source = FakeAnimeSource(
            details = SAnime.create().also {
                it.url = localVideo.url
                it.title = localVideo.title
                it.thumbnail_url = localVideo.thumbnailUrl
                it.initialized = true
            },
            episodes = emptyList(),
        )
        val videoRepository = FakeAnimeRepository(localVideo)
        val episodeRepository = FakeAnimeEpisodeRepository(emptyList())

        SyncAnimeWithSource(
            animeRepository = videoRepository,
            animeEpisodeRepository = episodeRepository,
            animeSourceManager = FakeAnimeSourceManager(source),
            now = { 10_000L },
        )(localVideo) shouldBe SyncAnimeWithSource.SyncResult(
            insertedEpisodes = emptyList(),
            updatedEpisodes = 0,
            removedEpisodes = 0,
            hasMetadataChanges = false,
        )

        videoRepository.updates shouldBe emptyList()
        videoRepository.updates.none { it.coverLastModified != null } shouldBe true
    }

    @Test
    fun `sync assigns fallback upload dates to new episodes when source dates are missing`() = runTest {
        val localVideo = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/animes/1",
            title = "Video",
            initialized = true,
        )
        val source = FakeAnimeSource(
            details = SAnime.create().also {
                it.url = localVideo.url
                it.title = localVideo.title
            },
            episodes = listOf(
                SEpisode.create().also {
                    it.url = "/animes/1/episodes/1"
                    it.name = "Episode 1"
                    it.date_upload = 123L
                    it.episode_number = 1f
                },
                SEpisode.create().also {
                    it.url = "/animes/1/episodes/2"
                    it.name = "Episode 2"
                    it.date_upload = 0L
                    it.episode_number = 2f
                },
                SEpisode.create().also {
                    it.url = "/animes/1/episodes/3"
                    it.name = "Episode 3"
                    it.date_upload = 0L
                    it.episode_number = 3f
                },
            ),
        )
        val videoRepository = FakeAnimeRepository(localVideo)
        val episodeRepository = FakeAnimeEpisodeRepository(emptyList())

        SyncAnimeWithSource(
            animeRepository = videoRepository,
            animeEpisodeRepository = episodeRepository,
            animeSourceManager = FakeAnimeSourceManager(source),
            now = { 10_000L },
        )(localVideo) shouldBe SyncAnimeWithSource.SyncResult(
            insertedEpisodes = episodeRepository.insertResults,
            updatedEpisodes = 0,
            removedEpisodes = 0,
            hasMetadataChanges = false,
        )

        episodeRepository.inserts shouldContainExactly listOf(
            AnimeEpisode.create().copy(
                animeId = localVideo.id,
                url = "/animes/1/episodes/1",
                name = "Episode 1",
                dateFetch = 10_000L,
                sourceOrder = 0L,
                dateUpload = 123L,
                episodeNumber = 1.0,
            ),
            AnimeEpisode.create().copy(
                animeId = localVideo.id,
                url = "/animes/1/episodes/2",
                name = "Episode 2",
                dateFetch = 10_000L,
                sourceOrder = 1L,
                dateUpload = 123L,
                episodeNumber = 2.0,
            ),
            AnimeEpisode.create().copy(
                animeId = localVideo.id,
                url = "/animes/1/episodes/3",
                name = "Episode 3",
                dateFetch = 10_000L,
                sourceOrder = 2L,
                dateUpload = 123L,
                episodeNumber = 3.0,
            ),
        )
    }

    @Test
    fun `sync keeps existing upload date when source omits it`() = runTest {
        val localVideo = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/animes/1",
            title = "Video",
            initialized = true,
        )
        val existingEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = localVideo.id,
            url = "/animes/1/episodes/1",
            name = "Episode 1",
            dateUpload = 123L,
            episodeNumber = 1.0,
            sourceOrder = 0L,
        )
        val source = FakeAnimeSource(
            details = SAnime.create().also {
                it.url = localVideo.url
                it.title = localVideo.title
            },
            episodes = listOf(
                SEpisode.create().also {
                    it.url = existingEpisode.url
                    it.name = "Episode 1 updated"
                    it.date_upload = 0L
                    it.episode_number = 1f
                },
            ),
        )
        val videoRepository = FakeAnimeRepository(localVideo)
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(existingEpisode))

        SyncAnimeWithSource(
            animeRepository = videoRepository,
            animeEpisodeRepository = episodeRepository,
            animeSourceManager = FakeAnimeSourceManager(source),
            now = { 10_000L },
        )(localVideo) shouldBe SyncAnimeWithSource.SyncResult(
            insertedEpisodes = emptyList(),
            updatedEpisodes = 1,
            removedEpisodes = 0,
            hasMetadataChanges = false,
        )

        episodeRepository.updates.single() shouldBe AnimeEpisodeUpdate(
            id = existingEpisode.id,
            name = "Episode 1 updated",
        )
    }

    @Test
    fun `sync backfills fallback upload date for existing episodes missing it`() = runTest {
        val localVideo = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            url = "/animes/1",
            title = "Video",
            initialized = true,
        )
        val existingEpisodes = listOf(
            AnimeEpisode.create().copy(
                id = 10L,
                animeId = localVideo.id,
                url = "/animes/1/episodes/1",
                name = "Episode 1",
                dateUpload = 123L,
                episodeNumber = 1.0,
                sourceOrder = 0L,
            ),
            AnimeEpisode.create().copy(
                id = 11L,
                animeId = localVideo.id,
                url = "/animes/1/episodes/2",
                name = "Episode 2",
                dateUpload = 0L,
                episodeNumber = 2.0,
                sourceOrder = 1L,
            ),
        )
        val source = FakeAnimeSource(
            details = SAnime.create().also {
                it.url = localVideo.url
                it.title = localVideo.title
            },
            episodes = listOf(
                SEpisode.create().also {
                    it.url = "/animes/1/episodes/1"
                    it.name = "Episode 1"
                    it.date_upload = 123L
                    it.episode_number = 1f
                },
                SEpisode.create().also {
                    it.url = "/animes/1/episodes/2"
                    it.name = "Episode 2"
                    it.date_upload = 0L
                    it.episode_number = 2f
                },
            ),
        )
        val videoRepository = FakeAnimeRepository(localVideo)
        val episodeRepository = FakeAnimeEpisodeRepository(existingEpisodes)

        SyncAnimeWithSource(
            animeRepository = videoRepository,
            animeEpisodeRepository = episodeRepository,
            animeSourceManager = FakeAnimeSourceManager(source),
            now = { 10_000L },
        )(localVideo) shouldBe SyncAnimeWithSource.SyncResult(
            insertedEpisodes = emptyList(),
            updatedEpisodes = 1,
            removedEpisodes = 0,
            hasMetadataChanges = false,
        )

        episodeRepository.updates.single() shouldBe AnimeEpisodeUpdate(
            id = 11L,
            dateUpload = 123L,
        )
    }

    private class FakeAnimeRepository(
        private val video: AnimeTitle,
    ) : AnimeRepository {
        val updates = mutableListOf<AnimeTitleUpdate>()

        override suspend fun getAnimeById(id: Long): AnimeTitle = video

        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = emptyFlow()

        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = error("Not used")

        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> = emptyFlow()

        override suspend fun getFavorites(): List<AnimeTitle> = error("Not used")

        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(emptyList())

        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = error("Not used")

        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean = error("Not used")

        override suspend fun update(update: AnimeTitleUpdate): Boolean {
            updates += update
            return true
        }

        override suspend fun updateAll(videoUpdates: List<AnimeTitleUpdate>): Boolean = error("Not used")

        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = error("Not used")

        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) = error("Not used")
    }

    private class FakeAnimeEpisodeRepository(
        existingEpisodes: List<AnimeEpisode>,
    ) : AnimeEpisodeRepository {
        private val episodes = existingEpisodes
        val inserts = mutableListOf<AnimeEpisode>()
        val insertResults = mutableListOf<AnimeEpisode>()
        val updates = mutableListOf<AnimeEpisodeUpdate>()
        val removals = mutableListOf<List<Long>>()

        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> {
            inserts += episodes
            return episodes.mapIndexed { index, episode ->
                episode.copy(id = 1_000L + insertResults.size + index)
            }.also {
                insertResults += it
            }
        }

        override suspend fun update(episodeUpdate: AnimeEpisodeUpdate) = error("Not used")

        override suspend fun updateAll(episodeUpdates: List<AnimeEpisodeUpdate>) {
            updates += episodeUpdates
        }

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) {
            removals += episodeIds
        }

        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> {
            return episodes.filter { it.animeId == animeId }
        }

        override fun getEpisodesByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeEpisode>> = emptyFlow()

        override fun getEpisodesByAnimeIdsAsFlow(videoIds: List<Long>): Flow<List<AnimeEpisode>> = emptyFlow()

        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = error("Not used")

        override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? = error("Not used")
    }

    private class FakeAnimeSourceManager(
        private val source: AnimeSource,
    ) : AnimeSourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)

        override val catalogueSources: Flow<List<AnimeCatalogueSource>> = emptyFlow()

        override fun get(sourceKey: Long): AnimeSource? = source.takeIf { it.id == sourceKey }

        override fun getCatalogueSources(): List<AnimeCatalogueSource> = emptyList()
    }

    private class FakeAnimeSource(
        private val details: SAnime,
        private val episodes: List<SEpisode>,
    ) : AnimeCatalogueSource {
        override val id: Long = 99L
        override val name: String = "Fake"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true

        override suspend fun getPopularAnime(page: Int): AnimesPage = error("Not used")

        override suspend fun getSearchAnime(
            page: Int,
            query: String,
            filters: FilterList,
        ): AnimesPage = error("Not used")

        override suspend fun getLatestUpdates(page: Int): AnimesPage = error("Not used")

        override fun getFilterList(): FilterList = FilterList()

        override suspend fun getAnimeDetails(anime: SAnime): SAnime = details

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = episodes

        override suspend fun getPlaybackData(
            episode: SEpisode,
            selection: VideoPlaybackSelection,
        ): VideoPlaybackData = error("Not used")
    }
}
