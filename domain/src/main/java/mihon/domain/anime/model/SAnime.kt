package mihon.domain.anime.model

import eu.kanade.tachiyomi.source.model.SAnime
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun SAnime.toDomainAnime(sourceId: Long): AnimeTitle {
    val libraryPreferences = Injekt.get<LibraryPreferences>()
    val episodeFlags = 0L
        .setFlag(libraryPreferences.filterEpisodeByUnwatched.get(), AnimeTitle.EPISODE_UNWATCHED_MASK)
        .setFlag(libraryPreferences.filterEpisodeByStarted.get(), AnimeTitle.EPISODE_STARTED_MASK)
        .setFlag(libraryPreferences.sortEpisodeBySourceOrNumber.get(), AnimeTitle.EPISODE_SORTING_MASK)
        .setFlag(libraryPreferences.sortEpisodeByAscendingOrDescending.get(), AnimeTitle.EPISODE_SORT_DIR_MASK)
        .setFlag(libraryPreferences.displayEpisodeByNameOrNumber.get(), AnimeTitle.EPISODE_DISPLAY_MASK)

    return AnimeTitle.create().copy(
        source = sourceId,
        url = url,
        title = title,
        episodeFlags = episodeFlags,
        coverLastModified = 0L,
        originalTitle = original_title,
        country = country,
        studio = studio,
        producer = producer,
        director = director,
        writer = writer,
        year = year,
        duration = duration,
        description = description,
        genre = getGenres(),
        status = status.toLong(),
        thumbnailUrl = thumbnail_url,
        initialized = initialized,
    )
}

private fun Long.setFlag(flag: Long, mask: Long): Long {
    return this and mask.inv() or (flag and mask)
}
