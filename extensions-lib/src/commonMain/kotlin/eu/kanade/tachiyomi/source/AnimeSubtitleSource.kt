package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.source.model.VideoSubtitle

/**
 * Optional interface for anime sources that can expose external subtitle tracks.
 */
interface AnimeSubtitleSource {

    /**
     * Resolve external subtitle tracks for an episode and selection.
     */
    suspend fun getSubtitles(
        episode: SEpisode,
        selection: VideoPlaybackSelection = VideoPlaybackSelection(),
    ): List<VideoSubtitle>
}
