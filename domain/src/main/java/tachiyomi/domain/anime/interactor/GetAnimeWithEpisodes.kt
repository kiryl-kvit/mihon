package tachiyomi.domain.anime.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.anime.service.sortedForMergedDisplay

class GetAnimeWithEpisodes(
    private val animeRepository: AnimeRepository,
    private val animeEpisodeRepository: AnimeEpisodeRepository,
    private val mergedAnimeRepository: MergedAnimeRepository,
) {

    suspend fun subscribe(
        id: Long,
        bypassMerge: Boolean = false,
    ): Flow<Pair<AnimeTitle, List<AnimeEpisode>>> {
        return combine(
            animeRepository.getAnimeByIdAsFlow(id),
            mergedAnimeRepository.subscribeGroupByAnimeId(id),
        ) { anime, merges ->
            anime to if (bypassMerge) emptyList() else merges
        }
            .flatMapLatest { (anime, merges) ->
                if (merges.isEmpty()) {
                    animeEpisodeRepository.getEpisodesByAnimeIdAsFlow(id)
                        .map { anime to it }
                } else {
                    mergedEpisodesAsFlow(anime, merges)
                        .map { anime to it }
                }
            }
    }

    suspend fun awaitAnime(id: Long): AnimeTitle {
        return animeRepository.getAnimeById(id)
    }

    suspend fun awaitEpisodes(
        id: Long,
        bypassMerge: Boolean = false,
    ): List<AnimeEpisode> {
        val merges = if (bypassMerge) {
            emptyList()
        } else {
            mergedAnimeRepository.getGroupByAnimeId(id)
        }

        return if (merges.isEmpty()) {
            animeEpisodeRepository.getEpisodesByAnimeId(id)
        } else {
            val anime = animeRepository.getAnimeById(id)
            mergedEpisodes(anime, merges)
        }
    }

    private suspend fun mergedEpisodes(
        anime: AnimeTitle,
        merges: List<AnimeMerge>,
    ): List<AnimeEpisode> {
        return mergeEpisodes(
            anime = anime,
            episodeLists = merges.sortedBy { it.position }
                .map { merge -> animeEpisodeRepository.getEpisodesByAnimeId(merge.animeId) },
        )
    }

    private fun mergedEpisodesAsFlow(
        anime: AnimeTitle,
        merges: List<AnimeMerge>,
    ): Flow<List<AnimeEpisode>> {
        val orderedMerges = merges.sortedBy { it.position }
        return combine(
            orderedMerges.map { merge ->
                animeEpisodeRepository.getEpisodesByAnimeIdAsFlow(merge.animeId)
            },
        ) { episodeLists ->
            mergeEpisodes(anime, episodeLists.asIterable())
        }
    }

    private fun mergeEpisodes(
        anime: AnimeTitle,
        episodeLists: Iterable<List<AnimeEpisode>>,
    ): List<AnimeEpisode> {
        val mergedAnimeIds = episodeLists.mapNotNull { it.firstOrNull()?.animeId }
        return episodeLists.flatten()
            .sortedForMergedDisplay(anime, mergedAnimeIds)
    }
}
