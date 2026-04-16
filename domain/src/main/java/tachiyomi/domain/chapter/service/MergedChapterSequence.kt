package tachiyomi.domain.chapter.service

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.util.orderedPresentIds

fun List<Chapter>.sortedForMergedDisplay(
    manga: Manga,
    mergedMangaIds: List<Long> = map(Chapter::mangaId).distinct(),
): List<Chapter> {
    if (mergedMangaIds.size <= 1) {
        return sortedWith(getChapterSort(manga))
    }

    val chapterSort = getChapterSort(manga)
    return mergedMangaIds.orderedPresentIds(this, Chapter::mangaId)
        .flatMap { mangaId ->
            asSequence()
                .filter { it.mangaId == mangaId }
                .sortedWith(chapterSort)
                .toList()
        }
}

fun List<Chapter>.sortedForReading(
    manga: Manga,
    mergedMangaIds: List<Long> = map(Chapter::mangaId).distinct(),
): List<Chapter> {
    if (mergedMangaIds.size <= 1) {
        return sortedWith(getChapterSort(manga, sortDescending = false))
    }

    val readingSort = getChapterSort(manga, sortDescending = false)
    val orderedMergedIds = mergedMangaIds.orderedPresentIds(this, Chapter::mangaId).let { ids ->
        if (manga.sortDescending()) ids.asReversed() else ids
    }
    return orderedMergedIds
        .flatMap { mangaId ->
            asSequence()
                .filter { it.mangaId == mangaId }
                .sortedWith(readingSort)
                .toList()
        }
}

fun List<Chapter>.groupedByMergedMember(
    mergedMangaIds: List<Long> = map(Chapter::mangaId).distinct(),
): List<Pair<Long, List<Chapter>>> {
    return mergedMangaIds.orderedPresentIds(this, Chapter::mangaId)
        .mapNotNull { mangaId ->
            val memberChapters = filter { it.mangaId == mangaId }
            memberChapters.takeIf { it.isNotEmpty() }?.let { mangaId to it }
        }
}
