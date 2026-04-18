package tachiyomi.domain.anime.service

import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.util.orderedPresentIds

fun List<AnimeEpisode>.sortedForMergedDisplay(
    anime: AnimeTitle,
    mergedAnimeIds: List<Long> = map(AnimeEpisode::animeId).distinct(),
): List<AnimeEpisode> {
    val episodeSort = anime.episodeSortComparator()
    if (mergedAnimeIds.size <= 1) {
        return sortedWith(episodeSort)
    }

    return mergedAnimeIds.orderedPresentIds(this, AnimeEpisode::animeId)
        .flatMap { animeId ->
            asSequence()
                .filter { it.animeId == animeId }
                .sortedWith(episodeSort)
                .toList()
        }
}

fun List<AnimeEpisode>.sortedForReading(
    anime: AnimeTitle,
    mergedAnimeIds: List<Long> = map(AnimeEpisode::animeId).distinct(),
): List<AnimeEpisode> {
    val readingSort = anime.episodeSortComparator(sortDescending = false)
    if (mergedAnimeIds.size <= 1) {
        return sortedWith(readingSort)
    }

    val orderedMergedIds = mergedAnimeIds.orderedPresentIds(this, AnimeEpisode::animeId).let { ids ->
        if (anime.sortDescending()) ids.asReversed() else ids
    }
    return orderedMergedIds.flatMap { animeId ->
        asSequence()
            .filter { it.animeId == animeId }
            .sortedWith(readingSort)
            .toList()
    }
}

fun List<AnimeEpisode>.groupedByMergedMember(
    mergedAnimeIds: List<Long> = map(AnimeEpisode::animeId).distinct(),
): List<Pair<Long, List<AnimeEpisode>>> {
    return mergedAnimeIds.orderedPresentIds(this, AnimeEpisode::animeId)
        .mapNotNull { animeId ->
            val memberEpisodes = filter { it.animeId == animeId }
            memberEpisodes.takeIf { it.isNotEmpty() }?.let { animeId to it }
        }
}

fun AnimeTitle.episodeSortComparator(
    sortDescending: Boolean = this.sortDescending(),
): Comparator<AnimeEpisode> {
    val comparator = when (sorting) {
        AnimeTitle.EPISODE_SORTING_NUMBER -> compareBy<AnimeEpisode> {
            it.episodeNumber.takeIf { number -> number >= 0.0 } ?: Double.MAX_VALUE
        }.thenBy { it.sourceOrder }
        AnimeTitle.EPISODE_SORTING_UPLOAD_DATE -> compareBy<AnimeEpisode> {
            it.dateUpload.takeIf { date -> date > 0L } ?: Long.MAX_VALUE
        }.thenBy { it.sourceOrder }
        AnimeTitle.EPISODE_SORTING_ALPHABET -> compareBy<AnimeEpisode>({
            it.name.ifBlank { it.url }
        }, { it.sourceOrder })
        else -> compareBy<AnimeEpisode> { it.sourceOrder }
    }

    return if (sortDescending) comparator.reversed() else comparator
}
