package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.HiddenSourceIds

class GetNextChaptersTest {

    private val getManga = mockk<GetManga>()
    private val getMangaWithChapters = mockk<GetMangaWithChapters>()
    private val historyRepository = mockk<HistoryRepository>()
    private val hiddenSourceIds = mockk<HiddenSourceIds>()

    private val getNextChapters = GetNextChapters(
        getManga = getManga,
        getMangaWithChapters = getMangaWithChapters,
        historyRepository = historyRepository,
        hiddenSourceIds = hiddenSourceIds,
    )

    @Test
    fun `await sorts chapters into reading order`() = runTest {
        val mangaId = 1L
        val manga = Manga.create().copy(id = mangaId)
        val chapters = listOf(
            chapter(id = 105, mangaId = mangaId, sourceOrder = 0, read = false),
            chapter(id = 104, mangaId = mangaId, sourceOrder = 1, read = false),
            chapter(id = 103, mangaId = mangaId, sourceOrder = 2, read = true),
        )

        coEvery { getManga.await(mangaId) } returns manga
        coEvery { getMangaWithChapters.awaitChapters(mangaId, true) } returns chapters

        getNextChapters.await(mangaId, onlyUnread = false).map(Chapter::id) shouldBe listOf(103L, 104L, 105L)
    }

    @Test
    fun `await from chapter returns following chapters in reading order`() = runTest {
        val mangaId = 1L
        val manga = Manga.create().copy(id = mangaId)
        val chapters = listOf(
            chapter(id = 105, mangaId = mangaId, sourceOrder = 0, read = false),
            chapter(id = 104, mangaId = mangaId, sourceOrder = 1, read = false),
            chapter(id = 103, mangaId = mangaId, sourceOrder = 2, read = true),
        )

        coEvery { getManga.await(mangaId) } returns manga
        coEvery { getMangaWithChapters.awaitChapters(mangaId, true) } returns chapters

        getNextChapters.await(mangaId, fromChapterId = 104L, onlyUnread = false).map(Chapter::id) shouldBe listOf(104L, 105L)
    }

    private fun chapter(
        id: Long,
        mangaId: Long,
        sourceOrder: Long,
        read: Boolean,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            sourceOrder = sourceOrder,
            read = read,
            name = "Chapter $id",
            url = "/chapter/$id",
        )
    }
}
