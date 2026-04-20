package eu.kanade.tachiyomi.ui.updates

import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.MergedMangaRepository
import tachiyomi.domain.source.service.HiddenSourceIds
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import tachiyomi.domain.updates.service.UpdatesPreferences
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesScreenModelTest {

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
        val model = UpdatesScreenModel(
            sourceManager = mockk(relaxed = true),
            downloadManager = fakeDownloadManager(),
            downloadCache = fakeDownloadCache(),
            updateChapter = UpdateChapter(FakeChapterRepository(emptyList())),
            setReadStatus = fakeSetReadStatus(),
            getUpdates = GetUpdates(
                repository = FakeUpdatesRepository(
                    listOf(
                        update(1L, 1_700_000_000_000L),
                        update(2L, 1_700_000_000_000L),
                        update(3L, 1_699_000_000_000L),
                    ),
                ),
                hiddenSourceIds = FakeHiddenSourceIds(),
            ),
            getManga = GetManga(FakeMangaRepository(emptyList())),
            getMergedManga = GetMergedManga(FakeMergedMangaRepository()),
            getChapter = GetChapter(FakeChapterRepository(emptyList())),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            updatesPreferences = UpdatesPreferences(InMemoryPreferenceStore()),
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

    @Test
    fun `preserves child metadata while keeping visible merged manga id`() = runTest(dispatcher) {
        val childManga = Manga.create().copy(
            id = 1L,
            source = 1L,
            url = "/child",
            title = "Child Manga",
            displayName = "Child Display",
            thumbnailUrl = "https://example.com/child.jpg",
            initialized = true,
            favorite = true,
        )
        val visibleManga = Manga.create().copy(
            id = 10L,
            source = 1L,
            url = "/visible",
            title = "Visible Manga",
            thumbnailUrl = "https://example.com/visible.jpg",
            initialized = true,
            favorite = true,
        )
        val model = UpdatesScreenModel(
            sourceManager = mockk(relaxed = true),
            downloadManager = fakeDownloadManager(),
            downloadCache = fakeDownloadCache(),
            updateChapter = UpdateChapter(FakeChapterRepository(emptyList())),
            setReadStatus = fakeSetReadStatus(),
            getUpdates = GetUpdates(
                repository = FakeUpdatesRepository(listOf(update(1L, 1_700_000_000_000L))),
                hiddenSourceIds = FakeHiddenSourceIds(),
            ),
            getManga = GetManga(FakeMangaRepository(listOf(childManga, visibleManga))),
            getMergedManga = GetMergedManga(
                FakeMergedMangaRepository(
                    visibleTargetIds = mapOf(1L to visibleManga.id),
                    groupsByMangaId = mapOf(
                        1L to listOf(
                            MangaMerge(targetId = visibleManga.id, mangaId = visibleManga.id, position = 0L),
                            MangaMerge(targetId = visibleManga.id, mangaId = 1L, position = 1L),
                        ),
                    ),
                ),
            ),
            getChapter = GetChapter(FakeChapterRepository(emptyList())),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            updatesPreferences = UpdatesPreferences(InMemoryPreferenceStore()),
        )

        eventually(2.seconds) {
            val item = model.state.value.items.single()
            item.visibleMangaId shouldBe visibleManga.id
            item.visibleMangaTitle shouldBe childManga.displayTitle
            item.visibleCoverData shouldBe childManga.asMangaCover()
        }
    }

    private fun update(id: Long, dateFetch: Long): UpdatesWithRelations {
        return UpdatesWithRelations(
            mangaId = id,
            mangaTitle = "Manga $id",
            chapterId = id,
            chapterName = "Chapter $id",
            scanlator = null,
            chapterUrl = "/chapter/$id",
            read = false,
            bookmark = false,
            lastPageRead = 0L,
            sourceId = 1L,
            dateFetch = dateFetch,
            coverData = tachiyomi.domain.manga.model.MangaCover(
                mangaId = id,
                sourceId = 1L,
                isMangaFavorite = false,
                url = "https://example.com/$id.jpg",
                lastModified = 0L,
            ),
        )
    }

    private fun fakeDownloadManager(): DownloadManager {
        return mockk(relaxed = true) {
            every { queueState } returns MutableStateFlow(emptyList())
            every { statusFlow() } returns flowOf<Download>()
            every { progressFlow() } returns flowOf<Download>()
            every { getQueuedDownloadOrNull(any()) } returns null
            every { isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns false
        }
    }

    private fun fakeDownloadCache(): DownloadCache {
        return mockk(relaxed = true) {
            every { changes } returns MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        }
    }

    private fun fakeSetReadStatus(): SetReadStatus {
        return mockk(relaxed = true)
    }

    private class FakeUpdatesRepository(
        private val items: List<UpdatesWithRelations>,
    ) : UpdatesRepository {
        override suspend fun awaitWithRead(
            read: Boolean,
            after: Long,
            limit: Long,
        ): List<UpdatesWithRelations> {
            return items
        }

        override fun subscribeAll(
            after: Long,
            limit: Long,
            unread: Boolean?,
            started: Boolean?,
            bookmarked: Boolean?,
            hideExcludedScanlators: Boolean,
        ): Flow<List<UpdatesWithRelations>> {
            return flowOf(items)
        }

        override fun subscribeWithRead(
            read: Boolean,
            after: Long,
            limit: Long,
        ): Flow<List<UpdatesWithRelations>> {
            return flowOf(items)
        }
    }

    private class FakeHiddenSourceIds : HiddenSourceIds {
        override fun get(): Set<Long> = emptySet()

        override fun subscribe(): Flow<Set<Long>> = flowOf(emptySet())
    }

    private class FakeChapterRepository(
        private val chapters: List<Chapter>,
    ) : ChapterRepository {
        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> = chapters

        override suspend fun update(chapterUpdate: ChapterUpdate) = Unit

        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) = Unit

        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) = Unit

        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
            return chapters.filter { it.mangaId == mangaId }
        }

        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()

        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = flowOf(emptyList())

        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()

        override suspend fun getChapterById(id: Long): Chapter? = chapters.firstOrNull { it.id == id }

        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> {
            return flowOf(chapters.filter { it.mangaId == mangaId })
        }

        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
            return chapters.firstOrNull { it.url == url && it.mangaId == mangaId }
        }
    }

    private class FakeMangaRepository(
        private val manga: List<Manga>,
    ) : MangaRepository {
        private val mangaById = manga.associateBy(Manga::id)

        override suspend fun getMangaById(id: Long): Manga = mangaById.getValue(id)

        override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> = flowOf(mangaById.getValue(id))

        override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? = null

        override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> = flowOf(null)

        override suspend fun getFavorites(): List<Manga> = manga

        override suspend fun getNonFavoriteIds(mangaIds: List<Long>): List<Long> = emptyList()

        override suspend fun getFavoritesByProfile(profileId: Long): List<Manga> = manga

        override suspend fun getAllMangaByProfile(profileId: Long): List<Manga> = manga

        override suspend fun getReadMangaNotInLibrary(): List<Manga> = emptyList()

        override suspend fun getReadMangaNotInLibraryByProfile(profileId: Long): List<Manga> = emptyList()

        override suspend fun getLibraryManga(): List<LibraryManga> = emptyList()

        override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> = flowOf(emptyList())

        override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> = flowOf(emptyList())

        override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> = flowOf(emptyList())

        override suspend fun resetViewerFlags(): Boolean = true

        override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) = Unit

        override suspend fun updateDisplayName(mangaId: Long, displayName: String?): Boolean = true

        override suspend fun update(update: tachiyomi.domain.manga.model.MangaUpdate): Boolean = true

        override suspend fun updateAll(mangaUpdates: List<tachiyomi.domain.manga.model.MangaUpdate>): Boolean = true

        override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> = manga

        override suspend fun getCoverHash(mangaId: Long, coverLastModified: Long): Long? = null

        override suspend fun upsertCoverHash(mangaId: Long, coverLastModified: Long, hash: Long) = Unit
    }

    private class FakeMergedMangaRepository(
        private val visibleTargetIds: Map<Long, Long> = emptyMap(),
        private val groupsByMangaId: Map<Long, List<MangaMerge>> = emptyMap(),
    ) : MergedMangaRepository {
        override suspend fun getAll(): List<MangaMerge> = groupsByMangaId.values.flatten()

        override fun subscribeAll(): Flow<List<MangaMerge>> = flowOf(groupsByMangaId.values.flatten())

        override suspend fun getGroupByMangaId(mangaId: Long): List<MangaMerge> = groupsByMangaId[mangaId].orEmpty()

        override fun subscribeGroupByMangaId(mangaId: Long): Flow<List<MangaMerge>> {
            return flowOf(groupsByMangaId[mangaId].orEmpty())
        }

        override suspend fun getGroupByTargetId(targetMangaId: Long): List<MangaMerge> {
            return groupsByMangaId.values.flatten().filter { it.targetId == targetMangaId }
        }

        override suspend fun getTargetId(mangaId: Long): Long? = visibleTargetIds[mangaId]

        override fun subscribeTargetId(mangaId: Long): Flow<Long?> = flowOf(visibleTargetIds[mangaId])

        override suspend fun upsertGroup(targetMangaId: Long, orderedMangaIds: List<Long>) = Unit

        override suspend fun removeMembers(targetMangaId: Long, mangaIds: List<Long>) = Unit

        override suspend fun deleteGroup(targetMangaId: Long) = Unit
    }
}
