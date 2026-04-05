package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.sortedForReading
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun Chapter.isDownloaded(
    manga: Manga,
    downloadCache: DownloadCache = Injekt.get(),
): Boolean {
    return manga.isLocal() || downloadCache.isChapterDownloaded(name, scanlator, url, manga.title, manga.source, false)
}

/**
 * Returns a copy of the list with not downloaded chapters removed.
 */
fun List<Chapter>.filterDownloaded(mangaById: Map<Long, Manga>): List<Chapter> {
    return filter { chapter ->
        val chapterManga = mangaById[chapter.mangaId] ?: return@filter false
        chapter.isDownloaded(chapterManga)
    }
}

fun List<Chapter>.filterForResume(
    manga: Manga,
    mangaById: Map<Long, Manga>,
    downloadManager: DownloadManager,
): List<Chapter> {
    val unreadFilter = manga.unreadFilter
    val bookmarkedFilter = manga.bookmarkedFilter
    val downloadedFilter = manga.downloadedFilter

    return filter { chapter -> applyFilter(unreadFilter) { !chapter.read } }
        .filter { chapter -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
        .filter { chapter ->
            applyFilter(downloadedFilter) {
                val chapterManga = mangaById[chapter.mangaId] ?: return@applyFilter false
                val downloaded = downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    chapterManga.title,
                    chapterManga.source,
                )
                downloaded || chapterManga.isLocal()
            }
        }
}

fun List<Chapter>.sortedForResume(
    manga: Manga,
    mangaById: Map<Long, Manga>,
    downloadManager: DownloadManager,
): List<Chapter> {
    return filterForResume(manga, mangaById, downloadManager)
        .sortedForReading(manga)
}
