package eu.kanade.tachiyomi.ui.anime

import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.service.episodeSortComparator
import tachiyomi.domain.anime.service.groupedByMergedMember
import tachiyomi.domain.anime.service.sortedForMergedDisplay
import tachiyomi.domain.anime.service.sortedForReading
import tachiyomi.domain.manga.model.applyFilter

internal data class AnimeEpisodeDisplayData(
    val episodes: List<AnimeEpisode>,
    val playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
    val primaryEpisodeId: Long?,
    val episodeListItems: List<AnimeEpisodeListEntry>,
)

internal fun buildAnimeEpisodeDisplayData(
    anime: AnimeTitle,
    episodes: List<AnimeEpisode>,
    memberIds: List<Long>,
    memberTitleById: Map<Long, String>,
    playbackStates: List<AnimePlaybackState>,
): AnimeEpisodeDisplayData {
    val playbackStateByEpisodeId = playbackStates.associateBy(AnimePlaybackState::episodeId)
    val filteredEpisodes = episodes.filterEpisodesForDisplay(anime, playbackStateByEpisodeId)
    val displayedEpisodes = filteredEpisodes.sortEpisodesForDisplay(anime, memberIds)

    return AnimeEpisodeDisplayData(
        episodes = displayedEpisodes,
        playbackStateByEpisodeId = playbackStateByEpisodeId,
        primaryEpisodeId = selectPrimaryEpisodeIdForDisplay(
            episodes = filteredEpisodes.sortedForReading(anime, memberIds),
            playbackStateByEpisodeId = playbackStateByEpisodeId,
        ),
        episodeListItems = buildAnimeEpisodeListItems(
            episodes = displayedEpisodes,
            memberIds = memberIds,
            memberTitleById = memberTitleById,
            fallbackTitle = anime.displayTitle,
        ),
    )
}

internal fun buildAnimeEpisodeListItems(
    episodes: List<AnimeEpisode>,
    memberIds: List<Long>,
    memberTitleById: Map<Long, String>,
    fallbackTitle: String,
): List<AnimeEpisodeListEntry> {
    if (memberIds.size <= 1) {
        return episodes.map(AnimeEpisodeListEntry::Item)
    }

    return buildList {
        episodes.groupedByMergedMember(memberIds).forEach { (memberId, memberEpisodes) ->
            add(
                AnimeEpisodeListEntry.MemberHeader(
                    animeId = memberId,
                    title = memberTitleById[memberId].orEmpty().ifBlank { fallbackTitle },
                ),
            )
            addAll(memberEpisodes.map(AnimeEpisodeListEntry::Item))
        }
    }
}

private fun List<AnimeEpisode>.filterEpisodesForDisplay(
    anime: AnimeTitle,
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
): List<AnimeEpisode> {
    val unwatchedFilter = anime.unwatchedFilter
    val startedFilter = anime.startedFilter

    return asSequence()
        .filter { episode ->
            applyFilter(unwatchedFilter) { !episode.completed }
        }
        .filter { episode ->
            applyFilter(startedFilter) {
                val playbackState = playbackStateByEpisodeId[episode.id]
                playbackState?.let { !it.completed && it.positionMs > 0L && it.durationMs > 0L } == true ||
                    episode.watched || episode.completed
            }
        }
        .toList()
}

private fun List<AnimeEpisode>.sortEpisodesForDisplay(
    anime: AnimeTitle,
    memberIds: List<Long>,
): List<AnimeEpisode> {
    return if (memberIds.size > 1) {
        sortedForMergedDisplay(anime, memberIds)
    } else {
        sortedWith(anime.episodeSortComparator())
    }
}

private fun selectPrimaryEpisodeIdForDisplay(
    episodes: List<AnimeEpisode>,
    playbackStateByEpisodeId: Map<Long, AnimePlaybackState>,
): Long? {
    val inProgressEpisode = episodes
        .asSequence()
        .mapNotNull { episode ->
            val playbackState = playbackStateByEpisodeId[episode.id] ?: return@mapNotNull null
            if (playbackState.completed || playbackState.positionMs <= 0L) return@mapNotNull null
            episode to playbackState
        }
        .maxByOrNull { (_, playbackState) -> playbackState.lastWatchedAt }
        ?.first
    if (inProgressEpisode != null) {
        return inProgressEpisode.id
    }

    return episodes.firstOrNull { !it.completed }?.id ?: episodes.firstOrNull()?.id
}
