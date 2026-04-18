package tachiyomi.domain.anime.interactor

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.anime.repository.MergedAnimeRepository
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason
import tachiyomi.domain.manga.service.DuplicatePreferences

class GetDuplicateLibraryAnimeTest {

    @Test
    fun `collapses merged library members into one candidate`() = runTest {
        val description = "A striker program turns forwards into monsters chasing the same impossible dream."
        val current = anime(id = 1, title = "Blue Lock", description = description)
        val target = anime(id = 10, title = "Blue Lock", description = description, favorite = true)
        val member = anime(id = 11, title = "Blue Lock Official", description = description, favorite = true)
        val animeRepository = FakeAnimeRepository(listOf(current, target, member))
        val episodeRepository = FakeAnimeEpisodeRepository(
            episodes = listOf(
                episode(id = 100, animeId = 10),
                episode(id = 101, animeId = 10),
                episode(id = 102, animeId = 11),
            ),
        )
        val mergedRepository = FakeMergedAnimeRepository(
            merges = listOf(
                AnimeMerge(targetId = 10, animeId = 10, position = 0),
                AnimeMerge(targetId = 10, animeId = 11, position = 1),
            ),
        )
        val interactor = GetDuplicateLibraryAnime(
            animeRepository = animeRepository,
            animeEpisodeRepository = episodeRepository,
            mergedAnimeRepository = mergedRepository,
            duplicatePreferences = DuplicatePreferences(InMemoryPreferenceStore()).apply {
                extendedDuplicateDetectionEnabled.set(true)
            },
        )

        val results = interactor(current)

        results shouldHaveSize 1
        results.single().anime.id shouldBe 10L
        results.single().episodeCount shouldBe 3L
        results.single().reasons shouldContain DuplicateMangaMatchReason.TITLE
    }

    @Test
    fun `does not match unrelated entries without tracker support`() = runTest {
        val current =
            anime(id = 1, title = "Alpha Series", description = "A short unique summary for the current show.")
        val library = anime(
            id = 2,
            title = "Totally Different Show",
            description = "A completely different story with no meaningful overlap at all.",
            favorite = true,
        )
        val interactor = GetDuplicateLibraryAnime(
            animeRepository = FakeAnimeRepository(listOf(current, library)),
            animeEpisodeRepository = FakeAnimeEpisodeRepository(),
            mergedAnimeRepository = FakeMergedAnimeRepository(),
            duplicatePreferences = DuplicatePreferences(InMemoryPreferenceStore()).apply {
                extendedDuplicateDetectionEnabled.set(true)
            },
        )

        interactor(current) shouldBe emptyList()
    }

    private fun anime(
        id: Long,
        title: String,
        description: String? = null,
        favorite: Boolean = false,
    ): AnimeTitle {
        return AnimeTitle.create().copy(
            id = id,
            source = id,
            favorite = favorite,
            title = title,
            description = description,
            initialized = true,
            url = "/anime/$id",
            lastModifiedAt = id,
        )
    }

    private fun episode(id: Long, animeId: Long): AnimeEpisode {
        return AnimeEpisode.create().copy(
            id = id,
            animeId = animeId,
            url = "/episode/$id",
        )
    }

    private class FakeAnimeRepository(
        private val anime: List<AnimeTitle>,
    ) : AnimeRepository {
        override suspend fun getAnimeById(id: Long): AnimeTitle = anime.first { it.id == id }
        override suspend fun getAnimeByIdAsFlow(id: Long): Flow<AnimeTitle> = flowOf(anime.first { it.id == id })
        override suspend fun getAnimeByUrlAndSourceId(
            url: String,
            sourceId: Long,
        ): AnimeTitle? = anime.firstOrNull {
            it.url ==
                url &&
                it.source == sourceId
        }
        override fun getAnimeByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<AnimeTitle?> {
            return flowOf(anime.firstOrNull { it.url == url && it.source == sourceId })
        }
        override suspend fun getFavorites(): List<AnimeTitle> = anime.filter { it.favorite }
        override fun getFavoritesAsFlow(): Flow<List<AnimeTitle>> = flowOf(anime.filter { it.favorite })
        override suspend fun getAllAnimeByProfile(profileId: Long): List<AnimeTitle> = anime
        override suspend fun updateDisplayName(animeId: Long, displayName: String?): Boolean = true
        override suspend fun update(update: tachiyomi.domain.anime.model.AnimeTitleUpdate): Boolean = true
        override suspend fun updateAll(
            animeUpdates: List<tachiyomi.domain.anime.model.AnimeTitleUpdate>,
        ): Boolean = true
        override suspend fun insertNetworkAnime(animes: List<AnimeTitle>): List<AnimeTitle> = animes
        override suspend fun setAnimeCategories(animeId: Long, categoryIds: List<Long>) = Unit
    }

    private class FakeAnimeEpisodeRepository(
        private val episodes: List<AnimeEpisode> = emptyList(),
    ) : AnimeEpisodeRepository {
        override suspend fun addAll(episodes: List<AnimeEpisode>): List<AnimeEpisode> = episodes
        override suspend fun update(episodeUpdate: tachiyomi.domain.anime.model.AnimeEpisodeUpdate) = Unit
        override suspend fun updateAll(episodeUpdates: List<tachiyomi.domain.anime.model.AnimeEpisodeUpdate>) = Unit
        override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) = Unit
        override suspend fun getEpisodesByAnimeId(animeId: Long): List<AnimeEpisode> = episodes.filter {
            it.animeId ==
                animeId
        }
        override fun getEpisodesByAnimeIdAsFlow(
            animeId: Long,
        ): Flow<List<AnimeEpisode>> = flowOf(
            episodes.filter {
                it.animeId ==
                    animeId
            },
        )
        override fun getEpisodesByAnimeIdsAsFlow(
            animeIds: List<Long>,
        ): Flow<List<AnimeEpisode>> = flowOf(
            episodes.filter {
                it.animeId in
                    animeIds
            },
        )
        override suspend fun getEpisodeById(id: Long): AnimeEpisode? = episodes.firstOrNull { it.id == id }
        override suspend fun getEpisodeByUrlAndAnimeId(
            url: String,
            animeId: Long,
        ): AnimeEpisode? = episodes.firstOrNull {
            it.url ==
                url &&
                it.animeId == animeId
        }
    }

    private class FakeMergedAnimeRepository(
        private val merges: List<AnimeMerge> = emptyList(),
    ) : MergedAnimeRepository {
        override suspend fun getAll(): List<AnimeMerge> = merges
        override fun subscribeAll(): Flow<List<AnimeMerge>> = flowOf(merges)
        override suspend fun getGroupByAnimeId(animeId: Long): List<AnimeMerge> {
            val targetId = merges.firstOrNull { it.animeId == animeId }?.targetId ?: return emptyList()
            return merges.filter { it.targetId == targetId }
        }
        override fun subscribeGroupByAnimeId(animeId: Long): Flow<List<AnimeMerge>> {
            val targetId = merges.firstOrNull { it.animeId == animeId }?.targetId
            return flowOf(targetId?.let { id -> merges.filter { it.targetId == id } }.orEmpty())
        }
        override suspend fun getGroupByTargetId(targetAnimeId: Long): List<AnimeMerge> = merges.filter {
            it.targetId ==
                targetAnimeId
        }
        override suspend fun getTargetId(animeId: Long): Long? = merges.firstOrNull { it.animeId == animeId }?.targetId
        override fun subscribeTargetId(
            animeId: Long,
        ): Flow<Long?> = flowOf(merges.firstOrNull { it.animeId == animeId }?.targetId)
        override suspend fun upsertGroup(targetAnimeId: Long, orderedAnimeIds: List<Long>) = Unit
        override suspend fun removeMembers(targetAnimeId: Long, animeIds: List<Long>) = Unit
        override suspend fun deleteGroup(targetAnimeId: Long) = Unit
    }
}
