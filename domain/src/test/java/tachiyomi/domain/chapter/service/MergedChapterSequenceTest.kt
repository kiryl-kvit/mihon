package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class MergedChapterSequenceTest {

    @Test
    fun `merged display order follows member order and visible descending sort`() {
        val manga = Manga.create().copy(
            id = 1L,
            chapterFlags = Manga.CHAPTER_SORT_DESC or Manga.CHAPTER_SORTING_NUMBER,
        )

        val chapters = listOf(
            chapter(id = 101, mangaId = 1, chapterNumber = 1.0),
            chapter(id = 203, mangaId = 2, chapterNumber = 3.0),
            chapter(id = 202, mangaId = 2, chapterNumber = 2.0),
            chapter(id = 201, mangaId = 2, chapterNumber = 1.0),
        )

        chapters.sortedForMergedDisplay(manga).map(Chapter::id) shouldBe listOf(101L, 203L, 202L, 201L)
    }

    @Test
    fun `merged reading order follows descending group traversal and canonical ascending chapters`() {
        val manga = Manga.create().copy(
            id = 1L,
            chapterFlags = Manga.CHAPTER_SORT_DESC or Manga.CHAPTER_SORTING_NUMBER,
        )

        val chapters = listOf(
            chapter(id = 101, mangaId = 1, chapterNumber = 1.0),
            chapter(id = 203, mangaId = 2, chapterNumber = 3.0),
            chapter(id = 202, mangaId = 2, chapterNumber = 2.0),
            chapter(id = 201, mangaId = 2, chapterNumber = 1.0),
        )

        chapters.sortedForReading(manga).map(Chapter::id) shouldBe listOf(201L, 202L, 203L, 101L)
    }

    @Test
    fun `merged reading order keeps top to bottom groups when sort is ascending`() {
        val manga = Manga.create().copy(
            id = 1L,
            chapterFlags = Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_SORTING_NUMBER,
        )

        val chapters = listOf(
            chapter(id = 101, mangaId = 1, chapterNumber = 1.0),
            chapter(id = 203, mangaId = 2, chapterNumber = 3.0),
            chapter(id = 202, mangaId = 2, chapterNumber = 2.0),
            chapter(id = 201, mangaId = 2, chapterNumber = 1.0),
        )

        chapters.sortedForReading(manga).map(Chapter::id) shouldBe listOf(101L, 201L, 202L, 203L)
    }

    @Test
    fun `non merged chapters keep reader ascending order`() {
        val manga = Manga.create().copy(
            id = 1L,
            chapterFlags = Manga.CHAPTER_SORT_DESC or Manga.CHAPTER_SORTING_NUMBER,
        )

        val chapters = listOf(
            chapter(id = 103, mangaId = 1, chapterNumber = 3.0),
            chapter(id = 102, mangaId = 1, chapterNumber = 2.0),
            chapter(id = 101, mangaId = 1, chapterNumber = 1.0),
        )

        chapters.sortedForReading(manga).map(Chapter::id) shouldBe listOf(101L, 102L, 103L)
    }

    @Test
    fun `merged display order ignores removed member ids that no longer have chapters`() {
        val manga = Manga.create().copy(
            id = 1L,
            chapterFlags = Manga.CHAPTER_SORT_DESC or Manga.CHAPTER_SORTING_NUMBER,
        )

        val chapters = listOf(
            chapter(id = 101, mangaId = 1, chapterNumber = 1.0),
            chapter(id = 301, mangaId = 3, chapterNumber = 1.0),
        )

        chapters.sortedForMergedDisplay(manga, mergedMangaIds = listOf(1L, 2L, 3L)).map(Chapter::id) shouldBe
            listOf(101L, 301L)
    }

    @Test
    fun `merged reading order ignores removed member ids that no longer have chapters`() {
        val manga = Manga.create().copy(
            id = 1L,
            chapterFlags = Manga.CHAPTER_SORT_DESC or Manga.CHAPTER_SORTING_NUMBER,
        )

        val chapters = listOf(
            chapter(id = 101, mangaId = 1, chapterNumber = 1.0),
            chapter(id = 301, mangaId = 3, chapterNumber = 1.0),
        )

        chapters.sortedForReading(manga, mergedMangaIds = listOf(1L, 2L, 3L)).map(Chapter::id) shouldBe
            listOf(301L, 101L)
    }

    private fun chapter(
        id: Long,
        mangaId: Long,
        chapterNumber: Double,
    ): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            chapterNumber = chapterNumber,
            name = "Chapter $id",
            url = "/chapter/$id",
        )
    }
}
