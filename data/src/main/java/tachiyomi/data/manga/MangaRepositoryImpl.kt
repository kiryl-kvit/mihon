package tachiyomi.data.manga

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.LocalDate
import java.time.ZoneId

class MangaRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return handler.awaitOne {
            mangasQueries.getMangaById(id, profileProvider.activeProfileId, MangaMapper::mapManga)
        }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOne { mangasQueries.getMangaById(id, profileId, MangaMapper::mapManga) }
        }
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
                profileProvider.activeProfileId,
                url,
                sourceId,
                MangaMapper::mapManga,
            )
        }
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                mangasQueries.getMangaByUrlAndSource(
                    profileId,
                    url,
                    sourceId,
                    MangaMapper::mapManga,
                )
            }
        }
    }

    override suspend fun getFavorites(): List<Manga> {
        return handler.awaitList {
            mangasQueries.getFavorites(profileProvider.activeProfileId, MangaMapper::mapManga)
        }
    }

    override suspend fun getFavoritesByProfile(profileId: Long): List<Manga> {
        return handler.awaitList {
            mangasQueries.getFavorites(profileId, MangaMapper::mapManga)
        }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return handler.awaitList {
            mangasQueries.getReadMangaNotInLibrary(profileProvider.activeProfileId, MangaMapper::mapManga)
        }
    }

    override suspend fun getReadMangaNotInLibraryByProfile(profileId: Long): List<Manga> {
        return handler.awaitList {
            mangasQueries.getReadMangaNotInLibrary(profileId, MangaMapper::mapManga)
        }
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return handler.awaitList {
            libraryViewQueries.library(profileProvider.activeProfileId, MangaMapper::mapLibraryManga)
        }
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList { libraryViewQueries.library(profileId, MangaMapper::mapLibraryManga) }
        }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                mangasQueries.getFavoriteBySourceId(profileId, sourceId, MangaMapper::mapManga)
            }
        }
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return handler.awaitList {
            mangasQueries.getDuplicateLibraryManga(
                profileProvider.activeProfileId,
                id,
                title,
                MangaMapper::mapMangaWithChapterCount,
            )
        }
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                mangasQueries.getUpcomingManga(profileId, epochMillis, statuses, MangaMapper::mapManga)
            }
        }
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            handler.await { mangasQueries.resetViewerFlags(profileProvider.activeProfileId) }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(profileProvider.activeProfileId, mangaId)
            categoryIds.map { categoryId ->
                mangas_categoriesQueries.insert(profileProvider.activeProfileId, mangaId, categoryId)
            }
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        return handler.await(inTransaction = true) {
            manga.map {
                mangasQueries.insertNetworkManga(
                    profileId = profileProvider.activeProfileId,
                    source = it.source,
                    url = it.url,
                    artist = it.artist,
                    author = it.author,
                    description = it.description,
                    genre = it.genre,
                    title = it.title,
                    status = it.status,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                    mapper = MangaMapper::mapManga,
                )
                    .executeAsOne()
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        handler.await(inTransaction = true) {
            mangaUpdates.forEach { value ->
                mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    calculateInterval = value.fetchInterval?.toLong(),
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    mangaId = value.id,
                    updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                    profileId = profileProvider.activeProfileId,
                )
            }
        }
    }
}
