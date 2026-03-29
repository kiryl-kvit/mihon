package tachiyomi.data.updates

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

class UpdatesRepositoryImpl(
    private val databaseHandler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<UpdatesWithRelations> {
        return databaseHandler.awaitList {
            updatesViewQueries.getUpdatesByReadStatus(
                profileId = profileProvider.activeProfileId,
                read = read,
                after = after,
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    override fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
    ): Flow<List<UpdatesWithRelations>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            databaseHandler.subscribeToList {
                updatesViewQueries.getRecentUpdatesWithFilters(
                    profileId = profileId,
                    after = after,
                    limit = limit,
                    // invert because unread in Kotlin -> read column in SQL
                    read = unread?.let { !it },
                    started = started?.toLong(),
                    bookmarked = bookmarked,
                    hideExcludedScanlators = hideExcludedScanlators.toLong(),
                    mapper = ::mapUpdatesWithRelations,
                )
            }
        }
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<UpdatesWithRelations>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            databaseHandler.subscribeToList {
                updatesViewQueries.getUpdatesByReadStatus(
                    profileId = profileId,
                    read = read,
                    after = after,
                    limit = limit,
                    mapper = ::mapUpdatesWithRelations,
                )
            }
        }
    }

    private fun mapUpdatesWithRelations(
        @Suppress("UNUSED_PARAMETER")
        profileId: Long,
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Long,
        dateUpload: Long,
        dateFetch: Long,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
