package mihon.domain.video.model

import eu.kanade.tachiyomi.source.model.SVideo
import tachiyomi.domain.video.model.VideoTitle

fun SVideo.toDomainVideo(sourceId: Long): VideoTitle {
    return VideoTitle.create().copy(
        source = sourceId,
        url = url,
        title = title,
        description = description,
        genre = getGenres(),
        thumbnailUrl = thumbnail_url,
        initialized = initialized,
    )
}
