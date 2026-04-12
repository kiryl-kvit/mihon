package eu.kanade.domain.video.model

import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.video.model.VideoTitle

fun VideoTitle.toMangaCover(): MangaCover {
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        url = thumbnailUrl,
        lastModified = lastModifiedAt,
    )
}
