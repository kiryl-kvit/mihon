package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimePlaybackPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupAnimePlaybackState
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.anime.AnimeEpisodeMapper
import tachiyomi.data.anime.AnimeHistoryMapper
import tachiyomi.data.anime.AnimePlaybackPreferencesMapper
import tachiyomi.data.anime.AnimePlaybackStateMapper
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.model.Category
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBackupCreator(
    private val handler: DatabaseHandler = Injekt.get(),
    private val profileProvider: ActiveProfileProvider = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animeHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val animePlaybackPreferencesRepository: AnimePlaybackPreferencesRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
) {

    suspend operator fun invoke(animes: List<AnimeTitle>, options: BackupOptions): List<BackupAnime> {
        return invoke(profileProvider.activeProfileId, animes, options)
    }

    suspend operator fun invoke(
        profileId: Long,
        animes: List<AnimeTitle>,
        options: BackupOptions,
    ): List<BackupAnime> {
        val allAnimeById = animeRepository.getAllAnimeByProfile(profileId).associateBy { it.id }
        return animes.map { backupAnime(profileId, it, options, allAnimeById) }
    }

    private suspend fun backupAnime(
        profileId: Long,
        anime: AnimeTitle,
        options: BackupOptions,
        allAnimeById: Map<Long, AnimeTitle>,
    ): BackupAnime {
        val animeObject = anime.toBackupAnime()
        getPlaybackPreferencesForBackup(profileId, anime.id)?.let { preferences ->
            animeObject.playbackPreferences = BackupAnimePlaybackPreferences(
                dubKey = preferences.dubKey,
                streamKey = preferences.streamKey,
                sourceQualityKey = preferences.sourceQualityKey,
                playerQualityMode = AnimePlaybackPreferencesMapper.encodePlayerQualityMode(
                    preferences.playerQualityMode,
                ),
                playerQualityHeight = preferences.playerQualityHeight,
                subtitleOffsetX = preferences.subtitleOffsetX,
                subtitleOffsetY = preferences.subtitleOffsetY,
                subtitleTextSize = preferences.subtitleTextSize,
                subtitleTextColor = preferences.subtitleTextColor,
                subtitleBackgroundColor = preferences.subtitleBackgroundColor,
                subtitleBackgroundOpacity = preferences.subtitleBackgroundOpacity,
                updatedAt = preferences.updatedAt,
            )
        }

        if (options.chapters) {
            animeObject.episodes = getEpisodesForBackup(profileId, anime.id)
                .map {
                    BackupAnimeEpisode(
                        url = it.url,
                        name = it.name,
                        watched = it.watched,
                        completed = it.completed,
                        dateFetch = it.dateFetch,
                        dateUpload = it.dateUpload,
                        episodeNumber = it.episodeNumber.toFloat(),
                        sourceOrder = it.sourceOrder,
                        lastModifiedAt = it.lastModifiedAt,
                        version = it.version,
                    )
                }

            animeObject.playbackStates = animeObject.episodes.mapNotNull { backupEpisode ->
                val episode = getEpisodeByUrlForBackup(profileId, anime.id, backupEpisode.url) ?: return@mapNotNull null
                val state = getPlaybackStateForBackup(profileId, episode.id) ?: return@mapNotNull null
                BackupAnimePlaybackState(
                    url = backupEpisode.url,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    completed = state.completed,
                    lastWatchedAt = state.lastWatchedAt,
                )
            }
        }

        if (options.categories) {
            val categoriesForAnime = if (profileId == profileProvider.activeProfileId) {
                getAnimeCategories.await(anime.id)
            } else {
                handler.awaitList {
                    categoriesQueries.getCategoriesByAnimeId(profileId, anime.id) { id, name, order, flags ->
                        Category(id = id, name = name, order = order, flags = flags)
                    }
                }
            }
            if (categoriesForAnime.isNotEmpty()) {
                animeObject.categories = categoriesForAnime.map { it.order }
            }
        }

        if (options.history) {
            val history = getHistoryForBackup(profileId, anime.id)
            if (history.isNotEmpty()) {
                animeObject.history = history.mapNotNull { item ->
                    val episode = getEpisodeByIdForBackup(profileId, item.episodeId) ?: return@mapNotNull null
                    BackupAnimeHistory(
                        url = episode.url,
                        lastWatched = item.watchedAt?.time ?: 0L,
                        watchedDuration = item.watchedDuration,
                    )
                }
            }
        }

        val mergeGroup = getMergedAnime.awaitGroupByAnimeId(anime.id)
        if (mergeGroup.isNotEmpty()) {
            val targetId = mergeGroup.first().targetId
            val targetAnime = allAnimeById[targetId]
            val position = mergeGroup.firstOrNull { it.animeId == anime.id }?.position?.toInt()
            if (targetAnime != null && position != null) {
                animeObject.mergeTargetSource = targetAnime.source
                animeObject.mergeTargetUrl = targetAnime.url
                animeObject.mergePosition = position
            }
        }

        return animeObject
    }

    private suspend fun getEpisodesForBackup(profileId: Long, animeId: Long): List<AnimeEpisode> {
        return if (profileId == profileProvider.activeProfileId) {
            animeEpisodeRepository.getEpisodesByAnimeId(animeId)
        } else {
            handler.awaitList {
                anime_episodesQueries.getEpisodesByAnimeId(profileId, animeId, AnimeEpisodeMapper::mapEpisode)
            }
        }
    }

    private suspend fun getEpisodeByUrlForBackup(
        profileId: Long,
        animeId: Long,
        episodeUrl: String,
    ): AnimeEpisode? {
        return if (profileId == profileProvider.activeProfileId) {
            animeEpisodeRepository.getEpisodeByUrlAndAnimeId(episodeUrl, animeId)
        } else {
            handler.awaitOneOrNull {
                anime_episodesQueries.getEpisodeByUrlAndAnimeId(
                    profileId,
                    episodeUrl,
                    animeId,
                    AnimeEpisodeMapper::mapEpisode,
                )
            }
        }
    }

    private suspend fun getEpisodeByIdForBackup(profileId: Long, episodeId: Long): AnimeEpisode? {
        return if (profileId == profileProvider.activeProfileId) {
            animeEpisodeRepository.getEpisodeById(episodeId)
        } else {
            handler.awaitOneOrNull {
                anime_episodesQueries.getEpisodeById(episodeId, profileId, AnimeEpisodeMapper::mapEpisode)
            }
        }
    }

    private suspend fun getPlaybackStateForBackup(profileId: Long, episodeId: Long): AnimePlaybackState? {
        return if (profileId == profileProvider.activeProfileId) {
            animePlaybackStateRepository.getByEpisodeId(episodeId)
        } else {
            handler.awaitOneOrNull {
                anime_playback_stateQueries.getByEpisodeId(profileId, episodeId, AnimePlaybackStateMapper::mapState)
            }
        }
    }

    private suspend fun getPlaybackPreferencesForBackup(profileId: Long, animeId: Long): AnimePlaybackPreferences? {
        return if (profileId == profileProvider.activeProfileId) {
            animePlaybackPreferencesRepository.getByAnimeId(animeId)
        } else {
            handler.awaitOneOrNull {
                anime_playback_preferencesQueries.getByAnimeId(
                    profileId,
                    animeId,
                    AnimePlaybackPreferencesMapper::mapPreferences,
                )
            }
        }
    }

    private suspend fun getHistoryForBackup(profileId: Long, animeId: Long): List<AnimeHistory> {
        return if (profileId == profileProvider.activeProfileId) {
            animeHistoryRepository.getHistoryByAnimeId(animeId)
        } else {
            handler.awaitList {
                anime_historyQueries.getHistoryByAnimeId(profileId, animeId, AnimeHistoryMapper::mapHistory)
            }
        }
    }
}

private fun AnimeTitle.toBackupAnime() = BackupAnime(
    url = this.url,
    title = this.title,
    displayName = this.displayName,
    originalTitle = this.originalTitle,
    country = this.country,
    studio = this.studio,
    producer = this.producer,
    director = this.director,
    writer = this.writer,
    year = this.year,
    duration = this.duration,
    description = this.description,
    genre = this.genre.orEmpty(),
    status = this.status,
    thumbnailUrl = this.thumbnailUrl,
    favorite = this.favorite,
    source = this.source,
    dateAdded = this.dateAdded,
    episodeFlags = this.episodeFlags,
    coverLastModified = this.coverLastModified,
    initialized = this.initialized,
    lastUpdate = this.lastUpdate,
    lastModifiedAt = this.lastModifiedAt,
    favoriteModifiedAt = this.favoriteModifiedAt,
    version = this.version,
    notes = this.notes,
)
