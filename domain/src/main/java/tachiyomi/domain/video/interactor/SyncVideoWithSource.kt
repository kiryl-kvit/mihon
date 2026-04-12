package tachiyomi.domain.video.interactor

import mihon.domain.video.model.copyFrom
import mihon.domain.video.model.toDomainEpisode
import mihon.domain.video.model.toSVideo
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.service.VideoSourceManager
import tachiyomi.domain.video.model.VideoEpisode
import tachiyomi.domain.video.model.VideoEpisodeUpdate
import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.model.VideoTitleUpdate
import tachiyomi.domain.video.repository.VideoEpisodeRepository
import tachiyomi.domain.video.repository.VideoRepository
import java.time.Instant

class SyncVideoWithSource(
    private val videoRepository: VideoRepository,
    private val videoEpisodeRepository: VideoEpisodeRepository,
    private val videoSourceManager: VideoSourceManager,
) {

    suspend operator fun invoke(video: VideoTitle) {
        val source = videoSourceManager.get(video.source) ?: throw SourceNotInstalledException()
        val networkVideo = source.getVideoDetails(video.toSVideo())

        val videoUpdate = VideoTitleUpdate(
            id = video.id,
            title = networkVideo.title.takeIf { it.isNotBlank() && it != video.title },
            description = networkVideo.description.takeIf { !it.isNullOrBlank() && it != video.description },
            genre = networkVideo.getGenres().takeIf { !it.isNullOrEmpty() && it != video.genre },
            thumbnailUrl = networkVideo.thumbnail_url.takeIf { !it.isNullOrBlank() && it != video.thumbnailUrl },
            initialized = true.takeIf { !video.initialized },
        )
        if (videoUpdate != VideoTitleUpdate(id = video.id) && !videoRepository.update(videoUpdate)) {
            error("Failed to update video ${video.id}")
        }

        val existingEpisodes = videoEpisodeRepository.getEpisodesByVideoId(video.id)
            .associateBy { it.url }
        val now = Instant.now().toEpochMilli()
        val episodesToInsert = mutableListOf<VideoEpisode>()
        val episodesToUpdate = mutableListOf<VideoEpisodeUpdate>()

        source.getEpisodeList(networkVideo)
            .distinctBy { it.url }
            .forEachIndexed { index, sourceEpisode ->
                val sourceOrder = index.toLong()
                val existingEpisode = existingEpisodes[sourceEpisode.url]
                if (existingEpisode == null) {
                    episodesToInsert += sourceEpisode.toDomainEpisode(
                        videoId = video.id,
                        sourceOrder = sourceOrder,
                        dateFetch = now,
                    )
                    return@forEachIndexed
                }

                val updatedEpisode = existingEpisode.copyFrom(sourceEpisode, sourceOrder)
                val episodeUpdate = VideoEpisodeUpdate(
                    id = existingEpisode.id,
                    name = updatedEpisode.name.takeIf { it != existingEpisode.name },
                    dateUpload = updatedEpisode.dateUpload.takeIf { it != existingEpisode.dateUpload },
                    episodeNumber = updatedEpisode.episodeNumber.takeIf { it != existingEpisode.episodeNumber },
                    sourceOrder = updatedEpisode.sourceOrder.takeIf { it != existingEpisode.sourceOrder },
                )
                if (episodeUpdate != VideoEpisodeUpdate(id = existingEpisode.id)) {
                    episodesToUpdate += episodeUpdate
                }
            }

        if (episodesToInsert.isNotEmpty()) {
            videoEpisodeRepository.addAll(episodesToInsert)
        }
        if (episodesToUpdate.isNotEmpty()) {
            videoEpisodeRepository.updateAll(episodesToUpdate)
        }
    }
}
