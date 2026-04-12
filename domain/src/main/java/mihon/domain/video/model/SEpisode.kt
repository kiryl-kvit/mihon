package mihon.domain.video.model

import eu.kanade.tachiyomi.source.model.SEpisode
import tachiyomi.domain.video.model.VideoEpisode

fun SEpisode.toDomainEpisode(
    videoId: Long,
    sourceOrder: Long,
    dateFetch: Long,
): VideoEpisode {
    return VideoEpisode.create().copy(
        videoId = videoId,
        url = url,
        name = name.ifBlank { url },
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        dateUpload = date_upload,
        episodeNumber = episode_number.toDouble(),
    )
}

fun VideoEpisode.copyFrom(sourceEpisode: SEpisode, sourceOrder: Long): VideoEpisode {
    return copy(
        url = sourceEpisode.url,
        name = sourceEpisode.name.ifBlank { sourceEpisode.url },
        dateUpload = sourceEpisode.date_upload,
        episodeNumber = sourceEpisode.episode_number.toDouble(),
        sourceOrder = sourceOrder,
    )
}
