package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class AnimeRestorer(
    private val handler: DatabaseHandler = Injekt.get(),
    private val profileProvider: ActiveProfileProvider = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val updateMergedAnime: UpdateMergedAnime = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animeHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val animePlaybackPreferencesRepository: AnimePlaybackPreferencesRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
) {

    private val pendingMerges = linkedMapOf<Pair<Long, String>, PendingMergeGroup>()

    suspend fun sortByNew(backupAnimes: List<BackupAnime>): List<BackupAnime> {
        val urlsBySource = animeRepository.getAllAnimeByProfile(profileProvider.activeProfileId)
            .groupBy({ it.source }, { it.url })

        return backupAnimes
            .sortedWith(
                compareBy<BackupAnime> { it.url in urlsBySource[it.source].orEmpty() }
                    .then(compareByDescending { it.lastModifiedAt }),
            )
    }

    suspend fun restore(
        backupAnime: BackupAnime,
        backupCategories: List<BackupCategory>,
    ) {
        handler.await(inTransaction = true) {
            val dbAnime = animeRepository.getAnimeByUrlAndSourceId(backupAnime.url, backupAnime.source)
            val anime = backupAnime.getAnimeImpl()
            val restoredAnime = if (dbAnime == null) {
                animeRepository.insertNetworkAnime(listOf(anime)).first()
            } else {
                animeRepository.update(
                    tachiyomi.domain.anime.model.AnimeTitleUpdate(
                        id = dbAnime.id,
                        source = anime.source,
                        url = anime.url,
                        title = anime.title,
                        displayName = anime.displayName,
                        originalTitle = anime.originalTitle,
                        country = anime.country,
                        studio = anime.studio,
                        producer = anime.producer,
                        director = anime.director,
                        writer = anime.writer,
                        year = anime.year,
                        duration = anime.duration,
                        description = anime.description,
                        genre = anime.genre,
                        status = anime.status,
                        thumbnailUrl = anime.thumbnailUrl,
                        favorite = dbAnime.favorite || anime.favorite,
                        initialized = dbAnime.initialized || anime.initialized,
                        lastUpdate = anime.lastUpdate,
                        dateAdded = anime.dateAdded,
                        episodeFlags = anime.episodeFlags,
                        coverLastModified = anime.coverLastModified,
                        version = maxOf(dbAnime.version, anime.version),
                        notes = anime.notes,
                    ),
                )
                animeRepository.getAnimeById(dbAnime.id)
            }

            restoreCategories(restoredAnime.id, backupAnime.categories, backupCategories)
            restoreEpisodes(restoredAnime.id, backupAnime)
            restoreHistory(restoredAnime.id, backupAnime)
            restorePlaybackPreferences(restoredAnime.id, backupAnime)
            enqueueMerge(restoredAnime, backupAnime)
        }
    }

    suspend fun restorePendingMerges() {
        pendingMerges.values.forEach { merge ->
            val targetAnime =
                animeRepository.getAnimeByUrlAndSourceId(merge.targetUrl, merge.targetSource) ?: return@forEach
            val orderedIds = merge.members
                .let { members ->
                    if (members.any { it.source == merge.targetSource && it.url == merge.targetUrl }) {
                        members
                    } else {
                        members +
                            PendingMergeMember(
                                source = merge.targetSource,
                                url = merge.targetUrl,
                                position = Int.MIN_VALUE,
                            )
                    }
                }
                .sortedBy { it.position }
                .mapNotNull { member ->
                    animeRepository.getAnimeByUrlAndSourceId(member.url, member.source)?.id
                }
                .distinct()

            if (targetAnime.id in orderedIds && orderedIds.size > 1) {
                updateMergedAnime.awaitMerge(targetAnime.id, orderedIds)
            }
        }
        pendingMerges.clear()
    }

    private suspend fun restoreCategories(
        animeId: Long,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategories = getAnimeCategories.await(animeId)
        val dbCategoriesByName = dbCategories.associateBy { it.name }
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val animeCategoryIds = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                dbCategoriesByName[backupCategory.name]?.id
            }
        }

        if (animeCategoryIds.isNotEmpty()) {
            animeRepository.setAnimeCategories(animeId, animeCategoryIds)
        }
    }

    private suspend fun restoreEpisodes(animeId: Long, backupAnime: BackupAnime) {
        val dbEpisodesByUrl = animeEpisodeRepository.getEpisodesByAnimeId(animeId).associateBy { it.url }

        val toInsert = mutableListOf<tachiyomi.domain.anime.model.AnimeEpisode>()
        val toUpdate = mutableListOf<AnimeEpisodeUpdate>()

        backupAnime.episodes.forEach { backupEpisode ->
            val episode = backupEpisode.toEpisodeImpl()
            val dbEpisode = dbEpisodesByUrl[episode.url]
            if (dbEpisode == null) {
                toInsert += episode.copy(animeId = animeId)
            } else {
                toUpdate += AnimeEpisodeUpdate(
                    id = dbEpisode.id,
                    watched = dbEpisode.watched || episode.watched,
                    completed = dbEpisode.completed || episode.completed,
                    version = maxOf(dbEpisode.version, episode.version),
                )
            }
        }

        if (toInsert.isNotEmpty()) {
            animeEpisodeRepository.addAll(toInsert)
        }
        if (toUpdate.isNotEmpty()) {
            animeEpisodeRepository.updateAll(toUpdate)
        }

        backupAnime.playbackStates.forEach { backupState ->
            val episode = animeEpisodeRepository.getEpisodeByUrlAndAnimeId(backupState.url, animeId) ?: return@forEach
            animePlaybackStateRepository.upsertAndSyncEpisodeState(
                AnimePlaybackState(
                    episodeId = episode.id,
                    positionMs = backupState.positionMs,
                    durationMs = backupState.durationMs,
                    completed = backupState.completed,
                    lastWatchedAt = backupState.lastWatchedAt,
                ),
            )
        }
    }

    private suspend fun restoreHistory(animeId: Long, backupAnime: BackupAnime) {
        backupAnime.history.forEach { history ->
            val episode = animeEpisodeRepository.getEpisodeByUrlAndAnimeId(history.url, animeId) ?: return@forEach
            val item = history.getHistoryImpl()
            animeHistoryRepository.upsertHistory(
                AnimeHistoryUpdate(
                    episodeId = episode.id,
                    watchedAt = item.watchedAt ?: Date(0L),
                    sessionWatchedDuration = item.watchedDuration,
                ),
            )
        }
    }

    private suspend fun restorePlaybackPreferences(animeId: Long, backupAnime: BackupAnime) {
        val preferences = backupAnime.playbackPreferences?.toPlaybackPreferences() ?: return
        animePlaybackPreferencesRepository.upsert(
            AnimePlaybackPreferences(
                animeId = animeId,
                dubKey = preferences.dubKey,
                streamKey = preferences.streamKey,
                sourceQualityKey = preferences.sourceQualityKey,
                playerQualityMode = preferences.playerQualityMode,
                playerQualityHeight = preferences.playerQualityHeight,
                subtitleOffsetX = preferences.subtitleOffsetX,
                subtitleOffsetY = preferences.subtitleOffsetY,
                subtitleTextSize = preferences.subtitleTextSize,
                subtitleTextColor = preferences.subtitleTextColor,
                subtitleBackgroundColor = preferences.subtitleBackgroundColor,
                subtitleBackgroundOpacity = preferences.subtitleBackgroundOpacity,
                updatedAt = preferences.updatedAt,
            ),
        )
    }

    private fun enqueueMerge(anime: tachiyomi.domain.anime.model.AnimeTitle, backupAnime: BackupAnime) {
        val targetSource = backupAnime.mergeTargetSource ?: return
        val targetUrl = backupAnime.mergeTargetUrl ?: return
        val position = backupAnime.mergePosition ?: return

        val key = targetSource to targetUrl
        val group = pendingMerges.getOrPut(key) {
            PendingMergeGroup(
                targetSource = targetSource,
                targetUrl = targetUrl,
                members = mutableListOf(),
            )
        }
        group.members.removeAll { it.source == anime.source && it.url == anime.url }
        group.members.add(PendingMergeMember(source = anime.source, url = anime.url, position = position))
        if (targetSource == anime.source && targetUrl == anime.url) {
            group.members.removeAll { it.source == targetSource && it.url == targetUrl }
            group.members.add(PendingMergeMember(source = targetSource, url = targetUrl, position = position))
        }
    }

    private data class PendingMergeGroup(
        val targetSource: Long,
        val targetUrl: String,
        val members: MutableList<PendingMergeMember>,
    )

    private data class PendingMergeMember(
        val source: Long,
        val url: String,
        val position: Int,
    )
}
