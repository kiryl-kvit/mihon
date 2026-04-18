package tachiyomi.domain.anime.interactor

import mihon.domain.anime.model.copyFrom
import mihon.domain.anime.model.toDomainEpisode
import mihon.domain.anime.model.toSAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.service.AnimeSourceManager
import java.time.Instant
import kotlin.math.abs

class SyncAnimeWithSource(
    private val animeRepository: AnimeRepository,
    private val animeEpisodeRepository: AnimeEpisodeRepository,
    private val animeSourceManager: AnimeSourceManager,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) {

    suspend operator fun invoke(anime: AnimeTitle): SyncResult {
        val source = animeSourceManager.get(anime.source) ?: throw SourceNotInstalledException()
        val networkAnime = source.getAnimeDetails(anime.toSAnime())
        val thumbnailUrl = networkAnime.thumbnail_url?.takeIf { it.isNotEmpty() }
        val coverLastModified = when {
            thumbnailUrl == null -> null
            anime.thumbnailUrl == thumbnailUrl && anime.coverLastModified != 0L -> null
            else -> now()
        }

        val animeUpdate = AnimeTitleUpdate(
            id = anime.id,
            title = networkAnime.title.takeIf { it.isNotBlank() && it != anime.title },
            originalTitle = networkAnime.original_title.takeIf { !it.isNullOrBlank() && it != anime.originalTitle },
            country = networkAnime.country.takeIf { !it.isNullOrBlank() && it != anime.country },
            studio = networkAnime.studio.takeIf { !it.isNullOrBlank() && it != anime.studio },
            producer = networkAnime.producer.takeIf { !it.isNullOrBlank() && it != anime.producer },
            director = networkAnime.director.takeIf { !it.isNullOrBlank() && it != anime.director },
            writer = networkAnime.writer.takeIf { !it.isNullOrBlank() && it != anime.writer },
            year = networkAnime.year.takeIf { !it.isNullOrBlank() && it != anime.year },
            duration = networkAnime.duration.takeIf { !it.isNullOrBlank() && it != anime.duration },
            description = networkAnime.description.takeIf { !it.isNullOrBlank() && it != anime.description },
            genre = networkAnime.getGenres().takeIf { !it.isNullOrEmpty() && it != anime.genre },
            status = networkAnime.status.toLong().takeIf { it != anime.status },
            thumbnailUrl = thumbnailUrl.takeIf { it != anime.thumbnailUrl },
            coverLastModified = coverLastModified,
            initialized = true.takeIf { !anime.initialized },
        )
        if (animeUpdate != AnimeTitleUpdate(id = anime.id) && !animeRepository.update(animeUpdate)) {
            error("Failed to update anime ${anime.id}")
        }

        val sourceEpisodes = source.getEpisodeList(networkAnime)
            .distinctBy { it.url }
            .mapIndexed { index, sourceEpisode ->
                IndexedSourceEpisode(
                    episode = sourceEpisode,
                    sourceOrder = index.toLong(),
                    resolvedName = sourceEpisode.name.ifBlank { sourceEpisode.url },
                    episodeNumber = sourceEpisode.episode_number.toDouble(),
                )
            }
        val currentSourceUrls = sourceEpisodes
            .asSequence()
            .map { it.episode.url }
            .toSet()
        val existingEpisodes = animeEpisodeRepository.getEpisodesByAnimeId(anime.id)
        val now = now()
        val representativeEpisodes = existingEpisodes
            .groupBy { it.sourceOrder }
            .values
            .map { episodes ->
                episodes.maxWithOrNull(
                    compareBy<AnimeEpisode>(
                        { stateRank(it) },
                        { if (it.url in currentSourceUrls) 1 else 0 },
                        AnimeEpisode::lastModifiedAt,
                        AnimeEpisode::id,
                    ),
                )!!
            }
        val episodesToInsert = mutableListOf<AnimeEpisode>()
        val episodesToUpdate = mutableListOf<AnimeEpisodeUpdate>()
        val matchedEpisodeIds = mutableSetOf<Long>()
        val remainingEpisodes = representativeEpisodes.toMutableList()
        var maxSeenUploadDate = 0L

        fun resolveDateUpload(sourceDateUpload: Long, existingDateUpload: Long? = null): Long {
            return when {
                sourceDateUpload != 0L -> {
                    maxSeenUploadDate = maxOf(maxSeenUploadDate, sourceDateUpload)
                    sourceDateUpload
                }
                existingDateUpload != null && existingDateUpload != 0L -> existingDateUpload
                else -> if (maxSeenUploadDate == 0L) now else maxSeenUploadDate
            }
        }

        fun matchEpisode(
            sourceEpisode: IndexedSourceEpisode,
            predicate: (AnimeEpisode) -> Boolean,
        ): AnimeEpisode? {
            val candidates = remainingEpisodes.filter(predicate)
            val exactSourceOrderCandidate = candidates.firstOrNull { it.sourceOrder == sourceEpisode.sourceOrder }
            val matchedEpisode = exactSourceOrderCandidate
                ?: candidates.singleOrNull()
                ?: candidates.minByOrNull { abs(it.sourceOrder - sourceEpisode.sourceOrder) }
            if (matchedEpisode != null) {
                remainingEpisodes.remove(matchedEpisode)
            }
            return matchedEpisode
        }

        sourceEpisodes.forEach { sourceEpisode ->
            val sourceDateUpload = sourceEpisode.episode.date_upload
            val existingEpisode = matchEpisode(sourceEpisode) { it.url == sourceEpisode.episode.url }
                ?: matchEpisode(sourceEpisode) {
                    it.name == sourceEpisode.resolvedName && it.episodeNumber == sourceEpisode.episodeNumber
                }
                ?: matchEpisode(sourceEpisode) {
                    sourceEpisode.episodeNumber >= 0.0 && it.episodeNumber == sourceEpisode.episodeNumber
                }
                ?: matchEpisode(sourceEpisode) { it.name == sourceEpisode.resolvedName }
                ?: matchEpisode(sourceEpisode) {
                    sourceEpisode.episodeNumber < 0.0 && sourceEpisode.resolvedName == sourceEpisode.episode.url &&
                        it.sourceOrder == sourceEpisode.sourceOrder
                }

            if (existingEpisode == null) {
                val episodeToInsert = sourceEpisode.episode.toDomainEpisode(
                    animeId = anime.id,
                    sourceOrder = sourceEpisode.sourceOrder,
                    dateFetch = now,
                )
                episodesToInsert += episodeToInsert.copy(
                    dateUpload = resolveDateUpload(sourceDateUpload = sourceDateUpload),
                )
                return@forEach
            }

            matchedEpisodeIds += existingEpisode.id

            val updatedEpisode = existingEpisode.copyFrom(sourceEpisode.episode, sourceEpisode.sourceOrder)
                .copy(
                    dateUpload = resolveDateUpload(
                        sourceDateUpload = sourceDateUpload,
                        existingDateUpload = existingEpisode.dateUpload,
                    ),
                )
            val episodeUpdate = AnimeEpisodeUpdate(
                id = existingEpisode.id,
                url = updatedEpisode.url.takeIf { it != existingEpisode.url },
                name = updatedEpisode.name.takeIf { it != existingEpisode.name },
                dateUpload = updatedEpisode.dateUpload.takeIf { it != existingEpisode.dateUpload },
                episodeNumber = updatedEpisode.episodeNumber.takeIf { it != existingEpisode.episodeNumber },
                sourceOrder = updatedEpisode.sourceOrder.takeIf { it != existingEpisode.sourceOrder },
            )
            if (episodeUpdate != AnimeEpisodeUpdate(id = existingEpisode.id)) {
                episodesToUpdate += episodeUpdate
            }
        }

        val episodesToRemove = existingEpisodes
            .filterNot { it.id in matchedEpisodeIds }
            .map(AnimeEpisode::id)

        if (episodesToRemove.isNotEmpty()) {
            animeEpisodeRepository.removeEpisodesWithIds(episodesToRemove)
        }

        if (episodesToUpdate.isNotEmpty()) {
            animeEpisodeRepository.updateAll(episodesToUpdate)
        }

        val insertedEpisodes =
            if (episodesToInsert.isNotEmpty()) {
                animeEpisodeRepository.addAll(episodesToInsert)
            } else {
                emptyList()
            }

        val hasEpisodeChanges =
            insertedEpisodes.isNotEmpty() || episodesToUpdate.isNotEmpty() || episodesToRemove.isNotEmpty()
        if (hasEpisodeChanges) {
            val timestamp = now()
            if (!animeRepository.update(AnimeTitleUpdate(id = anime.id, lastUpdate = timestamp))) {
                error("Failed to update anime ${anime.id}")
            }
        }

        return SyncResult(
            insertedEpisodes = insertedEpisodes,
            updatedEpisodes = episodesToUpdate.size,
            removedEpisodes = episodesToRemove.size,
            hasMetadataChanges = animeUpdate != AnimeTitleUpdate(id = anime.id),
        )
    }

    private fun stateRank(episode: AnimeEpisode): Int {
        return when {
            episode.completed -> 2
            episode.watched -> 1
            else -> 0
        }
    }

    private data class IndexedSourceEpisode(
        val episode: eu.kanade.tachiyomi.source.model.SEpisode,
        val sourceOrder: Long,
        val resolvedName: String,
        val episodeNumber: Double,
    )

    data class SyncResult(
        val insertedEpisodes: List<AnimeEpisode>,
        val updatedEpisodes: Int,
        val removedEpisodes: Int,
        val hasMetadataChanges: Boolean,
    ) {
        val insertedEpisodesCount: Int
            get() = insertedEpisodes.size

        val hasChanges: Boolean
            get() = insertedEpisodes.isNotEmpty() || updatedEpisodes > 0 || removedEpisodes > 0
    }
}
