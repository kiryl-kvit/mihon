package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.MergedMangaRepository

class GetMangaWithChaptersTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val chapterRepository = mockk<ChapterRepository>()
    private val mergedMangaRepository = mockk<MergedMangaRepository>()

    private val getMangaWithChapters = GetMangaWithChapters(
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        mergedMangaRepository = mergedMangaRepository,
    )

    @Test
    fun `subscribe updates when non-target merged member chapters change`() = runTest {
        val mangaId = 1L
        val mergedMemberId = 2L
        val manga = Manga.create().copy(id = mangaId)
        val mangaFlow = MutableStateFlow(manga)
        val mergesFlow = MutableStateFlow(
            listOf(
                MangaMerge(targetId = mangaId, mangaId = mangaId, position = 0),
                MangaMerge(targetId = mangaId, mangaId = mergedMemberId, position = 1),
            ),
        )
        val targetChaptersFlow = MutableStateFlow(
            listOf(
                chapter(id = 101, mangaId = mangaId, sourceOrder = 1),
            ),
        )
        val mergedChaptersFlow = MutableStateFlow(
            listOf(
                chapter(id = 201, mangaId = mergedMemberId, sourceOrder = 1),
            ),
        )

        coEvery { mangaRepository.getMangaByIdAsFlow(mangaId) } returns mangaFlow
        every { mergedMangaRepository.subscribeGroupByMangaId(mangaId) } returns mergesFlow
        coEvery { chapterRepository.getChapterByMangaIdAsFlow(mangaId, false) } returns targetChaptersFlow
        coEvery { chapterRepository.getChapterByMangaIdAsFlow(mergedMemberId, false) } returns mergedChaptersFlow

        val emissions = mutableListOf<Pair<Manga, List<Chapter>>>()

        val job = launch {
            getMangaWithChapters.subscribe(mangaId)
                .take(2)
                .toList(emissions)
        }

        advanceUntilIdle()

        mergedChaptersFlow.value =
            mergedChaptersFlow.value + chapter(id = 202, mangaId = mergedMemberId, sourceOrder = 2)

        advanceUntilIdle()

        emissions.map { emission -> emission.second.map(Chapter::id) } shouldBe listOf(
            listOf(101L, 201L),
            listOf(101L, 201L, 202L),
        )

        job.join()
    }

    @Test
    fun `subscribe bypasses merged chapters when requested`() = runTest {
        val mangaId = 1L
        val mergedMemberId = 2L
        val manga = Manga.create().copy(id = mangaId)
        val mangaFlow = MutableStateFlow(manga)
        val mergesFlow = MutableStateFlow(
            listOf(
                MangaMerge(targetId = mangaId, mangaId = mangaId, position = 0),
                MangaMerge(targetId = mangaId, mangaId = mergedMemberId, position = 1),
            ),
        )
        val targetChaptersFlow = MutableStateFlow(
            listOf(
                chapter(id = 101, mangaId = mangaId, sourceOrder = 1),
            ),
        )

        coEvery { mangaRepository.getMangaByIdAsFlow(mangaId) } returns mangaFlow
        every { mergedMangaRepository.subscribeGroupByMangaId(mangaId) } returns mergesFlow
        coEvery { chapterRepository.getChapterByMangaIdAsFlow(mangaId, false) } returns targetChaptersFlow

        val emissions = mutableListOf<Pair<Manga, List<Chapter>>>()

        val job = launch {
            getMangaWithChapters.subscribe(mangaId, bypassMerge = true)
                .take(1)
                .toList(emissions)
        }

        advanceUntilIdle()

        emissions.single().second.map(Chapter::id) shouldBe listOf(101L)

        job.join()
    }

    @Test
    fun `awaitChapters bypasses merged chapters when requested`() = runTest {
        val mangaId = 1L
        val chapters = listOf(
            chapter(id = 101, mangaId = mangaId, sourceOrder = 1),
        )

        coEvery { chapterRepository.getChapterByMangaId(mangaId, false) } returns chapters

        getMangaWithChapters.awaitChapters(mangaId, bypassMerge = true) shouldBe chapters
    }

    private fun chapter(
        id: Long,
        mangaId: Long,
        sourceOrder: Long,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            sourceOrder = sourceOrder,
            name = "Chapter $id",
            url = "/chapter/$id",
        )
    }
}
