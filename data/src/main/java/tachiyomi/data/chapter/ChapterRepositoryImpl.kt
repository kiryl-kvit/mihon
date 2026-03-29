package tachiyomi.data.chapter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class ChapterRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            handler.await(inTransaction = true) {
                chapters.map { chapter ->
                    chaptersQueries.insert(
                        profileId = profileProvider.activeProfileId,
                        mangaId = chapter.mangaId,
                        url = chapter.url,
                        name = chapter.name,
                        scanlator = chapter.scanlator,
                        read = chapter.read,
                        bookmark = chapter.bookmark,
                        lastPageRead = chapter.lastPageRead,
                        chapterNumber = chapter.chapterNumber,
                        sourceOrder = chapter.sourceOrder,
                        dateFetch = chapter.dateFetch,
                        dateUpload = chapter.dateUpload,
                        version = chapter.version,
                    )
                    val lastInsertId = chaptersQueries.selectLastInsertedRowId().executeAsOne()
                    chapter.copy(id = lastInsertId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    private suspend fun partialUpdate(vararg chapterUpdates: ChapterUpdate) {
        handler.await(inTransaction = true) {
            chapterUpdates.forEach { chapterUpdate ->
                chaptersQueries.update(
                    mangaId = chapterUpdate.mangaId,
                    url = chapterUpdate.url,
                    name = chapterUpdate.name,
                    scanlator = chapterUpdate.scanlator,
                    read = chapterUpdate.read,
                    bookmark = chapterUpdate.bookmark,
                    lastPageRead = chapterUpdate.lastPageRead,
                    chapterNumber = chapterUpdate.chapterNumber,
                    sourceOrder = chapterUpdate.sourceOrder,
                    dateFetch = chapterUpdate.dateFetch,
                    dateUpload = chapterUpdate.dateUpload,
                    chapterId = chapterUpdate.id,
                    version = chapterUpdate.version,
                    isSyncing = 0,
                    profileId = profileProvider.activeProfileId,
                )
            }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            handler.await { chaptersQueries.removeChaptersWithIds(profileProvider.activeProfileId, chapterIds) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getChaptersByMangaId(
                profileProvider.activeProfileId,
                mangaId,
                applyScanlatorFilter.toLong(),
                ::mapChapter,
            )
        }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return handler.awaitList {
            chaptersQueries.getScanlatorsByMangaId(profileProvider.activeProfileId, mangaId) { it.orEmpty() }
        }
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                chaptersQueries.getScanlatorsByMangaId(profileId, mangaId) { it.orEmpty() }
            }
        }
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return handler.awaitList {
            chaptersQueries.getBookmarkedChaptersByMangaId(
                profileProvider.activeProfileId,
                mangaId,
                ::mapChapter,
            )
        }
    }

    override suspend fun getChapterById(id: Long): Chapter? {
        return handler.awaitOneOrNull {
            chaptersQueries.getChapterById(id, profileProvider.activeProfileId, ::mapChapter)
        }
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                chaptersQueries.getChaptersByMangaId(
                    profileId,
                    mangaId,
                    applyScanlatorFilter.toLong(),
                    ::mapChapter,
                )
            }
        }
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return handler.awaitOneOrNull {
            chaptersQueries.getChapterByUrlAndMangaId(
                profileProvider.activeProfileId,
                url,
                mangaId,
                ::mapChapter,
            )
        }
    }

    private fun mapChapter(
        id: Long,
        @Suppress("UNUSED_PARAMETER")
        profileId: Long,
        mangaId: Long,
        url: String,
        name: String,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        chapterNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        @Suppress("UNUSED_PARAMETER")
        isSyncing: Long,
    ): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = scanlator,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )
}
