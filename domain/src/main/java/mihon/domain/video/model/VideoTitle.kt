package mihon.domain.video.model

import eu.kanade.tachiyomi.source.model.SVideo
import tachiyomi.domain.video.model.VideoTitle

fun VideoTitle.toSVideo(): SVideo = SVideo.create().also {
    it.url = url
    it.title = title
    it.description = description
    it.genre = genre?.joinToString()
    it.thumbnail_url = thumbnailUrl
    it.initialized = initialized
}
