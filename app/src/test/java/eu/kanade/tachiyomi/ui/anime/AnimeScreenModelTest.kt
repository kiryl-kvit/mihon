@file:Suppress("ktlint:standard:max-line-length")

package eu.kanade.tachiyomi.ui.anime

import android.app.Application
import eu.kanade.tachiyomi.source.AnimeScheduleSource
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SAnimeScheduleEpisode
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.service.DuplicatePreferences
import tachiyomi.domain.source.service.AnimeSourceManager
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeScreenModelTest {

    private lateinit var dispatcher: TestDispatcher
    private val createdModels = mutableListOf<AnimeScreenModel>()

    @BeforeEach
    fun setup() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        createdModels.asReversed().forEach(AnimeScreenModel::onDispose)
        createdModels.clear()
        dispatcher.scheduler.advanceUntilIdle()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `long press enters selection and second long press selects range`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
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
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
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
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val firstEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = anime.id,
            watched = false,
            completed = false,
            sourceOrder = 0L,
        )
        val secondEpisode = AnimeEpisode.create().copy(
            id = 11L,
            animeId = anime.id,
            watched = true,
            completed = false,
            sourceOrder = 1L,
        )
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode))
        val playbackRepository = FakeAnimePlaybackStateRepository(
            mapOf(
                anime.id to listOf(
                    AnimePlaybackState(
                        episodeId = firstEpisode.id,
                        positionMs = 5_000L,
                        durationMs = 10_000L,
                        completed = false,
                        lastWatchedAt = 100L,
                    ),
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
            episodeRepository.updateAllCalls.single().sortedBy(AnimeEpisodeUpdate::id) shouldBe listOf(
                AnimeEpisodeUpdate(id = firstEpisode.id, watched = true, completed = true),
                AnimeEpisodeUpdate(id = secondEpisode.id, watched = true, completed = true),
            ).sortedBy(AnimeEpisodeUpdate::id)
            playbackRepository.upserts.sortedBy(AnimePlaybackState::episodeId) shouldBe listOf(
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
            ).sortedBy(AnimePlaybackState::episodeId)
            val state = model.state.value as AnimeScreenModel.State.Success
            state.selection shouldBe emptySet<Long>()
            state.isSelectionMode shouldBe false
        }
    }

    @Test
    fun `mark unwatched resets selected episodes and playback states`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val firstEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = anime.id,
            watched = true,
            completed = true,
            sourceOrder = 0L,
        )
        val secondEpisode = AnimeEpisode.create().copy(
            id = 11L,
            animeId = anime.id,
            watched = true,
            completed = false,
            sourceOrder = 1L,
        )
        val episodeRepository = FakeAnimeEpisodeRepository(listOf(firstEpisode, secondEpisode))
        val playbackRepository = FakeAnimePlaybackStateRepository(
            mapOf(
                anime.id to listOf(
                    AnimePlaybackState(
                        episodeId = firstEpisode.id,
                        positionMs = 10_000L,
                        durationMs = 10_000L,
                        completed = true,
                        lastWatchedAt = 100L,
                    ),
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
            episodeRepository.updateAllCalls.single().sortedBy(AnimeEpisodeUpdate::id) shouldBe listOf(
                AnimeEpisodeUpdate(id = firstEpisode.id, watched = false, completed = false),
                AnimeEpisodeUpdate(id = secondEpisode.id, watched = false, completed = false),
            ).sortedBy(AnimeEpisodeUpdate::id)
            playbackRepository.upserts.sortedBy(AnimePlaybackState::episodeId) shouldBe listOf(
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
            ).sortedBy(AnimePlaybackState::episodeId)
            val state = model.state.value as AnimeScreenModel.State.Success
            state.selection shouldBe emptySet<Long>()
            state.isSelectionMode shouldBe false
        }
    }

    @Test
    fun `schedule preloads and shows upcoming summary when entries exist`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val scheduleSource = FakeScheduleAnimeSource(
            scheduleEntries = listOf(
                SAnimeScheduleEpisode(
                    seasonNumber = 1,
                    episodeNumber = 12f,
                    title = "Finale",
                    airDate = System.currentTimeMillis() + 86_400_000L,
                    isAvailable = false,
                ),
            ),
        )

        val model = createModel(
            anime = anime,
            episodes = emptyList(),
            animeSourceManager = FakeAnimeSourceManager(scheduleSource),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.showScheduleButton shouldBe true
            state.scheduleSummary shouldBe AnimeScreenModel.ScheduleSummary.Upcoming(1)
            state.schedule.shouldBeInstanceOf<AnimeScreenModel.ScheduleState.Success>()
            scheduleSource.scheduleRequests shouldBe 1
        }
    }

    @Test
    fun `empty preloaded schedule hides button and cannot open dialog`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val scheduleSource = FakeScheduleAnimeSource(scheduleEntries = emptyList())

        val model = createModel(
            anime = anime,
            episodes = emptyList(),
            animeSourceManager = FakeAnimeSourceManager(scheduleSource),
        )

        advanceUntilIdle()
        model.showScheduleDialog()
        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.showScheduleButton shouldBe false
            state.schedule shouldBe AnimeScreenModel.ScheduleState.Empty
            state.dialog shouldBe null
            scheduleSource.scheduleRequests shouldBe 1
        }
    }

    @Test
    fun `schedule load failure keeps button visible for retry`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val scheduleSource = FakeScheduleAnimeSource(error = IllegalStateException("boom"))

        val model = createModel(
            anime = anime,
            episodes = emptyList(),
            animeSourceManager = FakeAnimeSourceManager(scheduleSource),
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.showScheduleButton shouldBe true
            state.scheduleSummary shouldBe AnimeScreenModel.ScheduleSummary.Error
            state.schedule.shouldBeInstanceOf<AnimeScreenModel.ScheduleState.Error>()
            scheduleSource.scheduleRequests shouldBe 1
        }
    }

    @Test
    fun `merged schedule preloads all members in merge order`() = runTest(dispatcher) {
        val target = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Target",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val member = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Member",
            favorite = true,
            initialized = true,
            url = "/anime/2",
        )
        val targetScheduleSource = FakeScheduleAnimeSource(
            id = target.source,
            scheduleEntries = listOf(
                SAnimeScheduleEpisode(
                    seasonNumber = 1,
                    episodeNumber = 12f,
                    title = "Target Finale",
                    airDate = System.currentTimeMillis() + 172_800_000L,
                    isAvailable = false,
                ),
            ),
        )
        val memberScheduleSource = FakeScheduleAnimeSource(
            id = member.source,
            scheduleEntries = listOf(
                SAnimeScheduleEpisode(
                    seasonNumber = 1,
                    episodeNumber = 1f,
                    title = "Member Premiere",
                    airDate = System.currentTimeMillis() + 86_400_000L,
                    isAvailable = false,
                ),
            ),
        )
        val mergedRepository = FakeMergedAnimeRepository(
            listOf(
                AnimeMerge(targetId = target.id, animeId = member.id, position = 0L),
                AnimeMerge(targetId = target.id, animeId = target.id, position = 1L),
            ),
        )

        val model = createModel(
            anime = target,
            episodes = emptyList(),
            animeRepository = FakeAnimeRepository(listOf(target, member)),
            animeSourceManager = FakeAnimeSourceManager(targetScheduleSource, memberScheduleSource),
            mergedRepository = mergedRepository,
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.showScheduleButton shouldBe true
            state.scheduleSummary shouldBe AnimeScreenModel.ScheduleSummary.Upcoming(2)

            val schedule = state.schedule.shouldBeInstanceOf<AnimeScreenModel.ScheduleState.Success>()
            schedule.entries.map { it.memberId }.distinct() shouldContainExactly listOf(member.id, target.id)
            schedule.entries.map { it.memberId to it.memberOrder }.distinct() shouldContainExactly listOf(
                member.id to 0,
                target.id to 1,
            )
            schedule.entries.map { it.memberTitle }.distinct() shouldContainExactly listOf("Member", "Target")
            targetScheduleSource.scheduleRequests shouldBe 1
            memberScheduleSource.scheduleRequests shouldBe 1
        }
    }

    @Test
    fun `merged schedule uses child support when root source has none`() = runTest(dispatcher) {
        val target = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Target",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val member = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Member",
            favorite = true,
            initialized = true,
            url = "/anime/2",
        )
        val memberScheduleSource = FakeScheduleAnimeSource(
            id = member.source,
            scheduleEntries = listOf(
                SAnimeScheduleEpisode(
                    seasonNumber = 2,
                    episodeNumber = 3f,
                    title = "Child Schedule",
                    airDate = System.currentTimeMillis() + 86_400_000L,
                    isAvailable = false,
                ),
            ),
        )
        val mergedRepository = FakeMergedAnimeRepository(
            listOf(
                AnimeMerge(targetId = target.id, animeId = target.id, position = 0L),
                AnimeMerge(targetId = target.id, animeId = member.id, position = 1L),
            ),
        )

        val model = createModel(
            anime = target,
            episodes = emptyList(),
            animeRepository = FakeAnimeRepository(listOf(target, member)),
            animeSourceManager = FakeAnimeSourceManager(FakeAnimeSource(target.source), memberScheduleSource),
            mergedRepository = mergedRepository,
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.showScheduleButton shouldBe true
            state.scheduleSummary shouldBe AnimeScreenModel.ScheduleSummary.Upcoming(1)

            val schedule = state.schedule.shouldBeInstanceOf<AnimeScreenModel.ScheduleState.Success>()
            schedule.entries.map { it.memberId } shouldContainExactly listOf(member.id)
            memberScheduleSource.scheduleRequests shouldBe 1
        }
    }

    @Test
    fun `merged schedule keeps successful members when one member fails`() = runTest(dispatcher) {
        val target = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Target",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val member = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Member",
            favorite = true,
            initialized = true,
            url = "/anime/2",
        )
        val targetScheduleSource = FakeScheduleAnimeSource(
            id = target.source,
            scheduleEntries = listOf(
                SAnimeScheduleEpisode(
                    seasonNumber = 1,
                    episodeNumber = 10f,
                    title = "Target Success",
                    airDate = System.currentTimeMillis() + 86_400_000L,
                    isAvailable = false,
                ),
            ),
        )
        val memberScheduleSource = FakeScheduleAnimeSource(
            id = member.source,
            error = IllegalStateException("boom"),
        )
        val mergedRepository = FakeMergedAnimeRepository(
            listOf(
                AnimeMerge(targetId = target.id, animeId = target.id, position = 0L),
                AnimeMerge(targetId = target.id, animeId = member.id, position = 1L),
            ),
        )

        val model = createModel(
            anime = target,
            episodes = emptyList(),
            animeRepository = FakeAnimeRepository(listOf(target, member)),
            animeSourceManager = FakeAnimeSourceManager(targetScheduleSource, memberScheduleSource),
            mergedRepository = mergedRepository,
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.showScheduleButton shouldBe true
            state.scheduleSummary shouldBe AnimeScreenModel.ScheduleSummary.Upcoming(1)

            val schedule = state.schedule.shouldBeInstanceOf<AnimeScreenModel.ScheduleState.Success>()
            schedule.entries.map { it.memberId } shouldContainExactly listOf(target.id)
            targetScheduleSource.scheduleRequests shouldBe 1
            memberScheduleSource.scheduleRequests shouldBe 1
        }
    }

    @Test
    fun `sort by episode number reorders episodes ascending`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
            episodeFlags = AnimeTitle.EPISODE_SORT_ASC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )
        val episodes = listOf(
            AnimeEpisode.create().copy(
                id = 10L,
                animeId = anime.id,
                sourceOrder = 0L,
                episodeNumber = 3.0,
                name = "Episode 3",
            ),
            AnimeEpisode.create().copy(
                id = 11L,
                animeId = anime.id,
                sourceOrder = 1L,
                episodeNumber = 1.0,
                name = "Episode 1",
            ),
            AnimeEpisode.create().copy(
                id = 12L,
                animeId = anime.id,
                sourceOrder = 2L,
                episodeNumber = 2.0,
                name = "Episode 2",
            ),
        )

        val model = createModel(anime = anime, episodes = episodes)

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.episodes.map { it.id } shouldContainExactly listOf(11L, 12L, 10L)
        }
    }

    @Test
    fun `started filter keeps only started episodes`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
            episodeFlags = AnimeTitle.EPISODE_SHOW_STARTED,
        )
        val startedEpisode = AnimeEpisode.create().copy(
            id = 10L,
            animeId = anime.id,
            watched = true,
            sourceOrder = 0L,
            name = "Started",
        )
        val newEpisode = AnimeEpisode.create().copy(
            id = 11L,
            animeId = anime.id,
            watched = false,
            completed = false,
            sourceOrder = 1L,
            name = "New",
        )

        val model = createModel(anime = anime, episodes = listOf(startedEpisode, newEpisode))

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.filterActive shouldBe true
            state.episodes.map { it.id } shouldContainExactly listOf(10L)
        }
    }

    @Test
    fun `merged detail start picks first episode in reading order`() = runTest(dispatcher) {
        val target = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Target",
            favorite = true,
            initialized = true,
            url = "/anime/1",
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )
        val member = AnimeTitle.create().copy(
            id = 2L,
            source = 100L,
            title = "Member",
            favorite = true,
            initialized = true,
            url = "/anime/2",
        )
        val episodes = listOf(
            AnimeEpisode.create().copy(
                id = 10L,
                animeId = target.id,
                episodeNumber = 1.0,
                sourceOrder = 1L,
                completed = false,
                name = "Target 1",
            ),
            AnimeEpisode.create().copy(
                id = 20L,
                animeId = member.id,
                episodeNumber = 1.0,
                sourceOrder = 1L,
                completed = false,
                name = "Member 1",
            ),
        )
        val mergedRepository = FakeMergedAnimeRepository(
            listOf(
                AnimeMerge(targetId = target.id, animeId = target.id, position = 0L),
                AnimeMerge(targetId = target.id, animeId = member.id, position = 1L),
            ),
        )
        val animeRepository = FakeAnimeRepository(listOf(target, member))

        val model = createModel(
            anime = target,
            episodes = episodes,
            animeRepository = animeRepository,
            mergedRepository = mergedRepository,
        )

        advanceUntilIdle()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            state.episodes.map { it.id } shouldContainExactly listOf(10L, 20L)
            state.primaryEpisodeId shouldBe 20L
            state.primaryEpisode?.id shouldBe 20L
        }
    }

    @Test
    fun `set sorting updates repository episode flags`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val animeRepository = FakeAnimeRepository(listOf(anime))
        val model = createModel(anime = anime, episodes = emptyList(), animeRepository = animeRepository)

        advanceUntilIdle()
        awaitSuccess(model)
        model.setSorting(AnimeTitle.EPISODE_SORTING_ALPHABET)

        eventually(2.seconds) {
            animeRepository.updates.last() shouldBe AnimeTitleUpdate(
                id = anime.id,
                episodeFlags = AnimeTitle.EPISODE_SORTING_ALPHABET or AnimeTitle.EPISODE_SORT_ASC,
            )
        }
    }

    @Test
    fun `reset to default uses library preference episode flags`() = runTest(dispatcher) {
        val preferences = testLibraryPreferences().apply {
            filterEpisodeByUnwatched.set(AnimeTitle.EPISODE_SHOW_UNWATCHED)
            filterEpisodeByStarted.set(AnimeTitle.SHOW_ALL)
            sortEpisodeBySourceOrNumber.set(AnimeTitle.EPISODE_SORTING_ALPHABET)
            sortEpisodeByAscendingOrDescending.set(AnimeTitle.EPISODE_SORT_ASC)
            displayEpisodeByNameOrNumber.set(AnimeTitle.EPISODE_DISPLAY_NUMBER)
        }
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            favorite = true,
            initialized = true,
            url = "/anime/1",
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC,
        )
        val animeRepository = FakeAnimeRepository(listOf(anime))
        val model = createModel(
            anime = anime,
            episodes = emptyList(),
            animeRepository = animeRepository,
            libraryPreferences = preferences,
        )

        advanceUntilIdle()
        awaitSuccess(model)
        model.resetToDefaultSettings()

        eventually(2.seconds) {
            animeRepository.updates.last() shouldBe AnimeTitleUpdate(
                id = anime.id,
                episodeFlags = AnimeTitle.EPISODE_SHOW_UNWATCHED or
                    AnimeTitle.EPISODE_SORTING_ALPHABET or
                    AnimeTitle.EPISODE_SORT_ASC or
                    AnimeTitle.EPISODE_DISPLAY_NUMBER,
            )
        }
    }

    @Test
    fun `reset display name updates repository with null`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Anime",
            displayName = "Custom",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val animeRepository = FakeAnimeRepository(listOf(anime))
        val model = createModel(anime = anime, episodes = emptyList(), animeRepository = animeRepository)

        advanceUntilIdle()
        awaitSuccess(model)
        model.updateDisplayName("")

        eventually(2.seconds) {
            animeRepository.displayNameUpdates.single() shouldBe (anime.id to null)
        }
    }

    @Test
    fun `edit display name dialog is seeded from visible title`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Source Title",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val model = createModel(anime = anime, episodes = emptyList())

        advanceUntilIdle()
        awaitSuccess(model)
        model.showEditDisplayNameDialog()

        eventually(2.seconds) {
            val state = model.state.value.shouldBeInstanceOf<AnimeScreenModel.State.Success>()
            val dialog = state.dialog.shouldBeInstanceOf<AnimeScreenModel.Dialog.EditDisplayName>()
            dialog.initialValue shouldBe anime.title
        }
    }

    @Test
    fun `unchanged source title does not store redundant display name`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Source Title",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val animeRepository = FakeAnimeRepository(listOf(anime))
        val model = createModel(anime = anime, episodes = emptyList(), animeRepository = animeRepository)

        advanceUntilIdle()
        awaitSuccess(model)
        model.updateDisplayName(anime.title)

        eventually(2.seconds) {
            animeRepository.displayNameUpdates.single() shouldBe (anime.id to null)
            animeRepository.getAnimeById(anime.id).displayName.shouldBeNull()
        }
    }

    @Test
    fun `show merge target picker seeds query from visible title and prefilters targets`() = runTest(dispatcher) {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            source = 99L,
            title = "Source Title",
            displayName = "Custom Title",
            favorite = true,
            initialized = true,
            url = "/anime/1",
        )
        val matchingTarget = AnimeTitle.create().copy(
            id = 2L,
            source = 99L,
            title = "Custom Title Season 2",
            favorite = true,
            initialized = true,
            url = "/anime/2",
        )
        val otherTarget = AnimeTitle.create().copy(
            id = 3L,
            source = 99L,
            title = "Different Show",
            favorite = true,
            initialized = true,
            url = "/anime/3",
        )

        val model = createModel(
            anime = anime,
            episodes = emptyList(),
            animeRepository = FakeAnimeRepository(listOf(anime, matchingTarget, otherTarget)),
        )

        advanceUntilIdle()
        awaitSuccess(model)
        model.showMergeTargetPicker()

        eventually(2.seconds) {
            val state = model.state.value as AnimeScreenModel.State.Success
            val dialog = state.dialog as AnimeScreenModel.Dialog.SelectMergeTarget
            dialog.query shouldBe "Custom Title"
            dialog.visibleTargets.map { it.id } shouldContainExactly listOf(2L)
        }
    }

    private fun createModel(
        anime: AnimeTitle,
        episodes: List<AnimeEpisode>,
        animeRepository: FakeAnimeRepository = FakeAnimeRepository(listOf(anime)),
        episodeRepository: AnimeEpisodeRepository = FakeAnimeEpisodeRepository(episodes),
        playbackRepository: AnimePlaybackStateRepository = FakeAnimePlaybackStateRepository(emptyMap()),
        animeSourceManager: AnimeSourceManager = FakeAnimeSourceManager(),
        libraryPreferences: LibraryPreferences = testLibraryPreferences(),
        mergedRepository: MergedAnimeRepository = FakeMergedAnimeRepository(emptyList()),
    ): AnimeScreenModel {
        val setAnimeEpisodeFlags = SetAnimeEpisodeFlags(animeRepository)
        val getAnimeWithEpisodes = GetAnimeWithEpisodes(animeRepository, episodeRepository, mergedRepository)
        val getDuplicateLibraryAnime = GetDuplicateLibraryAnime(
            animeRepository = animeRepository,
            animeEpisodeRepository = episodeRepository,
            mergedAnimeRepository = mergedRepository,
            duplicatePreferences = DuplicatePreferences(InMemoryPreferenceStore()),
        )
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
            getAnimeWithEpisodes = getAnimeWithEpisodes,
            getDuplicateLibraryAnime = getDuplicateLibraryAnime,
            getMergedAnime = GetMergedAnime(mergedRepository),
            networkToLocalAnime = NetworkToLocalAnime(animeRepository),
            updateMergedAnime = UpdateMergedAnime(mergedRepository),
            setAnimeEpisodeFlags = setAnimeEpisodeFlags,
            setAnimeDefaultEpisodeFlags = SetAnimeDefaultEpisodeFlags(libraryPreferences, setAnimeEpisodeFlags),
            libraryPreferences = libraryPreferences,
            syncAnimeWithSource = SyncAnimeWithSource(
                animeRepository = animeRepository,
                animeEpisodeRepository = episodeRepository,
                animeSourceManager = animeSourceManager,
            ),
        ).also(createdModels::add)
    }

    private suspend fun awaitSuccess(model: AnimeScreenModel) {
        eventually(2.seconds) {
            (model.state.value is AnimeScreenModel.State.Success) shouldBe true
        }
    }

    private fun testLibraryPreferences(): LibraryPreferences {
        return LibraryPreferences(InMemoryPreferenceStore())
    }

    private class FakeAnimeRepository(
        private val anime: List<AnimeTitle>,
    ) : AnimeRepository {
        val updates = mutableListOf<AnimeTitleUpdate>()
        val displayNameUpdates = mutableListOf<Pair<Long, String?>>()
        private val animeById = anime.associateBy(AnimeTitle::id).toMutableMap()
        private val animeFlows = animeById.mapValues { MutableStateFlow(it.value) }.toMutableMap()

        override suspend fun getAnimeById(id: Long): AnimeTitle = animeById.getValue(id)
        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = animeFlows.getValue(id).asStateFlow()
        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = null
        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> = flowOf(null)
        override suspend fun getFavorites(): List<AnimeTitle> = animeById.values.toList()
        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(animeById.values.toList())
        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = animeById.values.toList()
        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean {
            displayNameUpdates += animeId to displayName
            val current = animeById.getValue(animeId)
            val updated = current.copy(displayName = displayName)
            animeById[animeId] = updated
            animeFlows.getValue(animeId).value = updated
            return true
        }
        override suspend fun update(update: AnimeTitleUpdate): Boolean {
            updates += update
            val current = animeById[update.id]
            if (current != null) {
                val updated = current.copy(
                    source = update.source ?: current.source,
                    favorite = update.favorite ?: current.favorite,
                    lastUpdate = update.lastUpdate ?: current.lastUpdate,
                    dateAdded = update.dateAdded ?: current.dateAdded,
                    episodeFlags = update.episodeFlags ?: current.episodeFlags,
                    coverLastModified = update.coverLastModified ?: current.coverLastModified,
                    url = update.url ?: current.url,
                    title = update.title ?: current.title,
                    displayName = update.displayName ?: current.displayName,
                    originalTitle = update.originalTitle ?: current.originalTitle,
                    country = update.country ?: current.country,
                    studio = update.studio ?: current.studio,
                    producer = update.producer ?: current.producer,
                    director = update.director ?: current.director,
                    writer = update.writer ?: current.writer,
                    year = update.year ?: current.year,
                    duration = update.duration ?: current.duration,
                    description = update.description ?: current.description,
                    genre = update.genre ?: current.genre,
                    status = update.status ?: current.status,
                    thumbnailUrl = update.thumbnailUrl ?: current.thumbnailUrl,
                    initialized = update.initialized ?: current.initialized,
                    version = update.version ?: current.version,
                    notes = update.notes ?: current.notes,
                )
                animeById[update.id] = updated
                animeFlows.getValue(update.id).value = updated
            }
            return true
        }
        override suspend fun updateAll(animeUpdates: List<AnimeTitleUpdate>): Boolean {
            updates += animeUpdates
            return true
        }
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
        private val statesByAnimeId: Map<Long, List<AnimePlaybackState>>,
    ) : AnimePlaybackStateRepository {
        val upserts = mutableListOf<AnimePlaybackState>()

        override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? = statesByAnimeId.values.flatten().firstOrNull {
            it.episodeId ==
                episodeId
        }
        override fun getByEpisodeIdAsFlow(
            episodeId: Long,
        ): Flow<AnimePlaybackState?> = flowOf(
            statesByAnimeId.values.flatten().firstOrNull {
                it.episodeId ==
                    episodeId
            },
        )
        override fun getByAnimeIdAsFlow(
            animeId: Long,
        ): Flow<List<AnimePlaybackState>> = flowOf(statesByAnimeId[animeId].orEmpty())
        override suspend fun upsert(state: AnimePlaybackState) {
            upserts += state
        }
        override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) = Unit
    }

    private class FakeAnimeSourceManager : AnimeSourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(emptyList<eu.kanade.tachiyomi.source.AnimeCatalogueSource>())

        private val sources = mutableMapOf<Long, eu.kanade.tachiyomi.source.AnimeSource>()

        constructor()

        constructor(source: eu.kanade.tachiyomi.source.AnimeSource) {
            sources[source.id] = source
        }

        constructor(vararg sources: eu.kanade.tachiyomi.source.AnimeSource) {
            sources.forEach { source ->
                this.sources[source.id] = source
            }
        }

        override fun get(sourceKey: Long): eu.kanade.tachiyomi.source.AnimeSource? =
            sources[sourceKey] ?: FakeAnimeSource(sourceKey)
        override fun getCatalogueSources(): List<eu.kanade.tachiyomi.source.AnimeCatalogueSource> = emptyList()
    }

    private open class FakeAnimeSource(
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

    private class FakeMergedAnimeRepository(
        private val merges: List<AnimeMerge>,
    ) : MergedAnimeRepository {
        override suspend fun getAll(): List<AnimeMerge> = merges
        override fun subscribeAll(): Flow<List<AnimeMerge>> = flowOf(merges)
        override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> {
            val targetId = merges.firstOrNull { it.animeId == animeId }?.targetId ?: return emptyList()
            return merges.filter { it.targetId == targetId }
        }
        override fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> = flowOf(
            run {
                val targetId = merges.firstOrNull { it.animeId == animeId }?.targetId ?: return@run emptyList()
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
        override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) = Unit
        override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) = Unit
        override suspend fun deleteGroup(targetAnimeId: Long) = Unit
    }

    private class FakeScheduleAnimeSource(
        override val id: Long = 99L,
        private val scheduleEntries: List<SAnimeScheduleEpisode> = emptyList(),
        private val error: Throwable? = null,
    ) : FakeAnimeSource(id = id), AnimeScheduleSource {
        var scheduleRequests = 0

        override suspend fun getEpisodeSchedule(anime: SAnime): List<SAnimeScheduleEpisode> {
            scheduleRequests += 1
            error?.let { throw it }
            return scheduleEntries
        }
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
