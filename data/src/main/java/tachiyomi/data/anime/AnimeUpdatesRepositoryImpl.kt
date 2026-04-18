package tachiyomi.data.anime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.anime.repository.AnimeUpdatesRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeUpdatesRepositoryImpl(
    private val databaseHandler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : AnimeUpdatesRepository {

    override fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
    ): Flow<List<AnimeUpdatesWithRelations>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            databaseHandler.subscribeToList {
                anime_updatesQueries.getAnimeRecentUpdatesWithFilters(
                    profileId = profileId,
                    after = after,
                    limit = limit,
                    unread = unread?.toLong(),
                    started = started?.toLong(),
                    mapper = AnimeUpdatesMapper::mapUpdatesWithRelations,
                )
            }
        }
    }

    override suspend fun awaitWithWatched(
        watched: Boolean,
        after: Long,
        limit: Long,
    ): List<AnimeUpdatesWithRelations> {
        return databaseHandler.awaitList {
            anime_updatesQueries.getAnimeUpdatesByWatchedStatus(
                profileId = profileProvider.activeProfileId,
                watched = watched,
                after = after,
                limit = limit,
                mapper = AnimeUpdatesMapper::mapUpdatesWithRelations,
            )
        }
    }

    override fun subscribeWithWatched(
        watched: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<AnimeUpdatesWithRelations>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            databaseHandler.subscribeToList {
                anime_updatesQueries.getAnimeUpdatesByWatchedStatus(
                    profileId = profileId,
                    watched = watched,
                    after = after,
                    limit = limit,
                    mapper = AnimeUpdatesMapper::mapUpdatesWithRelations,
                )
            }
        }
    }
}
