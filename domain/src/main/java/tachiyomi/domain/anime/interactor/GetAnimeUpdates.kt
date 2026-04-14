package tachiyomi.domain.anime.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.anime.repository.AnimeUpdatesRepository
import tachiyomi.domain.source.service.HiddenAnimeSourceIds
import java.time.Instant

class GetAnimeUpdates(
    private val repository: AnimeUpdatesRepository,
    private val hiddenAnimeSourceIds: HiddenAnimeSourceIds,
) {

    fun subscribe(
        instant: Instant,
        unread: Boolean?,
        started: Boolean?,
    ): Flow<List<AnimeUpdatesWithRelations>> {
        return combine(
            repository.subscribeAll(
                after = instant.toEpochMilli(),
                limit = 500,
                unread = unread,
                started = started,
            ),
            hiddenAnimeSourceIds.subscribe(),
            ::filterHiddenSources,
        )
    }

    private fun filterHiddenSources(
        updates: List<AnimeUpdatesWithRelations>,
        hiddenSources: Set<Long>,
    ): List<AnimeUpdatesWithRelations> {
        return updates.filterNot { it.sourceId in hiddenSources }
    }
}
