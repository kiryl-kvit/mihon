package tachiyomi.domain.video.interactor

import tachiyomi.domain.video.model.VideoTitle
import tachiyomi.domain.video.repository.VideoRepository

class NetworkToLocalVideo(
    private val videoRepository: VideoRepository,
) {

    suspend operator fun invoke(video: VideoTitle): VideoTitle {
        return invoke(listOf(video)).single()
    }

    suspend operator fun invoke(videos: List<VideoTitle>): List<VideoTitle> {
        return videoRepository.insertNetworkVideo(videos)
    }
}
