package eu.kanade.tachiyomi.ui.anime.history

import eu.kanade.domain.anime.model.toMangaCover
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeHistoryScreenModelTest {

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
    fun `groups history rows by watched date`() = runTest(dispatcher) {
        val repository = FakeAnimeHistoryRepository(
            listOf(
                historyWithRelations(1L, Date(1_700_000_000_000L)),
                historyWithRelations(2L, Date(1_700_000_000_000L)),
                historyWithRelations(3L, Date(1_699_000_000_000L)),
            ),
        )

        val model = AnimeHistoryScreenModel(
            getAnime = GetAnime(FakeAnimeRepository(emptyList())),
            getMergedAnime = GetMergedAnime(FakeMergedAnimeRepository()),
            animeHistoryRepository = repository,
        )

        advanceUntilIdle()

        val list = (model.state.value.list ?: emptyList())
        list.shouldHaveSize(5)
        list[0]::class shouldBe AnimeHistoryUiModel.Header::class
        list[1]::class shouldBe AnimeHistoryUiModel.Item::class
        list[2]::class shouldBe AnimeHistoryUiModel.Item::class
        list[3]::class shouldBe AnimeHistoryUiModel.Header::class
        list[4]::class shouldBe AnimeHistoryUiModel.Item::class
    }

    @Test
    fun `remaps history item to visible merged anime metadata`() = runTest(dispatcher) {
        val visibleAnime = AnimeTitle.create().copy(
            id = 10L,
            source = 1L,
            url = "/visible",
            title = "Visible Anime",
            thumbnailUrl = "https://example.com/visible.jpg",
            initialized = true,
        )
        val repository = FakeAnimeHistoryRepository(
            listOf(historyWithRelations(1L, Date(1_700_000_000_000L))),
        )

        val model = AnimeHistoryScreenModel(
            getAnime = GetAnime(FakeAnimeRepository(listOf(visibleAnime))),
            getMergedAnime = GetMergedAnime(
                FakeMergedAnimeRepository(
                    visibleTargetIds = mapOf(1L to visibleAnime.id),
                    groupsByAnimeId = mapOf(
                        1L to listOf(
                            AnimeMerge(targetId = visibleAnime.id, animeId = visibleAnime.id, position = 0L),
                            AnimeMerge(targetId = visibleAnime.id, animeId = 1L, position = 1L),
                        ),
                    ),
                ),
            ),
            animeHistoryRepository = repository,
        )

        advanceUntilIdle()

        val item = model.state.value.list!!.filterIsInstance<AnimeHistoryUiModel.Item>().single()
        item.visibleAnimeId shouldBe visibleAnime.id
        item.visibleTitle shouldBe visibleAnime.displayTitle
        item.visibleCoverData shouldBe visibleAnime.toMangaCover()
    }

    private fun historyWithRelations(id: Long, watchedAt: Date): AnimeHistoryWithRelations {
        return AnimeHistoryWithRelations(
            id = id,
            episodeId = id,
            animeId = id,
            title = "Video $id",
            episodeName = "Episode $id",
            coverData = tachiyomi.domain.manga.model.MangaCover(
                mangaId = id,
                sourceId = 1L,
                isMangaFavorite = false,
                url = "https://example.com/$id.jpg",
                lastModified = 0L,
            ),
            watchedDuration = 0L,
            watchedAt = watchedAt,
        )
    }

    private class FakeAnimeHistoryRepository(
        private val items: List<AnimeHistoryWithRelations>,
    ) : AnimeHistoryRepository {
        override fun getHistory(query: String): Flow<List<AnimeHistoryWithRelations>> = flowOf(items)

        override suspend fun getLastHistory(): AnimeHistoryWithRelations? = items.firstOrNull()

        override fun getLastHistoryAsFlow(): Flow<AnimeHistoryWithRelations?> = flowOf(items.firstOrNull())

        override suspend fun getTotalWatchedDuration(): Long = 0L

        override suspend fun getHistoryByAnimeId(animeId: Long): List<AnimeHistory> = emptyList()

        override suspend fun upsertHistory(historyUpdate: AnimeHistoryUpdate) = Unit

        override suspend fun resetHistory(historyId: Long) = Unit

        override suspend fun resetHistoryByAnimeId(animeId: Long) = Unit

        override suspend fun deleteAllHistory(): Boolean = true
    }

    private class FakeAnimeRepository(
        private val anime: List<AnimeTitle>,
    ) : AnimeRepository {
        private val animeById = anime.associateBy(AnimeTitle::id)

        override suspend fun getAnimeById(id: Long): AnimeTitle = animeById.getValue(id)

        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = flowOf(animeById.getValue(id))

        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? = null

        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> = flowOf(null)

        override suspend fun getFavorites(): List<AnimeTitle> = anime

        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(anime)

        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = anime

        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean = true

        override suspend fun update(update: tachiyomi.domain.anime.model.AnimeTitleUpdate): Boolean = true

        override suspend fun updateAll(
            animeUpdates: List<tachiyomi.domain.anime.model.AnimeTitleUpdate>,
        ): Boolean = true

        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = animes

        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) = Unit
    }

    private class FakeMergedAnimeRepository(
        private val visibleTargetIds: Map<Long, Long> = emptyMap(),
        private val groupsByAnimeId: Map<Long, List<AnimeMerge>> = emptyMap(),
    ) : MergedAnimeRepository {
        override suspend fun getAll(): List<AnimeMerge> = groupsByAnimeId.values.flatten()

        override fun subscribeAll(): Flow<List<AnimeMerge>> = flowOf(groupsByAnimeId.values.flatten())

        override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> = groupsByAnimeId[animeId].orEmpty()

        override fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> {
            return flowOf(groupsByAnimeId[animeId].orEmpty())
        }

        override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> {
            return groupsByAnimeId.values.flatten().filter { it.targetId == targetAnimeId }
        }

        override suspend fun getTargetId(animeId: Long): Long? = visibleTargetIds[animeId]

        override fun subscribeTargetId(animeId: Long): Flow<Long?> = flowOf(visibleTargetIds[animeId])

        override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) = Unit

        override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) = Unit

        override suspend fun deleteGroup(targetAnimeId: Long) = Unit
    }
}
