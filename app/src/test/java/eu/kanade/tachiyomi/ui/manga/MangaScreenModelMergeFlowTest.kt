package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge

class MangaScreenModelMergeFlowTest {

    @Test
    fun `merge changes still refresh browse-opened member state when manga and chapters stay the same`() = runTest {
        val manga = Manga.create().copy(id = 2L, source = 1L)
        val chapters = listOf(chapter(id = 101L, mangaId = manga.id))
        val mangaAndChaptersFlow = MutableStateFlow(manga to chapters)
        val mergeGroupFlow = MutableStateFlow(
            listOf(
                MangaMerge(targetId = 1L, mangaId = 1L, position = 0L),
                MangaMerge(targetId = 1L, mangaId = 2L, position = 1L),
            ),
        )
        val downloadChangesFlow = MutableStateFlow(Unit)
        val downloadQueueFlow = MutableStateFlow(Unit)
        val emissions = mutableListOf<Pair<Manga, List<Chapter>>>()

        val job = launch {
            mergeAwareMangaAndChaptersFlow(
                mangaAndChaptersFlow = mangaAndChaptersFlow,
                mergeGroupFlow = mergeGroupFlow,
                downloadChangesFlow = downloadChangesFlow,
                downloadQueueFlow = downloadQueueFlow,
            )
                .take(2)
                .toList(emissions)
        }

        advanceUntilIdle()

        mergeGroupFlow.value = emptyList()

        advanceUntilIdle()

        emissions shouldBe listOf(
            manga to chapters,
            manga to chapters,
        )

        job.join()
    }

    private fun chapter(id: Long, mangaId: Long): Chapter {
        return Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            sourceOrder = 1L,
            name = "Chapter $id",
            url = "/chapter/$id",
        )
    }
}
