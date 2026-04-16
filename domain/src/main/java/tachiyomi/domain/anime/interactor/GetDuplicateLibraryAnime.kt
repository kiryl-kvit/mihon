package tachiyomi.domain.anime.interactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.DuplicateAnimeCandidate
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.manga.interactor.DuplicateConfig
import tachiyomi.domain.manga.interactor.DuplicateEntryMetadata
import tachiyomi.domain.manga.interactor.DuplicateLibraryCandidate
import tachiyomi.domain.manga.interactor.DuplicateLibrarySupport
import tachiyomi.domain.manga.interactor.toDuplicateConfig
import tachiyomi.domain.manga.service.DuplicatePreferences

class GetDuplicateLibraryAnime(
    private val animeRepository: AnimeRepository,
    private val animeEpisodeRepository: AnimeEpisodeRepository,
    private val mergedAnimeRepository: MergedAnimeRepository,
    private val duplicatePreferences: DuplicatePreferences,
) {

    suspend operator fun invoke(anime: AnimeTitle): List<DuplicateAnimeCandidate> {
        val libraryAnime = animeRepository.getFavorites()
        val merges = mergedAnimeRepository.getAll()
        val episodeCounts = buildEpisodeCounts(libraryAnime.map(AnimeTitle::id))
        val config = duplicatePreferences.toDuplicateConfig()

        return withContext(Dispatchers.Default) {
            detectDuplicates(anime, libraryAnime, merges, episodeCounts, config)
        }
    }

    fun subscribe(
        anime: Flow<AnimeTitle>,
        scope: CoroutineScope,
    ): StateFlow<List<DuplicateAnimeCandidate>> {
        val duplicateConfigFlow = combine(
            duplicatePreferences.extendedDuplicateDetectionEnabled.changes(),
            duplicatePreferences.minimumMatchScore.changes(),
            combine(
                duplicatePreferences.descriptionWeight.changes(),
                duplicatePreferences.authorWeight.changes(),
                duplicatePreferences.artistWeight.changes(),
                duplicatePreferences.coverWeight.changes(),
            ) { _, _, _, _ -> Unit },
            combine(
                duplicatePreferences.genreWeight.changes(),
                duplicatePreferences.statusWeight.changes(),
                duplicatePreferences.chapterCountWeight.changes(),
                duplicatePreferences.titleWeight.changes(),
            ) { _, _, _, _ -> Unit },
            duplicatePreferences.titleExclusionPatterns.changes(),
        ) { _, _, _, _, _ ->
            duplicatePreferences.toDuplicateConfig()
        }

        val snapshot = combine(
            animeRepository.getFavoritesAsFlow(),
            mergedAnimeRepository.subscribeAll(),
            duplicateConfigFlow,
        ) { libraryAnime, merges, config ->
            Triple(libraryAnime, merges, config)
        }
            .flatMapLatest { (libraryAnime, merges, config) ->
                val libraryAnimeIds = libraryAnime.map(AnimeTitle::id)
                val episodeCountsFlow = if (libraryAnimeIds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    animeEpisodeRepository.getEpisodesByAnimeIdsAsFlow(libraryAnimeIds)
                        .map(::buildEpisodeCounts)
                }

                episodeCountsFlow.map { episodeCounts ->
                    DuplicateLibrarySnapshot(
                        libraryAnime = libraryAnime,
                        merges = merges,
                        episodeCounts = episodeCounts,
                        config = config,
                    )
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = DuplicateLibrarySnapshot(config = duplicatePreferences.toDuplicateConfig()),
            )

        return combine(anime, snapshot) { currentAnime, duplicateLibrarySnapshot ->
            detectDuplicates(
                anime = currentAnime,
                libraryAnime = duplicateLibrarySnapshot.libraryAnime,
                merges = duplicateLibrarySnapshot.merges,
                episodeCounts = duplicateLibrarySnapshot.episodeCounts,
                config = duplicateLibrarySnapshot.config,
            )
        }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )
    }

    private fun detectDuplicates(
        anime: AnimeTitle,
        libraryAnime: List<AnimeTitle>,
        merges: List<AnimeMerge>,
        episodeCounts: Map<Long, Long>,
        config: DuplicateConfig,
    ): List<DuplicateAnimeCandidate> {
        val collapsedLibrary = collapseLibraryAnime(libraryAnime, merges, episodeCounts)
        val excludedIds = buildExcludedIds(anime.id, merges)

        return DuplicateLibrarySupport.detectDuplicates(
            currentEntry = anime.toDuplicateEntryMetadata(episodeCounts[anime.id]),
            libraryEntries = collapsedLibrary,
            excludedIds = excludedIds,
            trackerDuplicateIds = emptySet(),
            config = config,
        ).map { match ->
            DuplicateAnimeCandidate(
                anime = match.item,
                episodeCount = match.count,
                cheapScore = match.cheapScore,
                scoreMax = match.scoreMax,
                score = match.score,
                reasons = match.reasons,
                contentSignature = match.contentSignature,
            )
        }
    }

    private fun buildExcludedIds(
        animeId: Long,
        merges: List<AnimeMerge>,
    ): Set<Long> {
        val mergeTargetId = merges.firstOrNull { it.animeId == animeId }?.targetId
        if (mergeTargetId == null) {
            return setOf(animeId)
        }

        return merges.asSequence()
            .filter { it.targetId == mergeTargetId }
            .mapTo(linkedSetOf(mergeTargetId)) { it.animeId }
    }

    private fun collapseLibraryAnime(
        libraryAnime: List<AnimeTitle>,
        merges: List<AnimeMerge>,
        episodeCounts: Map<Long, Long>,
    ): List<DuplicateLibraryCandidate<AnimeTitle>> {
        val byId = libraryAnime.associateBy(AnimeTitle::id)
        val groupedMerges = merges.groupBy(AnimeMerge::targetId)

        val collapsed = mutableListOf<DuplicateLibraryCandidate<AnimeTitle>>()
        val consumedIds = mutableSetOf<Long>()

        groupedMerges.forEach { (targetId, group) ->
            val members = group.sortedBy(AnimeMerge::position)
                .mapNotNull { byId[it.animeId] }
            if (members.size <= 1) return@forEach

            val target = members.firstOrNull { it.id == targetId } ?: members.first()
            val memberIds = members.map(AnimeTitle::id)
            consumedIds += memberIds
            collapsed += DuplicateLibraryCandidate(
                item = target,
                sortTitle = target.displayTitle,
                memberIds = memberIds,
                memberEntries = members.map { member ->
                    member.toDuplicateEntryMetadata(episodeCounts[member.id])
                },
                count = memberIds.sumOf { episodeCounts[it] ?: 0L },
                contentSignature = members.maxOfOrNull(AnimeTitle::lastModifiedAt) ?: target.lastModifiedAt,
            )
        }

        collapsed += libraryAnime.filterNot { it.id in consumedIds }
            .map { anime ->
                DuplicateLibraryCandidate(
                    item = anime,
                    sortTitle = anime.displayTitle,
                    memberIds = listOf(anime.id),
                    memberEntries = listOf(anime.toDuplicateEntryMetadata(episodeCounts[anime.id])),
                    count = episodeCounts[anime.id] ?: 0L,
                    contentSignature = anime.lastModifiedAt,
                )
            }

        return collapsed
    }

    private suspend fun buildEpisodeCounts(animeIds: List<Long>): Map<Long, Long> {
        return animeIds.associateWith { animeId ->
            animeEpisodeRepository.getEpisodesByAnimeId(animeId).size.toLong()
        }
    }

    private fun buildEpisodeCounts(episodes: List<AnimeEpisode>): Map<Long, Long> {
        return episodes.groupingBy(AnimeEpisode::animeId)
            .eachCount()
            .mapValues { it.value.toLong() }
    }

    private fun AnimeTitle.toDuplicateEntryMetadata(episodeCount: Long?): DuplicateEntryMetadata {
        return DuplicateEntryMetadata(
            id = id,
            title = title,
            description = description,
            primaryCreator = director,
            secondaryCreator = studio,
            genres = genre,
            status = status,
            count = episodeCount,
        )
    }

    private data class DuplicateLibrarySnapshot(
        val libraryAnime: List<AnimeTitle> = emptyList(),
        val merges: List<AnimeMerge> = emptyList(),
        val episodeCounts: Map<Long, Long> = emptyMap(),
        val config: DuplicateConfig,
    )

    private companion object {
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
