package eu.kanade.tachiyomi.data.backup.restore.restorers

import androidx.paging.PagingSource
import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.anime.model.AnimeHistoryWithRelations
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

class AnimeRestorerTest {

    @Test
    fun `restorePendingMerges rebuilds ordered anime merge group`() = runTest {
        val targetAnime = anime(id = 10L, source = 100L, url = "/target", title = "Target")
        val fixture = createFixture(existingAnime = listOf(targetAnime))

        fixture.restorer.restore(
            backupAnime(
                source = 201L,
                url = "/member-b",
                title = "Member B",
                mergeTargetSource = targetAnime.source,
                mergeTargetUrl = targetAnime.url,
                mergePosition = 2,
            ),
            backupCategories = emptyList(),
        )
        fixture.restorer.restore(
            backupAnime(
                source = 200L,
                url = "/member-a",
                title = "Member A",
                mergeTargetSource = targetAnime.source,
                mergeTargetUrl = targetAnime.url,
                mergePosition = 1,
            ),
            backupCategories = emptyList(),
        )

        fixture.restorer.restorePendingMerges()

        val memberA = fixture.animeRepository.requireAnime(url = "/member-a", sourceId = 200L)
        val memberB = fixture.animeRepository.requireAnime(url = "/member-b", sourceId = 201L)

        fixture.mergedAnimeRepository.upsertCalls shouldBe listOf(
            targetAnime.id to listOf(targetAnime.id, memberA.id, memberB.id),
        )
    }

    @Test
    fun `restorePendingMerges skips merge when target anime is missing`() = runTest {
        val fixture = createFixture()

        fixture.restorer.restore(
            backupAnime(
                source = 200L,
                url = "/member",
                title = "Member",
                mergeTargetSource = 100L,
                mergeTargetUrl = "/target",
                mergePosition = 1,
            ),
            backupCategories = emptyList(),
        )

        fixture.restorer.restorePendingMerges()

        fixture.mergedAnimeRepository.upsertCalls shouldBe emptyList()
    }

    private fun createFixture(existingAnime: List<AnimeTitle> = emptyList()): TestFixture {
        val animeRepository = FakeAnimeRepository(existingAnime)
        val mergedAnimeRepository = RecordingMergedAnimeRepository()

        val restorer = AnimeRestorer(
            handler = ImmediateDatabaseHandler(),
            profileProvider = FakeActiveProfileProvider(),
            getAnimeCategories = GetAnimeCategories(EmptyCategoryRepository()),
            animeRepository = animeRepository,
            updateMergedAnime = UpdateMergedAnime(mergedAnimeRepository),
            animeEpisodeRepository = EmptyAnimeEpisodeRepository(),
            animeHistoryRepository = EmptyAnimeHistoryRepository(),
            animePlaybackPreferencesRepository = EmptyAnimePlaybackPreferencesRepository(),
            animePlaybackStateRepository = EmptyAnimePlaybackStateRepository(),
        )

        return TestFixture(restorer, animeRepository, mergedAnimeRepository)
    }

    private fun anime(id: Long, source: Long, url: String, title: String): AnimeTitle {
        return AnimeTitle.create().copy(
            id = id,
            source = source,
            url = url,
            title = title,
            favorite = true,
            initialized = true,
        )
    }

    private fun backupAnime(
        source: Long,
        url: String,
        title: String,
        mergeTargetSource: Long? = null,
        mergeTargetUrl: String? = null,
        mergePosition: Int? = null,
    ): BackupAnime {
        return BackupAnime(
            source = source,
            url = url,
            title = title,
            mergeTargetSource = mergeTargetSource,
            mergeTargetUrl = mergeTargetUrl,
            mergePosition = mergePosition,
        )
    }

    private data class TestFixture(
        val restorer: AnimeRestorer,
        val animeRepository: FakeAnimeRepository,
        val mergedAnimeRepository: RecordingMergedAnimeRepository,
    )

    private class FakeActiveProfileProvider : ActiveProfileProvider {
        override val activeProfileId: Long = 1L
        override val activeProfileIdFlow: Flow<Long> = flowOf(activeProfileId)
    }

    private class ImmediateDatabaseHandler : DatabaseHandler {
        private val database = mockk<Database>(relaxed = true)

        override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
            return block(database)
        }

        override suspend fun <T : Any> awaitList(
            inTransaction: Boolean,
            block: suspend Database.() -> Query<T>,
        ): List<T> = error("Not used")

        override suspend fun <T : Any> awaitOne(
            inTransaction: Boolean,
            block: suspend Database.() -> Query<T>,
        ): T = error("Not used")

        override suspend fun <T : Any> awaitOneExecutable(
            inTransaction: Boolean,
            block: suspend Database.() -> ExecutableQuery<T>,
        ): T = error("Not used")

        override suspend fun <T : Any> awaitOneOrNull(
            inTransaction: Boolean,
            block: suspend Database.() -> Query<T>,
        ): T? = error("Not used")

        override suspend fun <T : Any> awaitOneOrNullExecutable(
            inTransaction: Boolean,
            block: suspend Database.() -> ExecutableQuery<T>,
        ): T? = error("Not used")

        override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> = error("Not used")

        override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T> = error("Not used")

        override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?> = error("Not used")

        override fun <T : Any> subscribeToPagingSource(
            countQuery: Database.() -> Query<Long>,
            queryProvider: Database.(Long, Long) -> Query<T>,
        ): PagingSource<Long, T> = error("Not used")
    }

    private class FakeAnimeRepository(
        anime: List<AnimeTitle>,
    ) : AnimeRepository {
        private val animeById = anime.associateBy(AnimeTitle::id).toMutableMap()
        private val animeByUrlAndSource = anime.associateBy { it.source to it.url }.toMutableMap()
        private var nextId = (anime.maxOfOrNull(AnimeTitle::id) ?: 0L) + 1L

        fun requireAnime(url: String, sourceId: Long): AnimeTitle {
            return requireNotNull(animeByUrlAndSource[sourceId to url])
        }

        override suspend fun getAnimeById(id: Long): AnimeTitle = animeById.getValue(id)

        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = flowOf(animeById.getValue(id))

        override suspend fun getAnimeByUrlAndSourceId(url: String, sourceId: Long): AnimeTitle? {
            return animeByUrlAndSource[sourceId to url]
        }

        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> {
            return flowOf(animeByUrlAndSource[sourceId to url])
        }

        override suspend fun getFavorites(): List<AnimeTitle> = animeById.values.filter(AnimeTitle::favorite)

        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(
            animeById.values.filter(AnimeTitle::favorite),
        )

        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = animeById.values.toList()

        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean {
            val current = animeById[animeId] ?: return false
            val updated = current.copy(displayName = displayName)
            store(updated, previous = current)
            return true
        }

        override suspend fun update(update: AnimeTitleUpdate): Boolean {
            val current = animeById[update.id] ?: return false
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
            store(updated, previous = current)
            return true
        }

        override suspend fun updateAll(animeUpdates: List<AnimeTitleUpdate>): Boolean {
            animeUpdates.forEach { update(it) }
            return true
        }

        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> {
            return animes.map { anime ->
                val restored = anime.copy(id = nextId++)
                store(restored)
                restored
            }
        }

        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) = Unit

        private fun store(anime: AnimeTitle, previous: AnimeTitle? = null) {
            previous?.let { animeByUrlAndSource.remove(it.source to it.url) }
            animeById[anime.id] = anime
            animeByUrlAndSource[anime.source to anime.url] = anime
        }
    }

    private class EmptyCategoryRepository : CategoryRepository {
        override suspend fun get(id: Long): Category? = null

        override suspend fun getAll(): List<Category> = emptyList()

        override fun getAllAsFlow(): Flow<List<Category>> = flowOf(emptyList())

        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> = emptyList()

        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> = flowOf(emptyList())

        override suspend fun getCategoriesByAnimeId(animeId: Long): List<Category> = emptyList()

        override fun getCategoriesByAnimeIdAsFlow(animeId: Long): Flow<List<Category>> = flowOf(emptyList())

        override suspend fun getAnimeCategoryIds(animeIds: List<Long>): Map<Long, List<Long>> = emptyMap()

        override suspend fun insert(category: Category) = Unit

        override suspend fun updatePartial(update: CategoryUpdate) = Unit

        override suspend fun updatePartial(updates: List<CategoryUpdate>) = Unit

        override suspend fun updateAllFlags(flags: Long?) = Unit

        override suspend fun delete(categoryId: Long) = Unit
    }

    private class EmptyAnimeEpisodeRepository : AnimeEpisodeRepository {
        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> = episodes

        override suspend fun update(episodeUpdate: AnimeEpisodeUpdate) = Unit

        override suspend fun updateAll(episodeUpdates: List<AnimeEpisodeUpdate>) = Unit

        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = Unit

        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> = emptyList()

        override fun getEpisodesByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeEpisode>> = flowOf(emptyList())

        override fun getEpisodesByAnimeIdsAsFlow(animeIds: List<Long>): Flow<List<AnimeEpisode>> = flowOf(emptyList())

        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = null

        override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): AnimeEpisode? = null
    }

    private class EmptyAnimeHistoryRepository : AnimeHistoryRepository {
        override fun getHistory(query: String): Flow<List<AnimeHistoryWithRelations>> = flowOf(emptyList())

        override suspend fun getLastHistory(): AnimeHistoryWithRelations? = null

        override fun getLastHistoryAsFlow(): Flow<AnimeHistoryWithRelations?> = flowOf(null)

        override suspend fun getTotalWatchedDuration(): Long = 0L

        override suspend fun getHistoryByAnimeId(animeId: Long): List<AnimeHistory> = emptyList()

        override suspend fun resetHistory(historyId: Long) = Unit

        override suspend fun resetHistoryByAnimeId(animeId: Long) = Unit

        override suspend fun deleteAllHistory(): Boolean = true

        override suspend fun upsertHistory(historyUpdate: AnimeHistoryUpdate) = Unit
    }

    private class EmptyAnimePlaybackPreferencesRepository : AnimePlaybackPreferencesRepository {
        override suspend fun getByAnimeId(animeId: Long): AnimePlaybackPreferences? = null

        override fun getByAnimeIdAsFlow(animeId: Long): Flow<AnimePlaybackPreferences?> = flowOf(null)

        override suspend fun upsert(preferences: AnimePlaybackPreferences) = Unit
    }

    private class EmptyAnimePlaybackStateRepository : AnimePlaybackStateRepository {
        override suspend fun getByEpisodeId(episodeId: Long): AnimePlaybackState? = null

        override fun getByEpisodeIdAsFlow(episodeId: Long): Flow<AnimePlaybackState?> = flowOf(null)

        override fun getByAnimeIdAsFlow(animeId: Long): Flow<List<AnimePlaybackState>> = flowOf(emptyList())

        override suspend fun upsert(state: AnimePlaybackState) = Unit

        override suspend fun upsertAndSyncEpisodeState(state: AnimePlaybackState) = Unit
    }

    private class RecordingMergedAnimeRepository : MergedAnimeRepository {
        val upsertCalls = mutableListOf<Pair<Long, List<Long>>>()

        override suspend fun getAll(): List<AnimeMerge> = emptyList()

        override fun subscribeAll(): Flow<List<AnimeMerge>> = emptyFlow()

        override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> = emptyList()

        override fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> = emptyFlow()

        override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> = emptyList()

        override suspend fun getTargetId(animeId: Long): Long? = null

        override fun subscribeTargetId(animeId: Long): Flow<Long?> = emptyFlow()

        override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) {
            upsertCalls += targetAnimeId to orderedAnimeIds
        }

        override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) = Unit

        override suspend fun deleteGroup(targetAnimeId: Long) = Unit
    }
}
