@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.anime

import android.app.Application
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.feature.profiles.core.ProfileAwareStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.AnimeSourceManager
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeLibraryScreenModelTest {

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
    fun `prefers in-progress episode as primary library action`() = runTest(dispatcher) {
        val video = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Video 1",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val firstEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = 1L,
            url = "/e/1",
            name = "Episode 1",
            sourceOrder = 0L,
            completed = false,
        )
        val secondEpisode = AnimeEpisode.create().copy(
            id = 11L,
            animeId = 1L,
            url = "/e/2",
            name = "Episode 2",
            sourceOrder = 1L,
            completed = false,
        )
        val inProgress =
            AnimePlaybackState(
                episodeId = secondEpisode.id,
                positionMs = 5_000L,
                durationMs = 10_000L,
                completed = false,
                lastWatchedAt = 100L,
            )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(video)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode)),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(mapOf(video.id to listOf(inProgress))),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value
            state.libraryItems.single().primaryEpisodeId shouldBe secondEpisode.id
            state.libraryItems.single().hasInProgress shouldBe true
            state.pages.single().itemIds shouldBe listOf(video.id)
        }
    }

    @Test
    fun `returns random item from current page`() = runTest(dispatcher) {
        val video = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Video 1",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(video)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.getRandomLibraryItemForCurrentPage()?.animeId shouldBe video.id
        }
    }

    @Test
    fun `returns no random item when library is empty`() = runTest(dispatcher) {
        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(emptyList()),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.getRandomLibraryItemForCurrentPage() shouldBe null
        }
    }

    @Test
    fun `filters library by unwatched anime preference`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            animeFilterUnwatched.set(TriState.ENABLED_IS)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                listOf(
                    AnimeEpisode.create().copy(id = 10L, animeId = first.id, completed = false),
                    AnimeEpisode.create().copy(id = 11L, animeId = second.id, completed = true),
                ),
            ),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value
            state.hasActiveFilters shouldBe true
            state.isLibraryEmpty shouldBe false
            state.libraryItems.map { it.animeId } shouldBe listOf(first.id)
        }
    }

    @Test
    fun `filters library by started anime preference`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Started",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Not started",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val firstEpisode = AnimeEpisode.create().copy(id = 10L, animeId = first.id, watched = true, completed = false)
        val secondEpisode = AnimeEpisode.create().copy(
            id = 11L,
            animeId = second.id,
            watched = false,
            completed = false,
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            animeFilterStarted.set(TriState.ENABLED_IS)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode)),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value
            state.hasActiveFilters shouldBe true
            state.libraryItems.map { it.animeId } shouldBe listOf(first.id)
        }
    }

    @Test
    fun `unfiltered library stays non-empty when filters exclude all anime`() = runTest(dispatcher) {
        val video = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Completed anime",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            animeFilterUnwatched.set(TriState.ENABLED_IS)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(video)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                listOf(AnimeEpisode.create().copy(id = 10L, animeId = video.id, completed = true)),
            ),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value
            state.hasActiveFilters shouldBe true
            state.isLibraryEmpty shouldBe false
            state.libraryItems shouldBe emptyList()
            state.pages.map { it.category?.id to it.itemIds } shouldBe listOf(
                Category.UNCATEGORIZED_ID to emptyList(),
            )
        }
    }

    @Test
    fun `search supports anime id queries`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()
        model.search("id:2")
        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.libraryItems.map { it.animeId } shouldBe listOf(second.id)
        }
    }

    @Test
    fun `search supports anime source id queries`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()
        model.search("src:100")
        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.libraryItems.map { it.animeId } shouldBe listOf(second.id)
        }
    }

    @Test
    fun `search supports negated genre and source matching`() = runTest(dispatcher) {
        val action = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Action",
            favorite = true,
            initialized = true,
            url = "/video/1",
            genre = listOf("Action"),
        )
        val drama = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Drama",
            favorite = true,
            initialized = true,
            url = "/video/2",
            genre = listOf("Drama"),
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(action, drama)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(
                sources = mapOf(
                    99L to FakeAnimeSource(id = 99L, name = "Alpha"),
                    100L to FakeAnimeSource(id = 100L, name = "Beta"),
                ),
            ),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()
        model.search("-Action,-Alpha")
        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.libraryItems.map { it.animeId } shouldBe listOf(drama.id)
        }
    }

    @Test
    fun `sorts anime library by date added`() = runTest(dispatcher) {
        val older = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Older",
            favorite = true,
            initialized = true,
            url = "/video/1",
            dateAdded = 100L,
        )
        val newer = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Newer",
            favorite = true,
            initialized = true,
            url = "/video/2",
            dateAdded = 200L,
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            sortingMode.set(LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending))
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(older, newer)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(newer.id, older.id)
        }
    }

    @Test
    fun `sorts anime library by unwatched count`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            sortingMode.set(LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Descending))
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                listOf(
                    AnimeEpisode.create().copy(id = 10L, animeId = first.id, completed = false),
                    AnimeEpisode.create().copy(id = 11L, animeId = first.id, completed = false),
                    AnimeEpisode.create().copy(id = 12L, animeId = second.id, completed = false),
                ),
            ),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(first.id, second.id)
        }
    }

    @Test
    fun `unread badge preference hides badge count without affecting sort data`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            unreadBadge.set(false)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(anime)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                listOf(
                    AnimeEpisode.create().copy(id = 10L, animeId = anime.id, completed = false),
                    AnimeEpisode.create().copy(id = 11L, animeId = anime.id, completed = false),
                ),
            ),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val item = model.state.value.libraryItems.single()
            item.unwatchedCount shouldBe 2L
            item.unwatchedBadgeCount shouldBe 0L
        }
    }

    @Test
    fun `language badge preference clears source language`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            languageBadge.set(false)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(anime)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.libraryItems.single().sourceLanguage shouldBe ""
        }
    }

    @Test
    fun `continue watching follows manga library preference logic`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val episode = AnimeEpisode.create().copy(id = 10L, animeId = anime.id, completed = false)

        val hiddenByContinuePref = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(anime)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(listOf(episode)),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                unreadBadge.set(true)
                showContinueReadingButton.set(false)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        val hiddenByUnreadPref = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(anime)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(listOf(episode)),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                unreadBadge.set(false)
                showContinueReadingButton.set(true)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        val shownWhenBothEnabled = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(anime)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(listOf(episode)),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                unreadBadge.set(true)
                showContinueReadingButton.set(true)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            hiddenByContinuePref.state.value.libraryItems.single().showContinueWatching shouldBe false
            hiddenByContinuePref.state.value.libraryItems.single().unwatchedBadgeCount shouldBe 1L

            hiddenByUnreadPref.state.value.libraryItems.single().showContinueWatching shouldBe true
            hiddenByUnreadPref.state.value.libraryItems.single().unwatchedBadgeCount shouldBe 0L

            shownWhenBothEnabled.state.value.libraryItems.single().showContinueWatching shouldBe true
            shownWhenBothEnabled.state.value.libraryItems.single().unwatchedBadgeCount shouldBe 1L
        }
    }

    @Test
    fun `merged library continue uses reading order across members`() = runTest(dispatcher) {
        val target = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Target",
            favorite = true,
            initialized = true,
            url = "/video/1",
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )
        val member = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Member",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val targetEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = target.id,
            episodeNumber = 1.0,
            sourceOrder = 1L,
            completed = false,
        )
        val memberEpisode = AnimeEpisode.create().copy(
            id = 20L,
            animeId = member.id,
            episodeNumber = 1.0,
            sourceOrder = 1L,
            completed = false,
        )
        val mergedRepository = FakeMergedAnimeRepository(
            listOf(
                AnimeMerge(targetId = target.id, animeId = target.id, position = 0L),
                AnimeMerge(targetId = target.id, animeId = member.id, position = 1L),
            ),
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(target, member)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(listOf(targetEpisode, memberEpisode)),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            getMergedAnime = GetMergedAnime(mergedRepository),
            updateMergedAnime = UpdateMergedAnime(mergedRepository),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val item = model.state.value.libraryItems.single()
            item.animeId shouldBe target.id
            item.memberAnimeIds shouldBe listOf(target.id, member.id)
            item.primaryEpisodeId shouldBe memberEpisode.id
            item.primaryEpisodeAnimeId shouldBe member.id
        }
    }

    @Test
    fun `library merge dialog places new selection before existing merged entries`() = runTest(dispatcher) {
        val target = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Target",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val member = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Member",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val extra = AnimeTitle.create().copy(
            id = 3L,
            source = 101L,
            title = "Extra",
            favorite = true,
            initialized = true,
            url = "/video/3",
        )
        val mergedRepository = FakeMergedAnimeRepository(
            listOf(
                AnimeMerge(targetId = target.id, animeId = target.id, position = 0L),
                AnimeMerge(targetId = target.id, animeId = member.id, position = 1L),
            ),
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(target, member, extra)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L, 101L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            getMergedAnime = GetMergedAnime(mergedRepository),
            updateMergedAnime = UpdateMergedAnime(mergedRepository),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(extra.id, target.id)
        }

        val page = model.state.value.pages.single()
        model.state.value.getItemsForPage(page).forEach { item ->
            model.toggleSelection(page, item)
        }
        model.openMergeDialog()

        eventually(2.seconds) {
            val dialog = model.state.value.dialog as AnimeLibraryScreenModel.Dialog.MergeAnime
            dialog.entries.map { it.id } shouldBe listOf(extra.id, target.id, member.id)
            dialog.targetId shouldBe target.id
            dialog.targetLocked shouldBe false
            dialog.entries.count { it.isFromExistingMerge } shouldBe 2
        }
    }

    @Test
    fun `category grouping hides system category when no anime are uncategorized`() = runTest(dispatcher) {
        val categorized = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Categorized",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val categories = listOf(
            Category(Category.UNCATEGORIZED_ID, "", -1L, 0L),
            Category(id = 1L, name = "Favorites", order = 0L, flags = 0L),
        )
        val repo = FakeCategoryRepository(categories, mapOf(categorized.id to listOf(1L)))

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(categorized)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = GetAnimeCategories(repo),
            getCategories = GetCategories(repo),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = repo,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                groupType.set(LibraryGroupType.Category)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.map { it.category?.id } shouldBe listOf(1L)
        }
    }

    @Test
    fun `category grouping shows system category for uncategorized anime and preserves empty categories`() = runTest(
        dispatcher,
    ) {
        val uncategorized = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Uncategorized",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val categories = listOf(
            Category(Category.UNCATEGORIZED_ID, "", -1L, 0L),
            Category(id = 1L, name = "Favorites", order = 0L, flags = 0L),
        )

        val repo = FakeCategoryRepository(categories)
        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(uncategorized)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = GetAnimeCategories(repo),
            getCategories = GetCategories(repo),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = repo,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                groupType.set(LibraryGroupType.Category)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.map { it.category?.id to it.itemIds } shouldBe listOf(
                Category.UNCATEGORIZED_ID to listOf(uncategorized.id),
                1L to emptyList(),
            )
        }
    }

    @Test
    fun `category extension preserves empty category pages`() = runTest(dispatcher) {
        val uncategorized = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Uncategorized",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val categories = listOf(
            Category(Category.UNCATEGORIZED_ID, "", -1L, 0L),
            Category(id = 1L, name = "Favorites", order = 0L, flags = 0L),
        )

        val repo = FakeCategoryRepository(categories)
        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(uncategorized)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = GetAnimeCategories(repo),
            getCategories = GetCategories(repo),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = repo,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                groupType.set(LibraryGroupType.CategoryExtension)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.map { it.id to it.itemIds } shouldBe listOf(
                "category:0:source:99" to listOf(uncategorized.id),
                "category:1" to emptyList(),
            )
        }
    }

    @Test
    fun `extension category omits empty source category combinations`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val categories = listOf(
            Category(Category.UNCATEGORIZED_ID, "", -1L, 0L),
            Category(id = 1L, name = "Favorites", order = 0L, flags = 0L),
        )
        val categoryIdsByAnimeId = mapOf(
            first.id to listOf(1L),
        )
        val repo = FakeCategoryRepository(categories, categoryIdsByAnimeId)

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = GetAnimeCategories(repo),
            getCategories = GetCategories(repo),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = repo,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()).apply {
                groupType.set(LibraryGroupType.ExtensionCategory)
            },
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.map { it.id to it.itemIds } shouldBe listOf(
                "source:99:1" to listOf(first.id),
                "source:100:0" to listOf(second.id),
            )
        }
    }

    @Test
    fun `uses category sort override only when grouped by category`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Alpha",
            favorite = true,
            initialized = true,
            url = "/video/1",
            dateAdded = 100L,
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Zulu",
            favorite = true,
            initialized = true,
            url = "/video/2",
            dateAdded = 200L,
        )
        val category = Category(
            id = 1L,
            name = "Favorites",
            order = 0L,
            flags = LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending).flag,
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            groupType.set(LibraryGroupType.Category)
            sortingMode.set(LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending))
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                listOf(AnimeEpisode.create().copy(id = 10L, animeId = first.id, watched = true, completed = false)),
            ),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository(listOf(category))),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(
                categories = listOf(category),
                categoryIdsByAnimeId = mapOf(first.id to listOf(category.id), second.id to listOf(category.id)),
            ),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value
            state.activeSortCategory?.id shouldBe category.id
            state.pages.single().itemIds shouldBe listOf(second.id, first.id)
        }
    }

    @Test
    fun `ignores category sort override when not grouped by category`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Alpha",
            favorite = true,
            initialized = true,
            url = "/video/1",
            dateAdded = 100L,
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Zulu",
            favorite = true,
            initialized = true,
            url = "/video/2",
            dateAdded = 200L,
        )
        val category = Category(
            id = 1L,
            name = "Favorites",
            order = 0L,
            flags = LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending).flag,
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            groupType.set(LibraryGroupType.Extension)
            sortingMode.set(LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending))
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository(listOf(category))),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(
                categories = listOf(category),
                categoryIdsByAnimeId = mapOf(first.id to listOf(category.id), second.id to listOf(category.id)),
            ),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value
            state.activeSortCategory shouldBe null
            state.pages.single().itemIds shouldBe listOf(first.id, second.id)
        }
    }

    @Test
    fun `toggle selection enters and clears selection mode`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(first.id, second.id)
        }

        val page = model.state.value.pages.single()
        val firstItem = model.state.value.getItemsForPage(page).first { it.animeId == first.id }

        model.toggleSelection(page, firstItem)
        model.state.value.selection shouldBe setOf(first.id)
        model.state.value.selectionMode shouldBe true

        model.toggleSelection(page, firstItem)
        model.state.value.selection shouldBe emptySet()
        model.state.value.selectionMode shouldBe false
    }

    @Test
    fun `range selection selects inclusive range on same page`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Alpha",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Beta",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val third = AnimeTitle.create().copy(
            id = 3L,
            source = 99L,
            title = "Gamma",
            favorite = true,
            initialized = true,
            url = "/video/3",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second, third)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(first.id, second.id, third.id)
        }

        val page = model.state.value.pages.single()
        val items = model.state.value.getItemsForPage(page)

        model.toggleRangeSelection(page, items.first { it.animeId == first.id })
        model.toggleRangeSelection(page, items.first { it.animeId == third.id })

        model.state.value.selection shouldBe setOf(first.id, second.id, third.id)
    }

    @Test
    fun `range selection does not cross pages`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Source A",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Source B",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            groupType.set(LibraryGroupType.Extension)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.first { it.sourceId == 99L }.itemIds shouldBe listOf(first.id)
            model.state.value.pages.first { it.sourceId == 100L }.itemIds shouldBe listOf(second.id)
        }

        val firstPage = model.state.value.pages.first { it.sourceId == 99L }
        val secondPage = model.state.value.pages.first { it.sourceId == 100L }
        val firstItem = model.state.value.getItemsForPage(firstPage).single()
        val secondItem = model.state.value.getItemsForPage(secondPage).single()

        model.toggleRangeSelection(firstPage, firstItem)
        model.toggleRangeSelection(secondPage, secondItem)

        model.state.value.selection shouldBe setOf(first.id, second.id)
    }

    @Test
    fun `select all selects active page only`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Source A1",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Source A2",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val third = AnimeTitle.create().copy(
            id = 3L,
            source = 100L,
            title = "Source B1",
            favorite = true,
            initialized = true,
            url = "/video/3",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            groupType.set(LibraryGroupType.Extension)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second, third)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.first { it.sourceId == 99L }.itemIds shouldBe listOf(first.id, second.id)
            model.state.value.pages.first { it.sourceId == 100L }.itemIds shouldBe listOf(third.id)
        }

        model.updateActivePageIndex(0)
        model.selectAll()

        model.state.value.selection shouldBe setOf(first.id, second.id)
    }

    @Test
    fun `invert selection toggles active page only and preserves off-page selections`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Source A1",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Source A2",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val third = AnimeTitle.create().copy(
            id = 3L,
            source = 100L,
            title = "Source B1",
            favorite = true,
            initialized = true,
            url = "/video/3",
        )
        val prefs = LibraryPreferences(InMemoryPreferenceStore()).apply {
            groupType.set(LibraryGroupType.Extension)
        }

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second, third)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(sourceIds = listOf(99L, 100L)),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.first { it.sourceId == 99L }.itemIds shouldBe listOf(first.id, second.id)
            model.state.value.pages.first { it.sourceId == 100L }.itemIds shouldBe listOf(third.id)
        }

        val secondPage = model.state.value.pages.first { it.sourceId == 100L }
        val secondPageItem = model.state.value.getItemsForPage(secondPage).single()
        model.toggleSelection(secondPage, secondPageItem)

        model.updateActivePageIndex(0)
        val firstPage = model.state.value.activePage!!
        val firstPageItem = model.state.value.getItemsForPage(firstPage).first { it.animeId == first.id }
        model.toggleSelection(firstPage, firstPageItem)

        model.invertSelection()
        model.state.value.selection shouldBe setOf(second.id, third.id)
    }

    @Test
    fun `selection survives state rebuilds for visible items`() = runTest(dispatcher) {
        val store = InMemoryPreferenceStore()
        val prefs = LibraryPreferences(store)
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(
                listOf(
                    AnimeEpisode.create().copy(id = 10L, animeId = first.id, watched = true, completed = false),
                ),
            ),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(first.id, second.id)
        }

        val page = model.state.value.pages.single()
        val firstItem = model.state.value.getItemsForPage(page).first { it.animeId == first.id }

        model.toggleSelection(page, firstItem)
        prefs.animeFilterStarted.set(TriState.ENABLED_IS)

        eventually(2.seconds) {
            val state = model.state.value
            state.libraryItems.map { it.animeId } shouldBe listOf(first.id)
            state.selection shouldBe setOf(first.id)
            state.dialog shouldBe null
        }
    }

    @Test
    fun `selection clears when rebuild hides selected items`() = runTest(dispatcher) {
        val prefs = LibraryPreferences(InMemoryPreferenceStore())
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = prefs,
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.single().itemIds shouldBe listOf(first.id, second.id)
        }

        val page = model.state.value.pages.single()
        val firstItem = model.state.value.getItemsForPage(page).first { it.animeId == first.id }

        model.toggleSelection(page, firstItem)
        prefs.animeFilterStarted.set(TriState.ENABLED_IS)

        eventually(2.seconds) {
            val state = model.state.value
            state.libraryItems shouldBe emptyList()
            state.selection shouldBe emptySet()
            state.dialog shouldBe null
        }
    }

    @Test
    fun `bulk category dialog preselects common and mixed categories`() = runTest(dispatcher) {
        val common = Category(id = 1L, name = "Common", order = 0L, flags = 0L)
        val mixed = Category(id = 2L, name = "Mixed", order = 1L, flags = 0L)
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val categoryRepository = FakeCategoryRepository(
            categories = listOf(common, mixed),
            categoryIdsByAnimeId = mapOf(
                first.id to listOf(common.id, mixed.id),
                second.id to listOf(common.id),
            ),
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = GetAnimeCategories(categoryRepository),
            getCategories = GetCategories(categoryRepository),
            setAnimeCategories = SetAnimeCategories(FakeAnimeRepository(listOf(first, second))),
            categoryRepository = categoryRepository,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.any { page ->
                page.itemIds.containsAll(listOf(first.id, second.id))
            } shouldBe true
        }

        val page = model.state.value.pages.first { currentPage ->
            currentPage.itemIds.containsAll(listOf(first.id, second.id))
        }
        model.state.value.getItemsForPage(page).forEach { model.toggleSelection(page, it) }
        model.openChangeCategoryDialog()
        advanceUntilIdle()

        eventually(2.seconds) {
            val dialog = model.state.value.dialog as AnimeLibraryScreenModel.Dialog.ChangeCategory
            (dialog.initialSelection.first { it.value.id == common.id } is CheckboxState.State.Checked) shouldBe true
            (dialog.initialSelection.first { it.value.id == mixed.id } is CheckboxState.TriState.Exclude) shouldBe true
        }
    }

    @Test
    fun `bulk category apply merges includes and excludes per anime`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val categoryRepository = FakeCategoryRepository(
            categories = listOf(
                Category(id = 1L, name = "Keep", order = 0L, flags = 0L),
                Category(id = 2L, name = "Remove", order = 1L, flags = 0L),
                Category(id = 3L, name = "Add", order = 2L, flags = 0L),
            ),
            categoryIdsByAnimeId = mutableMapOf(
                first.id to listOf(1L, 2L),
                second.id to listOf(2L),
            ),
        )
        val animeRepository = FakeAnimeRepository(listOf(first, second))

        val model = AnimeLibraryScreenModel(
            animeRepository = animeRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = GetAnimeCategories(categoryRepository),
            getCategories = GetCategories(categoryRepository),
            setAnimeCategories = SetAnimeCategories(animeRepository),
            categoryRepository = categoryRepository,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        model.setAnimeCategories(listOf(first.id, second.id), addCategories = listOf(3L), removeCategories = listOf(2L))

        eventually(2.seconds) {
            animeRepository.setCategoriesCalls.size shouldBe 2
            animeRepository.setCategoriesCalls[0].first shouldBe first.id
            animeRepository.setCategoriesCalls[0].second shouldBe listOf(1L, 3L)
            animeRepository.setCategoriesCalls[1].first shouldBe second.id
            animeRepository.setCategoriesCalls[1].second shouldBe listOf(3L)
        }
    }

    @Test
    fun `remove dialog opens for selected anime`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.any { page ->
                page.itemIds.containsAll(listOf(first.id, second.id))
            } shouldBe true
        }

        val page = model.state.value.pages.first { currentPage ->
            currentPage.itemIds.containsAll(listOf(first.id, second.id))
        }
        model.state.value.getItemsForPage(page).forEach { model.toggleSelection(page, it) }
        model.openRemoveAnimeDialog()

        eventually(2.seconds) {
            val dialog = model.state.value.dialog as AnimeLibraryScreenModel.Dialog.RemoveAnime
            dialog.animeIds.sorted() shouldBe listOf(first.id, second.id)
        }
    }

    @Test
    fun `remove anime bulk-unfavorites selected anime`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val animeRepository = FakeAnimeRepository(listOf(first, second))

        val model = AnimeLibraryScreenModel(
            animeRepository = animeRepository,
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        model.removeAnime(listOf(first.id, second.id, first.id))

        eventually(2.seconds) {
            animeRepository.updates shouldBe listOf(
                AnimeTitleUpdate(id = first.id, favorite = false, dateAdded = 0L),
                AnimeTitleUpdate(id = second.id, favorite = false, dateAdded = 0L),
            )
        }
    }

    @Test
    fun `mark watched selection updates episodes and playback state`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val firstEpisode = AnimeEpisode.create().copy(id = 10L, animeId = first.id, watched = false, completed = false)
        val secondEpisode = AnimeEpisode.create().copy(id = 11L, animeId = second.id, watched = true, completed = false)
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode))
        val playbackRepository = FakeAnimePlaybackStateRepository(
            mapOf(
                first.id to
                    listOf(
                        AnimePlaybackState(
                            episodeId = firstEpisode.id,
                            positionMs = 5_000L,
                            durationMs = 10_000L,
                            completed = false,
                            lastWatchedAt = 100L,
                        ),
                    ),
                second.id to
                    listOf(
                        AnimePlaybackState(
                            episodeId = secondEpisode.id,
                            positionMs = 7_500L,
                            durationMs = 10_000L,
                            completed = false,
                            lastWatchedAt = 200L,
                        ),
                    ),
            ),
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = episodeRepository,
            animePlaybackStateRepository = playbackRepository,
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.any { page ->
                page.itemIds.containsAll(listOf(first.id, second.id))
            } shouldBe true
        }

        val page = model.state.value.pages.first { currentPage ->
            currentPage.itemIds.containsAll(listOf(first.id, second.id))
        }
        model.state.value.getItemsForPage(page).forEach { model.toggleSelection(page, it) }

        model.markWatchedSelection(true)

        eventually(2.seconds) {
            episodeRepository.updateAllCalls.single() shouldBe listOf(
                AnimeEpisodeUpdate(id = firstEpisode.id, watched = true, completed = true),
                AnimeEpisodeUpdate(id = secondEpisode.id, watched = true, completed = true),
            )
            playbackRepository.upserts shouldBe listOf(
                AnimePlaybackState(
                    episodeId = firstEpisode.id,
                    positionMs = 5_000L,
                    durationMs = 10_000L,
                    completed = true,
                    lastWatchedAt = 100L,
                ),
                AnimePlaybackState(
                    episodeId = secondEpisode.id,
                    positionMs = 7_500L,
                    durationMs = 10_000L,
                    completed = true,
                    lastWatchedAt = 200L,
                ),
            )
            model.state.value.selection shouldBe emptySet()
        }
    }

    @Test
    fun `mark unwatched selection resets episodes and playback state`() = runTest(dispatcher) {
        val first = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "First",
            favorite = true,
            initialized = true,
            url = "/video/1",
        )
        val second = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Second",
            favorite = true,
            initialized = true,
            url = "/video/2",
        )
        val firstEpisode = AnimeEpisode.create().copy(id = 10L, animeId = first.id, watched = true, completed = true)
        val secondEpisode = AnimeEpisode.create().copy(id = 11L, animeId = second.id, watched = true, completed = false)
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode))
        val playbackRepository = FakeAnimePlaybackStateRepository(
            mapOf(
                first.id to
                    listOf(
                        AnimePlaybackState(
                            episodeId = firstEpisode.id,
                            positionMs = 10_000L,
                            durationMs = 10_000L,
                            completed = true,
                            lastWatchedAt = 100L,
                        ),
                    ),
                second.id to
                    listOf(
                        AnimePlaybackState(
                            episodeId = secondEpisode.id,
                            positionMs = 5_000L,
                            durationMs = 10_000L,
                            completed = false,
                            lastWatchedAt = 200L,
                        ),
                    ),
            ),
        )

        val model = AnimeLibraryScreenModel(
            animeRepository = FakeAnimeRepository(listOf(first, second)),
            animeEpisodeRepository = episodeRepository,
            animePlaybackStateRepository = playbackRepository,
            animeHistoryRepository = FakeAnimeHistoryRepository(),
            animeSourceManager = FakeAnimeSourceManager(),
            getAnimeCategories = fakeGetAnimeCategories(),
            getCategories = GetCategories(FakeCategoryRepository()),
            setAnimeCategories = fakeSetAnimeCategories(),
            categoryRepository = FakeCategoryRepository(),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            setSortModeForCategory = fakeSetSortModeForCategory(),
            profileStore = FakeProfileAwareStore(),
            application = mockk<Application>(relaxed = true),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            model.state.value.pages.any { page ->
                page.itemIds.containsAll(listOf(first.id, second.id))
            } shouldBe true
        }

        val page = model.state.value.pages.first { currentPage ->
            currentPage.itemIds.containsAll(listOf(first.id, second.id))
        }
        model.state.value.getItemsForPage(page).forEach { model.toggleSelection(page, it) }

        model.markWatchedSelection(false)

        eventually(2.seconds) {
            episodeRepository.updateAllCalls.single() shouldBe listOf(
                AnimeEpisodeUpdate(id = firstEpisode.id, watched = false, completed = false),
                AnimeEpisodeUpdate(id = secondEpisode.id, watched = false, completed = false),
            )
            playbackRepository.upserts shouldBe listOf(
                AnimePlaybackState(
                    episodeId = firstEpisode.id,
                    positionMs = 0L,
                    durationMs = 10_000L,
                    completed = false,
                    lastWatchedAt = 100L,
                ),
                AnimePlaybackState(
                    episodeId = secondEpisode.id,
                    positionMs = 0L,
                    durationMs = 10_000L,
                    completed = false,
                    lastWatchedAt = 200L,
                ),
            )
            model.state.value.selection shouldBe emptySet()
        }
    }

    private class FakeAnimeRepository(
        private val favorites: List<AnimeTitle>,
    ) : AnimeRepository {
        val setCategoriesCalls = mutableListOf<Pair<Long, List<Long>>>()
        val updates = mutableListOf<AnimeTitleUpdate>()

        override suspend fun getAnimeById(id: Long): AnimeTitle = favorites.first { it.id == id }
        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = flowOf(favorites.first { it.id == id })
        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = null
        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> = flowOf(null)
        override suspend fun getFavorites(): List<AnimeTitle> = favorites
        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(favorites)
        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = favorites
        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean = true
        override suspend fun update(update: AnimeTitleUpdate): Boolean {
            updates += update
            return true
        }
        override suspend fun updateAll(
            animeUpdates: List<tachiyomi.domain.anime.model.AnimeTitleUpdate>,
        ): Boolean = true
        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = animes
        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) {
            setCategoriesCalls += animeId to categoryIds
        }
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
        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> = episodes.filter {
            it.animeId ==
                animeId
        }
        override fun getEpisodesByAnimeIdAsFlow(
            animeId: Long,
        ): Flow<List<AnimeEpisode>> = flowOf(
            episodes.filter {
                it.animeId ==
                    animeId
            },
        )
        override fun getEpisodesByAnimeIdsAsFlow(
            animeIds: List<Long>,
        ): Flow<List<AnimeEpisode>> = flowOf(
            episodes.filter {
                it.animeId in
                    animeIds
            },
        )
        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = episodes.firstOrNull { it.id == id }
        override suspend fun getEpisodeByUrlAndAnimeId(
            url: String,
            animeId: Long,
        ): AnimeEpisode? = episodes.firstOrNull {
            it.url ==
                url &&
                it.animeId == animeId
        }
    }

    private class FakeAnimePlaybackStateRepository(
        private val statesByVideoId: Map<Long, List<AnimePlaybackState>>,
    ) : AnimePlaybackStateRepository {
        val upserts = mutableListOf<AnimePlaybackState>()

        override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? = statesByVideoId.values.flatten().firstOrNull {
            it.episodeId ==
                episodeId
        }
        override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<AnimePlaybackState?> {
            return flowOf(statesByVideoId.values.flatten().firstOrNull { it.episodeId == episodeId })
        }
        override fun getByAnimeIdAsFlow(
            animeId: Long,
        ): Flow<List<AnimePlaybackState>> = flowOf(statesByVideoId[animeId].orEmpty())
        override suspend fun upsert(state: AnimePlaybackState) {
            upserts += state
        }
        override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) = Unit
    }

    private class FakeAnimeHistoryRepository : AnimeHistoryRepository {
        override fun getHistory(query: String): Flow<List<AnimeHistoryWithRelations>> = emptyFlow()
        override suspend fun getLastHistory(): AnimeHistoryWithRelations? = null
        override fun getLastHistoryAsFlow(): Flow<AnimeHistoryWithRelations?> = flowOf(null)
        override suspend fun getTotalWatchedDuration(): Long = 0L
        override suspend fun getHistoryByAnimeId(animeId: Long): List<AnimeHistory> = emptyList()
        override suspend fun upsertHistory(historyUpdate: AnimeHistoryUpdate) = Unit
        override suspend fun resetHistory(historyId: Long) = Unit
        override suspend fun resetHistoryByAnimeId(animeId: Long) = Unit
        override suspend fun deleteAllHistory(): Boolean = true
    }

    private class FakeAnimeSourceManager(
        sourceIds: List<Long> = listOf(99L),
        sources: Map<Long, FakeAnimeSource>? = null,
    ) : AnimeSourceManager {
        private val sources = sources ?: sourceIds.associateWith(::FakeAnimeSource)

        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = emptyFlow<List<eu.kanade.tachiyomi.source.AnimeCatalogueSource>>()
        override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.AnimeSource? = sources[sourceKey]
        override fun getCatalogueSources(): List<eu.kanade.tachiyomi.source.AnimeCatalogueSource> = emptyList()
    }

    private class FakeAnimeSource(
        override val id: Long,
        override val name: String = "Fake",
        override val lang: String = "en",
    ) : eu.kanade.tachiyomi.source.AnimeSource {
        override suspend fun getAnimeDetails(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getEpisodeList(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

        override suspend fun getPlaybackData(
            episode: eu.kanade.tachiyomi.source.model.SEpisode,
            selection: eu.kanade.tachiyomi.source.model.VideoPlaybackSelection,
        ) = error("Not used")
    }

    private class FakeMergedAnimeRepository(
        private var merges: List<AnimeMerge>,
    ) : MergedAnimeRepository {
        override suspend fun getAll(): List<AnimeMerge> = merges
        override fun subscribeAll(): Flow<List<AnimeMerge>> = flowOf(merges)
        override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> {
            val targetId = merges.firstOrNull { it.animeId == animeId }?.targetId ?: return emptyList()
            return merges.filter { it.targetId == targetId }
        }
        override fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> = flowOf(
            run {
                val targetId = merges.firstOrNull { it.animeId == animeId }?.targetId
                merges.filter { it.targetId == targetId }
            },
        )
        override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> = merges.filter {
            it.targetId ==
                targetAnimeId
        }
        override suspend fun getTargetId(animeId: Long): Long? = merges.firstOrNull { it.animeId == animeId }?.targetId
        override fun subscribeTargetId(
            animeId: Long,
        ): Flow<Long?> = flowOf(merges.firstOrNull { it.animeId == animeId }?.targetId)
        override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) {
            merges = merges.filterNot { it.animeId in orderedAnimeIds } + orderedAnimeIds.mapIndexed { index, animeId ->
                AnimeMerge(targetId = targetAnimeId, animeId = animeId, position = index.toLong())
            }
        }
        override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) {
            merges = merges.filterNot { it.targetId == targetAnimeId && it.animeId in animeIds }
        }
        override suspend fun deleteGroup(targetAnimeId: Long) {
            merges = merges.filterNot { it.targetId == targetAnimeId }
        }
    }

    private class FakeCategoryRepository(
        private val categories: List<Category> = listOf(Category(Category.UNCATEGORIZED_ID, "", -1L, 0L)),
        private val categoryIdsByAnimeId: Map<Long, List<Long>> = emptyMap(),
    ) : CategoryRepository {

        override suspend fun get(id: Long): Category? = categories.firstOrNull { it.id == id }
        override suspend fun getAll(): List<Category> = categories
        override fun getAllAsFlow(): Flow<List<Category>> = flowOf(categories)
        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> = emptyList()
        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> = flowOf(emptyList())
        override suspend fun getCategoriesByAnimeId(animeId: Long): List<Category> =
            categoryIdsByAnimeId[animeId].orEmpty().mapNotNull { id -> categories.firstOrNull { it.id == id } }

        override fun getCategoriesByAnimeIdAsFlow(animeId: Long): Flow<List<Category>> =
            flowOf(categoryIdsByAnimeId[animeId].orEmpty().mapNotNull { id -> categories.firstOrNull { it.id == id } })
        override suspend fun getAnimeCategoryIds(animeIds: List<Long>): Map<Long, List<Long>> =
            categoryIdsByAnimeId.filterKeys { it in animeIds }
        override suspend fun insert(category: Category) = Unit
        override suspend fun updatePartial(update: tachiyomi.domain.category.model.CategoryUpdate) = Unit
        override suspend fun updatePartial(updates: List<tachiyomi.domain.category.model.CategoryUpdate>) = Unit
        override suspend fun updateAllFlags(flags: Long?) = Unit
        override suspend fun delete(categoryId: Long) = Unit
    }

    private fun fakeSetSortModeForCategory(): SetSortModeForCategory {
        return SetSortModeForCategory(
            preferences = LibraryPreferences(InMemoryPreferenceStore()),
            categoryRepository = FakeCategoryRepository(),
        )
    }

    private fun fakeGetAnimeCategories(): GetAnimeCategories {
        return GetAnimeCategories(FakeCategoryRepository())
    }

    private fun fakeSetAnimeCategories(): SetAnimeCategories {
        return SetAnimeCategories(FakeAnimeRepository(emptyList()))
    }

    private class FakeProfileAwareStore : ProfileAwareStore {
        override val currentProfileId: Long = 1L
        override val currentProfileIdFlow: Flow<Long> = flowOf(1L)
        override val activeProfileId: Long = 1L
        override val activeProfileIdFlow: Flow<Long> = flowOf(1L)
        override fun setCurrentProfileId(profileId: Long) = Unit
        override fun basePreferenceStore() = InMemoryPreferenceStore()
        override fun appStateStore() = InMemoryPreferenceStore()
        override fun privateStore() = InMemoryPreferenceStore()
        override fun profileStore() = InMemoryPreferenceStore()
        override fun profileStore(profileId: Long) = InMemoryPreferenceStore()
        override fun appStateStore(profileId: Long) = InMemoryPreferenceStore()
        override fun privateStore(profileId: Long) = InMemoryPreferenceStore()
        override fun sourcePreferenceKey(sourceId: Long, profileId: Long): String = "$profileId-$sourceId"
    }
}
