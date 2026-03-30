package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Gets next unread chapter with filters and sorting applied
 */
suspend fun List<Chapter>.getNextUnread(manga: Manga, downloadManager: DownloadManager): Chapter? {
    val isMerged = map { it.mangaId }.distinct().size > 1
    val chapters = if (!isMerged) {
        applyFilters(manga, downloadManager)
    } else {
        val mangaRepository = Injekt.get<MangaRepository>()
        val unreadFilter = manga.unreadFilter
        val downloadedFilter = manga.downloadedFilter
        val bookmarkedFilter = manga.bookmarkedFilter
        filter { chapter -> applyFilter(unreadFilter) { !chapter.read } }
            .filter { chapter -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
            .filter { chapter ->
                applyFilter(downloadedFilter) {
                    val chapterManga = mangaRepository.getMangaById(chapter.mangaId)
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
    return chapters.firstOrNull { !it.read }
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    val isMerged = map { it.manga.id }.distinct().size > 1
    return applyFilters(manga, isMerged = isMerged).firstOrNull { !it.chapter.read }?.chapter
}
