package tachiyomi.domain.anime.interactor

import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.library.service.LibraryPreferences

class SetAnimeDefaultEpisodeFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setAnimeEpisodeFlags: SetAnimeEpisodeFlags,
) {

    suspend fun await(anime: AnimeTitle) {
        withNonCancellableContext {
            with(libraryPreferences) {
                setAnimeEpisodeFlags.awaitSetAllFlags(
                    animeId = anime.id,
                    unwatchedFilter = filterEpisodeByUnwatched.get(),
                    startedFilter = filterEpisodeByStarted.get(),
                    sortingMode = sortEpisodeBySourceOrNumber.get(),
                    sortingDirection = sortEpisodeByAscendingOrDescending.get(),
                    displayMode = displayEpisodeByNameOrNumber.get(),
                )
            }
        }
    }
}
