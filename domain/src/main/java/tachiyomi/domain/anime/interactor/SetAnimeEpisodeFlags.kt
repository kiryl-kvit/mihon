package tachiyomi.domain.anime.interactor

import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.repository.AnimeRepository

class SetAnimeEpisodeFlags(
    private val animeRepository: AnimeRepository,
) {

    suspend fun awaitSetUnwatchedFilter(anime: AnimeTitle, flag: Long): Boolean {
        return animeRepository.update(
            AnimeTitleUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, AnimeTitle.EPISODE_UNWATCHED_MASK),
            ),
        )
    }

    suspend fun awaitSetStartedFilter(anime: AnimeTitle, flag: Long): Boolean {
        return animeRepository.update(
            AnimeTitleUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, AnimeTitle.EPISODE_STARTED_MASK),
            ),
        )
    }

    suspend fun awaitSetDisplayMode(anime: AnimeTitle, flag: Long): Boolean {
        return animeRepository.update(
            AnimeTitleUpdate(
                id = anime.id,
                episodeFlags = anime.episodeFlags.setFlag(flag, AnimeTitle.EPISODE_DISPLAY_MASK),
            ),
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(anime: AnimeTitle, flag: Long): Boolean {
        val newFlags = anime.episodeFlags.let {
            if (anime.sorting == flag) {
                val orderFlag = if (anime.sortDescending()) {
                    AnimeTitle.EPISODE_SORT_ASC
                } else {
                    AnimeTitle.EPISODE_SORT_DESC
                }
                it.setFlag(orderFlag, AnimeTitle.EPISODE_SORT_DIR_MASK)
            } else {
                it
                    .setFlag(flag, AnimeTitle.EPISODE_SORTING_MASK)
                    .setFlag(AnimeTitle.EPISODE_SORT_ASC, AnimeTitle.EPISODE_SORT_DIR_MASK)
            }
        }
        return animeRepository.update(
            AnimeTitleUpdate(
                id = anime.id,
                episodeFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetAllFlags(
        animeId: Long,
        unwatchedFilter: Long,
        startedFilter: Long,
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
    ): Boolean {
        return animeRepository.update(
            AnimeTitleUpdate(
                id = animeId,
                episodeFlags = 0L.setFlag(unwatchedFilter, AnimeTitle.EPISODE_UNWATCHED_MASK)
                    .setFlag(startedFilter, AnimeTitle.EPISODE_STARTED_MASK)
                    .setFlag(sortingMode, AnimeTitle.EPISODE_SORTING_MASK)
                    .setFlag(sortingDirection, AnimeTitle.EPISODE_SORT_DIR_MASK)
                    .setFlag(displayMode, AnimeTitle.EPISODE_DISPLAY_MASK),
            ),
        )
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
