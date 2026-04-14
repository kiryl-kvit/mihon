package eu.kanade.tachiyomi.ui.anime.updates

import android.app.Application
import eu.kanade.presentation.updates.UpdatesUiModel
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.anime.interactor.GetAnimeUpdates
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeUpdatesRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.HiddenAnimeSourceIds
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeUpdatesScreenModelTest {

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
    fun `groups updates by fetch date`() = runTest(dispatcher) {
        val model = AnimeUpdatesScreenModel(
            getAnimeUpdates = GetAnimeUpdates(
                repository = FakeAnimeUpdatesRepository(
                    listOf(
                        update(1L, 1_700_000_000_000L),
                        update(2L, 1_700_000_000_000L),
                        update(3L, 1_699_000_000_000L),
                    ),
                ),
                hiddenAnimeSourceIds = FakeHiddenAnimeSourceIds(),
            ),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(emptyList()),
            animePlaybackStateRepository = FakeAnimePlaybackStateRepository(),
            updatesPreferences = UpdatesPreferences(InMemoryPreferenceStore()),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            application = mockk<Application>(relaxed = true),
        )

        eventually(2.seconds) {
            val uiModels = model.state.value.getUiModel()
            uiModels.shouldHaveSize(5)
            uiModels[0]::class shouldBe UpdatesUiModel.Header::class
            uiModels[1]::class shouldBe UpdatesUiModel.Item::class
            uiModels[2]::class shouldBe UpdatesUiModel.Item::class
            uiModels[3]::class shouldBe UpdatesUiModel.Header::class
            uiModels[4]::class shouldBe UpdatesUiModel.Item::class
        }
    }

    private fun update(id: Long, dateFetch: Long): AnimeUpdatesWithRelations {
        return AnimeUpdatesWithRelations(
            episodeId = id,
            animeId = id,
            episodeName = "Episode $id",
            episodeUrl = "/episode/$id",
            animeTitle = "Video $id",
            coverData = tachiyomi.domain.manga.model.MangaCover(
                mangaId = id,
                sourceId = 1L,
                isMangaFavorite = false,
                url = "https://example.com/$id.jpg",
                lastModified = 0L,
            ),
            dateFetch = dateFetch,
            watched = false,
            completed = false,
            sourceId = 1L,
        )
    }

    private class FakeAnimeUpdatesRepository(
        private val items: List<AnimeUpdatesWithRelations>,
    ) : AnimeUpdatesRepository {
        override suspend fun awaitWithWatched(watched: Boolean, after: Long, limit: Long): List<AnimeUpdatesWithRelations> {
            return items
        }

        override fun subscribeAll(
            after: Long,
            limit: Long,
            unread: Boolean?,
            started: Boolean?,
        ): Flow<List<AnimeUpdatesWithRelations>> {
            return flowOf(items)
        }

        override fun subscribeWithWatched(watched: Boolean, after: Long, limit: Long): Flow<List<AnimeUpdatesWithRelations>> {
            return flowOf(items)
        }
    }

    private class FakeHiddenAnimeSourceIds : HiddenAnimeSourceIds {
        override fun get(): Set<Long> = emptySet()

        override fun subscribe(): Flow<Set<Long>> = flowOf(emptySet())
    }

    private class FakeAnimeEpisodeRepository(
        private val episodes: List<AnimeEpisode>,
    ) : AnimeEpisodeRepository {
        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> = episodes

        override suspend fun update(episodeUpdate: AnimeEpisodeUpdate) = Unit

        override suspend fun updateAll(episodeUpdates: List<AnimeEpisodeUpdate>) = Unit

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = Unit

        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> = episodes.filter { it.animeId == animeId }

        override fun getEpisodesByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeEpisode>> = flowOf(episodes.filter { it.animeId == animeId })

        override fun getEpisodesByAnimeIdsAsFlow(animeIds: List<Long>): Flow<List<AnimeEpisode>> = flowOf(episodes.filter { it.animeId in animeIds })

        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = episodes.firstOrNull { it.id == id }

        override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? {
            return episodes.firstOrNull { it.url == url && it.animeId == animeId }
        }
    }

    private class FakeAnimePlaybackStateRepository : AnimePlaybackStateRepository {
        override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? = null

        override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<AnimePlaybackState?> = flowOf(null)

        override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<AnimePlaybackState>> = flowOf(emptyList())

        override suspend fun upsert(state: AnimePlaybackState) = Unit

        override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) = Unit
    }
}
