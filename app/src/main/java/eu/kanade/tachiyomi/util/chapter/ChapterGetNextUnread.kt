package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter

/**
 * Gets next unread chapter with filters and sorting applied
 */
suspend fun List<Chapter>.getNextUnread(manga: Manga, downloadManager: DownloadManager): Chapter? {
    val isMerged = map { it.mangaId }.distinct().size > 1
    val chapters = if (!isMerged) {
        applyFilters(manga, downloadManager)
    } else {
        val unreadFilter = manga.unreadFilter
        val downloadedFilter = manga.downloadedFilter
        val bookmarkedFilter = manga.bookmarkedFilter
        filter { chapter -> applyFilter(unreadFilter) { !chapter.read } }
            .filter { chapter -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
            .filterNot { chapter ->
                manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                    !downloadManager.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        manga.source,
                    )
            }
            .filterNot { chapter ->
                manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                    downloadManager.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        manga.source,
                    )
            }
            .let { filtered ->
                filtered.map { it.mangaId }
                    .distinct()
                    .flatMap { mangaId ->
                        filtered.filter { it.mangaId == mangaId }
                            .sortedWith(getChapterSort(manga))
                    }
            }
    }
    return if (manga.sortDescending()) {
        chapters.findLast { !it.read }
    } else {
        chapters.find { !it.read }
    }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    val isMerged = map { it.manga.id }.distinct().size > 1
    return applyFilters(manga, isMerged = isMerged).let { chapters ->
        if (manga.sortDescending()) {
            chapters.findLast { !it.chapter.read }
        } else {
            chapters.find { !it.chapter.read }
        }
    }?.chapter
}
