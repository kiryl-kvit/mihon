package eu.kanade.domain.anime.model

import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.manga.model.MangaCover

fun AnimeTitle.toMangaCover(): MangaCover {
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        url = thumbnailUrl,
        lastModified = coverLastModified,
    )
}

fun AnimeTitle.episodesFiltered(): Boolean {
    return unwatchedFilter != TriState.DISABLED || startedFilter != TriState.DISABLED
}
