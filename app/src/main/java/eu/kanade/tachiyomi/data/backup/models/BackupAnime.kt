package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.anime.model.AnimeTitle

@Serializable
data class BackupAnime(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var description: String? = null,
    @ProtoNumber(5) var genre: List<String> = emptyList(),
    @ProtoNumber(6) var thumbnailUrl: String? = null,
    @ProtoNumber(7) var dateAdded: Long = 0,
    @ProtoNumber(8) var episodes: List<BackupAnimeEpisode> = emptyList(),
    @ProtoNumber(9) var categories: List<Long> = emptyList(),
    @ProtoNumber(10) var history: List<BackupAnimeHistory> = emptyList(),
    @ProtoNumber(11) var playbackStates: List<BackupAnimePlaybackState> = emptyList(),
    @ProtoNumber(12) var favorite: Boolean = true,
    @ProtoNumber(13) var initialized: Boolean = false,
    @ProtoNumber(14) var lastUpdate: Long = 0,
    @ProtoNumber(16) var lastModifiedAt: Long = 0,
    @ProtoNumber(17) var favoriteModifiedAt: Long? = null,
    @ProtoNumber(18) var version: Long = 0,
    @ProtoNumber(19) var notes: String = "",
    @ProtoNumber(20) var displayName: String? = null,
    @ProtoNumber(21) var playbackPreferences: BackupAnimePlaybackPreferences? = null,
    @ProtoNumber(22) var originalTitle: String? = null,
    @ProtoNumber(23) var country: String? = null,
    @ProtoNumber(24) var studio: String? = null,
    @ProtoNumber(25) var producer: String? = null,
    @ProtoNumber(26) var director: String? = null,
    @ProtoNumber(27) var writer: String? = null,
    @ProtoNumber(28) var year: String? = null,
    @ProtoNumber(29) var duration: String? = null,
    @ProtoNumber(30) var status: Long = 0,
    @ProtoNumber(31) var episodeFlags: Long = 0,
    @ProtoNumber(32) var coverLastModified: Long = 0,
    @ProtoNumber(33) var mergeTargetSource: Long? = null,
    @ProtoNumber(34) var mergeTargetUrl: String? = null,
    @ProtoNumber(35) var mergePosition: Int? = null,
) {
    fun getAnimeImpl(): AnimeTitle {
        return AnimeTitle.create().copy(
            url = this@BackupAnime.url,
            title = this@BackupAnime.title,
            displayName = this@BackupAnime.displayName,
            originalTitle = this@BackupAnime.originalTitle,
            country = this@BackupAnime.country,
            studio = this@BackupAnime.studio,
            producer = this@BackupAnime.producer,
            director = this@BackupAnime.director,
            writer = this@BackupAnime.writer,
            year = this@BackupAnime.year,
            duration = this@BackupAnime.duration,
            description = this@BackupAnime.description,
            genre = this@BackupAnime.genre,
            status = this@BackupAnime.status,
            thumbnailUrl = this@BackupAnime.thumbnailUrl,
            favorite = this@BackupAnime.favorite,
            source = this@BackupAnime.source,
            dateAdded = this@BackupAnime.dateAdded,
            episodeFlags = this@BackupAnime.episodeFlags,
            coverLastModified = this@BackupAnime.coverLastModified,
            initialized = this@BackupAnime.initialized,
            lastUpdate = this@BackupAnime.lastUpdate,
            lastModifiedAt = this@BackupAnime.lastModifiedAt,
            favoriteModifiedAt = this@BackupAnime.favoriteModifiedAt,
            version = this@BackupAnime.version,
            notes = this@BackupAnime.notes,
        )
    }
}
