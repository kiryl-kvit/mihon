package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.MergedMangaRepository

class GetMangaWithChapters(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val mergedMangaRepository: MergedMangaRepository,
) {

    suspend fun subscribe(id: Long, applyScanlatorFilter: Boolean = false): Flow<Pair<Manga, List<Chapter>>> {
        return combine(
            mangaRepository.getMangaByIdAsFlow(id),
            mergedMangaRepository.subscribeGroupByMangaId(id),
        ) { manga, merges ->
            manga to merges
        }
            .let { upstream ->
                combine(upstream, chapterRepository.getChapterByMangaIdAsFlow(id, applyScanlatorFilter)) { (manga, merges), ownChapters ->
                    if (merges.isEmpty()) {
                        manga to ownChapters
                    } else {
                        manga to mergedChapters(manga, merges, applyScanlatorFilter)
                    }
                }
            }
    }

    suspend fun awaitManga(id: Long): Manga {
        return mangaRepository.getMangaById(id)
    }

    suspend fun awaitChapters(id: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        val merges = mergedMangaRepository.getGroupByMangaId(id)
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
        val chapterSort = getChapterSort(manga, sortDescending = false)
        return merges.sortedBy { it.position }
            .flatMap { merge ->
                chapterRepository.getChapterByMangaId(merge.mangaId, applyScanlatorFilter)
                    .sortedWith(chapterSort)
            }
    }
}
