package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SAnimeScheduleEpisode

/**
 * Optional capability for sources that expose an episode airing/release schedule.
 *
 * Schedule entries may include future episodes that are not yet available in the episode list.
 */
interface AnimeScheduleSource : AnimeSource {

    suspend fun getEpisodeSchedule(anime: SAnime): List<SAnimeScheduleEpisode>
}
