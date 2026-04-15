package eu.kanade.tachiyomi.ui.anime.browse

import eu.kanade.domain.source.service.SourcePreferences
import io.mockk.every
import io.mockk.mockk
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.repository.AnimeSourcePagingSource
import tachiyomi.domain.source.repository.AnimeSourceRepository
import tachiyomi.domain.source.service.AnimeSourceManager
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeBrowseSourceScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `long press on favorite opens remove dialog`() = runTest(dispatcher) {
        val anime = anime(id = 1L, favorite = true)

        val model = createModel(anime = anime)

        model.onAnimeLongClick(anime) shouldBe true

        eventually(2.seconds) {
            model.state.value.dialog shouldBe AnimeBrowseSourceScreenModel.Dialog.RemoveAnime(anime)
        }
    }

    @Test
    fun `long press on non favorite with default category adds to library`() = runTest(dispatcher) {
        val anime = anime(id = 2L, favorite = false)
        val animeRepository = FakeAnimeRepository(listOf(anime))
        val categoryRepository = FakeCategoryRepository(
            categories = listOf(
                Category(0L, "Default", 0L, 0L),
                Category(1L, "Watching", 1L, 0L),
            ),
        )
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
            defaultCategory.set(1)
        }

        val model = createModel(
            anime = anime,
            animeRepository = animeRepository,
            categoryRepository = categoryRepository,
            libraryPreferences = libraryPreferences,
        )

        model.onAnimeLongClick(anime) shouldBe true

        eventually(2.seconds) {
            animeRepository.updates shouldContainExactly listOf(
                AnimeTitleUpdate(id = anime.id, favorite = true, dateAdded = animeRepository.updates.single().dateAdded),
            )
            animeRepository.categoryUpdates shouldContainExactly listOf(anime.id to listOf(1L))
            model.state.value.dialog shouldBe null
        }
    }

    @Test
    fun `long press on non favorite without default category opens category dialog`() = runTest(dispatcher) {
        val anime = anime(id = 3L, favorite = false)
        val categoryRepository = FakeCategoryRepository(
            categories = listOf(
                Category(0L, "Default", 0L, 0L),
                Category(2L, "Seasonal", 1L, 0L),
            ),
        )
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
            defaultCategory.set(-1)
        }

        val model = createModel(
            anime = anime,
            categoryRepository = categoryRepository,
            libraryPreferences = libraryPreferences,
        )

        model.onAnimeLongClick(anime) shouldBe true

        eventually(2.seconds) {
            val dialog = model.state.value.dialog as AnimeBrowseSourceScreenModel.Dialog.ChangeAnimeCategory
            dialog.anime shouldBe anime
            dialog.initialSelection.map { it.value.id } shouldContainExactly listOf(2L)
        }
    }

    private fun createModel(
        anime: AnimeTitle,
        animeRepository: FakeAnimeRepository = FakeAnimeRepository(listOf(anime)),
        categoryRepository: FakeCategoryRepository = FakeCategoryRepository(),
        libraryPreferences: LibraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
    ): AnimeBrowseSourceScreenModel {
        val sourcePreferences = SourcePreferences(InMemoryPreferenceStore(), testJson)
        val getIncognitoState = mockk<GetIncognitoState>()
        every { getIncognitoState.await(any()) } returns false
        return AnimeBrowseSourceScreenModel(
            sourceId = anime.source,
            listingQuery = null,
            animeSourceManager = FakeAnimeSourceManager(anime.source),
            sourcePreferences = sourcePreferences,
            libraryPreferences = libraryPreferences,
            getRemoteAnime = GetRemoteAnime(FakeAnimeSourceRepository()),
            getAnime = GetAnime(animeRepository),
            getCategories = GetCategories(categoryRepository),
            getAnimeCategories = GetAnimeCategories(categoryRepository),
            setAnimeCategories = SetAnimeCategories(animeRepository),
            animeRepository = animeRepository,
            getIncognitoState = getIncognitoState,
        )
    }

    private fun anime(id: Long, favorite: Boolean): AnimeTitle {
        return AnimeTitle.create().copy(
            id = id,
            source = 99L,
            favorite = favorite,
            title = "Anime $id",
            url = "/anime/$id",
            initialized = true,
        )
    }

    private class FakeAnimeRepository(
        private val anime: List<AnimeTitle>,
    ) : AnimeRepository {
        val updates = mutableListOf<AnimeTitleUpdate>()
        val categoryUpdates = mutableListOf<Pair<Long, List<Long>>>()

        override suspend fun getAnimeById(id: Long): AnimeTitle = anime.first { it.id == id }
        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = flowOf(anime.first { it.id == id })
        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = anime.firstOrNull { it.url == url && it.source == sourceId }
        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> = flowOf(
            anime.firstOrNull { it.url == url && it.source == sourceId },
        )
        override suspend fun getFavorites(): List<AnimeTitle> = anime.filter { it.favorite }
        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(anime.filter { it.favorite })
        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = anime
        override suspend fun update(update: AnimeTitleUpdate): Boolean {
            updates += update
            return true
        }
        override suspend fun updateAll(animeUpdates: List<AnimeTitleUpdate>): Boolean {
            updates += animeUpdates
            return true
        }
        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = animes
        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) {
            categoryUpdates += animeId to categoryIds
        }
    }

    private class FakeCategoryRepository(
        private val categories: List<Category> = listOf(Category(0L, "Default", 0L, 0L)),
        private val animeCategories: Map<Long, List<Category>> = emptyMap(),
    ) : CategoryRepository {
        override suspend fun get(id: Long): Category? = categories.firstOrNull { it.id == id }
        override suspend fun getAll(): List<Category> = categories
        override fun getAllAsFlow(): Flow<List<Category>> = flowOf(categories)
        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> = emptyList()
        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> = flowOf(emptyList())
        override suspend fun getCategoriesByAnimeId(animeId: Long): List<Category> = animeCategories[animeId].orEmpty()
        override fun getCategoriesByAnimeIdAsFlow(animeId: Long): Flow<List<Category>> = flowOf(animeCategories[animeId].orEmpty())
        override suspend fun getAnimeCategoryIds(animeIds: List<Long>): Map<Long, List<Long>> {
            return animeIds.associateWith { animeCategories[it].orEmpty().map(Category::id) }
        }
        override suspend fun insert(category: Category) = Unit
        override suspend fun updatePartial(update: CategoryUpdate) = Unit
        override suspend fun updatePartial(updates: List<CategoryUpdate>) = Unit
        override suspend fun updateAllFlags(flags: Long?) = Unit
        override suspend fun delete(categoryId: Long) = Unit
    }

    private class FakeAnimeSourceManager(
        private val sourceId: Long,
    ) : AnimeSourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(listOf(FakeAnimeCatalogueSource(sourceId)))
        override fun get(sourceKey: Long) = FakeAnimeCatalogueSource(sourceKey)
        override fun getCatalogueSources(): List<AnimeCatalogueSource> = listOf(FakeAnimeCatalogueSource(sourceId))
    }

    private class FakeAnimeCatalogueSource(
        override val id: Long,
    ) : AnimeCatalogueSource {
        override val name: String = "Fake Anime Source"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true
        override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getSearchAnime(page: Int, query: String, filters: FilterList): AnimesPage = AnimesPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)
        override fun getFilterList(): FilterList = FilterList()
        override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime
        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()
        override suspend fun getPlaybackData(
            episode: SEpisode,
            selection: VideoPlaybackSelection,
        ): VideoPlaybackData = error("Not used")
    }

    private class FakeAnimeSourceRepository : AnimeSourceRepository {
        override fun getPopular(sourceId: Long): AnimeSourcePagingSource = error("Not used")
        override fun getLatest(sourceId: Long): AnimeSourcePagingSource = error("Not used")
        override fun search(sourceId: Long, query: String, filterList: FilterList): AnimeSourcePagingSource = error("Not used")
    }
    companion object {
        private val testJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
