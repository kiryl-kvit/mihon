package tachiyomi.data.track

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

class TrackRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : TrackRepository {

    override suspend fun getTrackById(id: Long): Track? {
        return handler.awaitOneOrNull {
            manga_syncQueries.getTrackById(id, profileProvider.activeProfileId, TrackMapper::mapTrack)
        }
    }

    override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
        return handler.awaitList {
            manga_syncQueries.getTracksByMangaId(profileProvider.activeProfileId, mangaId, TrackMapper::mapTrack)
        }
    }

    override fun getTracksAsFlow(): Flow<List<Track>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                manga_syncQueries.getTracks(profileId, TrackMapper::mapTrack)
            }
        }
    }

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                manga_syncQueries.getTracksByMangaId(profileId, mangaId, TrackMapper::mapTrack)
            }
        }
    }

    override suspend fun delete(mangaId: Long, trackerId: Long) {
        handler.await {
            manga_syncQueries.delete(
                profileId = profileProvider.activeProfileId,
                mangaId = mangaId,
                syncId = trackerId,
            )
        }
    }

    override suspend fun insert(track: Track) {
        insertValues(track)
    }

    override suspend fun insertAll(tracks: List<Track>) {
        insertValues(*tracks.toTypedArray())
    }

    private suspend fun insertValues(vararg tracks: Track) {
        handler.await(inTransaction = true) {
            tracks.forEach { mangaTrack ->
                manga_syncQueries.insert(
                    profileId = profileProvider.activeProfileId,
                    mangaId = mangaTrack.mangaId,
                    syncId = mangaTrack.trackerId,
                    remoteId = mangaTrack.remoteId,
                    libraryId = mangaTrack.libraryId,
                    title = mangaTrack.title,
                    lastChapterRead = mangaTrack.lastChapterRead,
                    totalChapters = mangaTrack.totalChapters,
                    status = mangaTrack.status,
                    score = mangaTrack.score,
                    remoteUrl = mangaTrack.remoteUrl,
                    startDate = mangaTrack.startDate,
                    finishDate = mangaTrack.finishDate,
                    private = mangaTrack.private,
                )
            }
        }
    }
}
