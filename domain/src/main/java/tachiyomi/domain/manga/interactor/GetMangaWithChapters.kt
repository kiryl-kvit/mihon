package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.MergedMangaRepository

class GetMangaWithChapters(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val mergedMangaRepository: MergedMangaRepository,
) {

    suspend fun subscribe(
        id: Long,
        applyScanlatorFilter: Boolean = false,
        bypassMerge: Boolean = false,
    ): Flow<Pair<Manga, List<Chapter>>> {
        return combine(
            mangaRepository.getMangaByIdAsFlow(id),
            mergedMangaRepository.subscribeGroupByMangaId(id),
        ) { manga, merges ->
            manga to if (bypassMerge) emptyList() else merges
        }
            .flatMapLatest { (manga, merges) ->
                if (merges.isEmpty()) {
                    chapterRepository.getChapterByMangaIdAsFlow(id, applyScanlatorFilter)
                        .map { manga to it }
                } else {
                    mergedChaptersAsFlow(manga, merges, applyScanlatorFilter)
                        .map { manga to it }
                }
            }
    }

    suspend fun awaitManga(id: Long): Manga {
        return mangaRepository.getMangaById(id)
    }

    suspend fun awaitChapters(
        id: Long,
        applyScanlatorFilter: Boolean = false,
        bypassMerge: Boolean = false,
    ): List<Chapter> {
        val merges = if (bypassMerge) {
            emptyList()
        } else {
            mergedMangaRepository.getGroupByMangaId(id)
        }
        return if (merges.isEmpty()) {
            chapterRepository.getChapterByMangaId(id, applyScanlatorFilter)
        } else {
            val manga = mangaRepository.getMangaById(id)
            mergedChapters(manga, merges, applyScanlatorFilter)
        }
    }

    private suspend fun mergedChapters(
        manga: Manga,
        merges: List<MangaMerge>,
        applyScanlatorFilter: Boolean,
    ): List<Chapter> {
        return mergeChapters(
            manga = manga,
            chapterLists = merges.sortedBy { it.position }
                .map { merge -> chapterRepository.getChapterByMangaId(merge.mangaId, applyScanlatorFilter) },
        )
    }

    private suspend fun mergedChaptersAsFlow(
        manga: Manga,
        merges: List<MangaMerge>,
        applyScanlatorFilter: Boolean,
    ): Flow<List<Chapter>> {
        val orderedMerges = merges.sortedBy { it.position }
        return combine(
            orderedMerges.map { merge ->
                chapterRepository.getChapterByMangaIdAsFlow(merge.mangaId, applyScanlatorFilter)
            },
        ) { chapterLists ->
            mergeChapters(manga, chapterLists.asIterable())
        }
    }

    private fun mergeChapters(
        manga: Manga,
        chapterLists: Iterable<List<Chapter>>,
    ): List<Chapter> {
        val chapterSort = getChapterSort(manga)
        return chapterLists.flatMap { chapters ->
            chapters.sortedWith(chapterSort)
        }
    }
}
