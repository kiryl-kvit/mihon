package eu.kanade.tachiyomi.ui.anime

import android.app.Application
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.source.service.AnimeSourceManager
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeScreenModelTest {

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
    fun `long press enters selection and second long press selects range`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(id = 1L, source = 99L, title = "Anime", favorite = true, initialized = true, url = "/anime/1")
        val episodes = listOf(
            AnimeEpisode.create().copy(id = 10L, animeId = anime.id, sourceOrder = 0L, name = "Episode 1"),
            AnimeEpisode.create().copy(id = 11L, animeId = anime.id, sourceOrder = 1L, name = "Episode 2"),
            AnimeEpisode.create().copy(id = 12L, animeId = anime.id, sourceOrder = 2L, name = "Episode 3"),
        )

        val model = createModel(anime = anime, episodes = episodes)

        advanceUntilIdle()
        awaitSuccess(model)

        model.toggleSelection(episodes[0], true, fromLongPress = true)
        model.toggleSelection(episodes[2], true, fromLongPress = true)

        eventually(2.seconds) {
            val state = model.state.value as AnimeScreenModel.State.Success
            state.isSelectionMode shouldBe true
            state.selectedCount shouldBe 3
            state.selection shouldContainExactly setOf(10L, 11L, 12L)
        }
    }

    @Test
    fun `invert selection flips current episode selection`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(id = 1L, source = 99L, title = "Anime", favorite = true, initialized = true, url = "/anime/1")
        val episodes = listOf(
            AnimeEpisode.create().copy(id = 10L, animeId = anime.id, sourceOrder = 0L),
            AnimeEpisode.create().copy(id = 11L, animeId = anime.id, sourceOrder = 1L),
            AnimeEpisode.create().copy(id = 12L, animeId = anime.id, sourceOrder = 2L),
        )

        val model = createModel(anime = anime, episodes = episodes)

        advanceUntilIdle()
        awaitSuccess(model)

        model.toggleSelection(episodes[0], true, fromLongPress = true)
        model.invertSelection()

        eventually(2.seconds) {
            val state = model.state.value as AnimeScreenModel.State.Success
            state.selection shouldContainExactly setOf(11L, 12L)
        }
    }

    @Test
    fun `mark watched updates selected episodes and playback states`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(id = 1L, source = 99L, title = "Anime", favorite = true, initialized = true, url = "/anime/1")
        val firstEpisode = AnimeEpisode.create().copy(id = 10L, animeId = anime.id, watched = false, completed = false, sourceOrder = 0L)
        val secondEpisode = AnimeEpisode.create().copy(id = 11L, animeId = anime.id, watched = true, completed = false, sourceOrder = 1L)
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode))
        val playbackRepository = FakeAnimePlaybackStateRepository(
            mapOf(
                anime.id to listOf(
                    AnimePlaybackState(episodeId = firstEpisode.id, positionMs = 5_000L, durationMs = 10_000L, completed = false, lastWatchedAt = 100L),
                    AnimePlaybackState(episodeId = secondEpisode.id, positionMs = 7_500L, durationMs = 10_000L, completed = false, lastWatchedAt = 200L),
                ),
            ),
        )

        val model = createModel(
            anime = anime,
            episodes = listOf(firstEpisode, secondEpisode),
            episodeRepository = episodeRepository,
            playbackRepository = playbackRepository,
        )

        advanceUntilIdle()
        awaitSuccess(model)

        model.toggleSelection(firstEpisode, true, fromLongPress = true)
        model.toggleSelection(secondEpisode, true, fromLongPress = false)
        model.markSelectedEpisodesWatched(true)

        eventually(2.seconds) {
            episodeRepository.updateAllCalls.single() shouldBe listOf(
                AnimeEpisodeUpdate(id = firstEpisode.id, watched = true, completed = true),
                AnimeEpisodeUpdate(id = secondEpisode.id, watched = true, completed = true),
            )
            playbackRepository.upserts shouldBe listOf(
                AnimePlaybackState(episodeId = firstEpisode.id, positionMs = 5_000L, durationMs = 10_000L, completed = true, lastWatchedAt = 100L),
                AnimePlaybackState(episodeId = secondEpisode.id, positionMs = 7_500L, durationMs = 10_000L, completed = true, lastWatchedAt = 200L),
            )
            val state = model.state.value as AnimeScreenModel.State.Success
            state.selection shouldBe emptySet<Long>()
            state.isSelectionMode shouldBe false
        }
    }

    @Test
    fun `mark unwatched resets selected episodes and playback states`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(id = 1L, source = 99L, title = "Anime", favorite = true, initialized = true, url = "/anime/1")
        val firstEpisode = AnimeEpisode.create().copy(id = 10L, animeId = anime.id, watched = true, completed = true, sourceOrder = 0L)
        val secondEpisode = AnimeEpisode.create().copy(id = 11L, animeId = anime.id, watched = true, completed = false, sourceOrder = 1L)
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode))
        val playbackRepository = FakeAnimePlaybackStateRepository(
            mapOf(
                anime.id to listOf(
                    AnimePlaybackState(episodeId = firstEpisode.id, positionMs = 10_000L, durationMs = 10_000L, completed = true, lastWatchedAt = 100L),
                    AnimePlaybackState(episodeId = secondEpisode.id, positionMs = 5_000L, durationMs = 10_000L, completed = false, lastWatchedAt = 200L),
                ),
            ),
        )

        val model = createModel(
            anime = anime,
            episodes = listOf(firstEpisode, secondEpisode),
            episodeRepository = episodeRepository,
            playbackRepository = playbackRepository,
        )

        advanceUntilIdle()
        awaitSuccess(model)

        model.toggleSelection(firstEpisode, true, fromLongPress = true)
        model.toggleSelection(secondEpisode, true, fromLongPress = false)
        model.markSelectedEpisodesWatched(false)

        eventually(2.seconds) {
            episodeRepository.updateAllCalls.single() shouldBe listOf(
                AnimeEpisodeUpdate(id = firstEpisode.id, watched = false, completed = false),
                AnimeEpisodeUpdate(id = secondEpisode.id, watched = false, completed = false),
            )
            playbackRepository.upserts shouldBe listOf(
                AnimePlaybackState(episodeId = firstEpisode.id, positionMs = 0L, durationMs = 10_000L, completed = false, lastWatchedAt = 100L),
                AnimePlaybackState(episodeId = secondEpisode.id, positionMs = 0L, durationMs = 10_000L, completed = false, lastWatchedAt = 200L),
            )
            val state = model.state.value as AnimeScreenModel.State.Success
            state.selection shouldBe emptySet<Long>()
            state.isSelectionMode shouldBe false
        }
    }

    private fun createModel(
        anime: AnimeTitle,
        episodes: List<AnimeEpisode>,
        episodeRepository: AnimeEpisodeRepository = FakeAnimeEpisodeRepository(episodes),
        playbackRepository: AnimePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
    ): AnimeScreenModel {
        val animeRepository = FakeAnimeRepository(listOf(anime))
        val animeSourceManager = FakeAnimeSourceManager()
        return AnimeScreenModel(
            context = mockk<Application>(relaxed = true),
            animeId = anime.id,
            animeRepository = animeRepository,
            animeEpisodeRepository = episodeRepository,
            animePlaybackStateRepository = playbackRepository,
            animeSourceManager = animeSourceManager,
            getCategories = GetCategories(FakeCategoryRepository()),
            getAnimeCategories = fakeGetAnimeCategories(),
            setAnimeCategories = fakeSetAnimeCategories(),
            syncAnimeWithSource = SyncAnimeWithSource(
                animeRepository = animeRepository,
                animeEpisodeRepository = episodeRepository,
                animeSourceManager = animeSourceManager,
            ),
        )
    }

    private suspend fun awaitSuccess(model: AnimeScreenModel) {
        eventually(2.seconds) {
            (model.state.value is AnimeScreenModel.State.Success) shouldBe true
        }
    }

    private class FakeAnimeRepository(
        private val anime: List<AnimeTitle>,
    ) : AnimeRepository {
        override suspend fun getAnimeById(id: Long): AnimeTitle = anime.first { it.id == id }
        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = flowOf(anime.first { it.id == id })
        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = null
        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> = flowOf(null)
        override suspend fun getFavorites(): List<AnimeTitle> = anime
        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(anime)
        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = anime
        override suspend fun update(update: AnimeTitleUpdate): Boolean = true
        override suspend fun updateAll(animeUpdates: List<AnimeTitleUpdate>): Boolean = true
        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = animes
        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) = Unit
    }

    private class FakeAnimeEpisodeRepository(
        private val episodes: List<AnimeEpisode>,
    ) : AnimeEpisodeRepository {
        val updateAllCalls = mutableListOf<List<AnimeEpisodeUpdate>>()

        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> = episodes
        override suspend fun update(episodeUpdate: AnimeEpisodeUpdate) = Unit
        override suspend fun updateAll(episodeUpdates: List<AnimeEpisodeUpdate>) {
            updateAllCalls += episodeUpdates
        }
        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = Unit
        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> = episodes.filter { it.animeId == animeId }
        override fun getEpisodesByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeEpisode>> = flowOf(episodes.filter { it.animeId == animeId })
        override fun getEpisodesByAnimeIdsAsFlow(animeIds: List<Long>): Flow<List<AnimeEpisode>> = flowOf(episodes.filter { it.animeId in animeIds })
        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = episodes.firstOrNull { it.id == id }
        override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? = episodes.firstOrNull { it.url == url && it.animeId == animeId }
    }

    private class FakeAnimePlaybackStateRepository(
        private val statesByAnimeId: Map<Long, List<AnimePlaybackState>>,
    ) : AnimePlaybackStateRepository {
        val upserts = mutableListOf<AnimePlaybackState>()

        override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? = statesByAnimeId.values.flatten().firstOrNull { it.episodeId == episodeId }
        override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<AnimePlaybackState?> = flowOf(statesByAnimeId.values.flatten().firstOrNull { it.episodeId == episodeId })
        override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<AnimePlaybackState>> = flowOf(statesByAnimeId[animeId].orEmpty())
        override suspend fun upsert(state: AnimePlaybackState) {
            upserts += state
        }
        override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) = Unit
    }

    private class FakeAnimeSourceManager : AnimeSourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(emptyList<eu.kanade.tachiyomi.source.AnimeCatalogueSource>())
        override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.AnimeSource? = FakeAnimeSource(sourceKey)
        override fun getCatalogueSources(): List<eu.kanade.tachiyomi.source.AnimeCatalogueSource> = emptyList()
    }

    private class FakeAnimeSource(
        override val id: Long,
        override val name: String = "Fake",
        override val lang: String = "en",
    ) : eu.kanade.tachiyomi.source.AnimeSource {
        override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()

        override suspend fun getPlaybackData(
            episode: SEpisode,
            selection: VideoPlaybackSelection,
        ): VideoPlaybackData = error("Not used")
    }

    private class FakeCategoryRepository : CategoryRepository {
        private val categories = listOf(Category(Category.UNCATEGORIZED_ID, "", -1L, 0L))

        override suspend fun get(id: Long): Category? = categories.firstOrNull { it.id == id }
        override suspend fun getAll(): List<Category> = categories
        override fun getAllAsFlow(): Flow<List<Category>> = flowOf(categories)
        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> = emptyList()
        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> = flowOf(emptyList())
        override suspend fun getCategoriesByAnimeId(animeId: Long): List<Category> = emptyList()
        override fun getCategoriesByAnimeIdAsFlow(animeId: Long): Flow<List<Category>> = flowOf(emptyList())
        override suspend fun getAnimeCategoryIds(animeIds: List<Long>): Map<Long, List<Long>> = emptyMap()
        override suspend fun insert(category: Category) = Unit
        override suspend fun updatePartial(update: tachiyomi.domain.category.model.CategoryUpdate) = Unit
        override suspend fun updatePartial(updates: List<tachiyomi.domain.category.model.CategoryUpdate>) = Unit
        override suspend fun updateAllFlags(flags: Long?) = Unit
        override suspend fun delete(categoryId: Long) = Unit
    }

    private fun fakeGetAnimeCategories(): GetAnimeCategories {
        return GetAnimeCategories(FakeCategoryRepository())
    }

    private fun fakeSetAnimeCategories(): SetAnimeCategories {
        return SetAnimeCategories(FakeAnimeRepository(emptyList()))
    }
}
