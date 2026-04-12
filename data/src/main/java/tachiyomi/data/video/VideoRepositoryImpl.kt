package tachiyomi.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import logcat.LogPriority
import kotlinx.coroutines.ExperimentalCoroutinesApi
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.model.VideoTitleUpdate
import tachiyomi.domain.video.repository.VideoRepository

@OptIn(ExperimentalCoroutinesApi::class)
class VideoRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : VideoRepository {

    override suspend fun getVideoById(id: Long): VideoTitle {
        return handler.awaitOne {
            videosQueries.getVideoById(id, profileProvider.activeProfileId, VideoMapper::mapVideo)
        }
    }

    override suspend fun getVideoByIdAsFlow(id: Long): Flow<VideoTitle> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                videosQueries.getVideoById(id, profileId, VideoMapper::mapVideo)
            }.filterNotNull()
        }
    }

    override suspend fun getVideoByUrlAndSourceId(url: String, sourceId: Long): VideoTitle? {
        return handler.awaitOneOrNull {
            videosQueries.getVideoByUrlAndSource(
                profileProvider.activeProfileId,
                url,
                sourceId,
                VideoMapper::mapVideo,
            )
        }
    }

    override fun getVideoByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<VideoTitle?> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToOneOrNull {
                videosQueries.getVideoByUrlAndSource(
                    profileId,
                    url,
                    sourceId,
                    VideoMapper::mapVideo,
                )
            }
        }
    }

    override suspend fun getFavorites(): List<VideoTitle> {
        return handler.awaitList {
            videosQueries.getFavorites(profileProvider.activeProfileId, VideoMapper::mapVideo)
        }
    }

    override fun getFavoritesAsFlow(): Flow<List<VideoTitle>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                videosQueries.getFavorites(profileId, VideoMapper::mapVideo)
            }
        }
    }

    override suspend fun getAllVideosByProfile(profileId: Long): List<VideoTitle> {
        return handler.awaitList {
            videosQueries.getAllVideos(profileId, VideoMapper::mapVideo)
        }
    }

    override suspend fun update(update: VideoTitleUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(videoUpdates: List<VideoTitleUpdate>): Boolean {
        return try {
            partialUpdate(*videoUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun insertNetworkVideo(videos: List<VideoTitle>): List<VideoTitle> {
        return handler.await(inTransaction = true) {
            videos.map {
                videosQueries.insertNetworkVideo(
                    profileId = profileProvider.activeProfileId,
                    source = it.source,
                    url = it.url,
                    title = it.title,
                    description = it.description,
                    genre = it.genre,
                    thumbnailUrl = it.thumbnailUrl,
                    favorite = it.favorite,
                    initialized = it.initialized,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    dateAdded = it.dateAdded,
                    version = it.version,
                    notes = it.notes,
                    updateTitle = it.title.isNotBlank(),
                    updateCover = !it.thumbnailUrl.isNullOrBlank(),
                    updateDetails = it.initialized,
                )
                    .executeAsOne()
                    .let { row -> VideoMapper.mapVideo(row) }
            }
        }
    }

    override suspend fun setVideoCategories(videoId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            videos_categoriesQueries.deleteVideoCategoryByVideoId(profileProvider.activeProfileId, videoId)
            categoryIds.forEach { categoryId ->
                videos_categoriesQueries.insert(profileProvider.activeProfileId, videoId, categoryId)
            }
        }
    }

    private suspend fun partialUpdate(vararg videoUpdates: VideoTitleUpdate) {
        handler.await(inTransaction = true) {
            videoUpdates.forEach { value ->
                videosQueries.update(
                    source = value.source,
                    url = value.url,
                    title = value.title,
                    displayName = value.displayName,
                    description = value.description,
                    genre = VideoMapper.encodeGenre(value.genre),
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    initialized = value.initialized,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    dateAdded = value.dateAdded,
                    videoId = value.id,
                    version = value.version,
                    notes = value.notes,
                    profileId = profileProvider.activeProfileId,
                )
            }
        }
    }
}
