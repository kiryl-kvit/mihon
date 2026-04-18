package tachiyomi.domain.anime.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeTitle

class MergedEpisodeSequenceTest {

    @Test
    fun `merged display order follows member order and visible descending sort`() {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )

        val episodes = listOf(
            episode(id = 101, animeId = 1, episodeNumber = 1.0),
            episode(id = 203, animeId = 2, episodeNumber = 3.0),
            episode(id = 202, animeId = 2, episodeNumber = 2.0),
            episode(id = 201, animeId = 2, episodeNumber = 1.0),
        )

        episodes.sortedForMergedDisplay(anime).map(AnimeEpisode::id) shouldBe listOf(101L, 203L, 202L, 201L)
    }

    @Test
    fun `merged reading order follows descending group traversal and canonical ascending episodes`() {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )

        val episodes = listOf(
            episode(id = 101, animeId = 1, episodeNumber = 1.0),
            episode(id = 203, animeId = 2, episodeNumber = 3.0),
            episode(id = 202, animeId = 2, episodeNumber = 2.0),
            episode(id = 201, animeId = 2, episodeNumber = 1.0),
        )

        episodes.sortedForReading(anime).map(AnimeEpisode::id) shouldBe listOf(201L, 202L, 203L, 101L)
    }

    @Test
    fun `merged reading order keeps top to bottom groups when sort is ascending`() {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            episodeFlags = AnimeTitle.EPISODE_SORT_ASC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )

        val episodes = listOf(
            episode(id = 101, animeId = 1, episodeNumber = 1.0),
            episode(id = 203, animeId = 2, episodeNumber = 3.0),
            episode(id = 202, animeId = 2, episodeNumber = 2.0),
            episode(id = 201, animeId = 2, episodeNumber = 1.0),
        )

        episodes.sortedForReading(anime).map(AnimeEpisode::id) shouldBe listOf(101L, 201L, 202L, 203L)
    }

    @Test
    fun `non merged episodes keep watcher ascending order`() {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )

        val episodes = listOf(
            episode(id = 103, animeId = 1, episodeNumber = 3.0),
            episode(id = 102, animeId = 1, episodeNumber = 2.0),
            episode(id = 101, animeId = 1, episodeNumber = 1.0),
        )

        episodes.sortedForReading(anime).map(AnimeEpisode::id) shouldBe listOf(101L, 102L, 103L)
    }

    @Test
    fun `merged display order ignores removed member ids that no longer have episodes`() {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )

        val episodes = listOf(
            episode(id = 101, animeId = 1, episodeNumber = 1.0),
            episode(id = 301, animeId = 3, episodeNumber = 1.0),
        )

        episodes.sortedForMergedDisplay(anime, mergedAnimeIds = listOf(1L, 2L, 3L)).map(AnimeEpisode::id) shouldBe
            listOf(101L, 301L)
    }

    @Test
    fun `merged reading order ignores removed member ids that no longer have episodes`() {
        val anime = AnimeTitle.create().copy(
            id = 1L,
            episodeFlags = AnimeTitle.EPISODE_SORT_DESC or AnimeTitle.EPISODE_SORTING_NUMBER,
        )

        val episodes = listOf(
            episode(id = 101, animeId = 1, episodeNumber = 1.0),
            episode(id = 301, animeId = 3, episodeNumber = 1.0),
        )

        episodes.sortedForReading(anime, mergedAnimeIds = listOf(1L, 2L, 3L)).map(AnimeEpisode::id) shouldBe
            listOf(301L, 101L)
    }

    private fun episode(
        id: Long,
        animeId: Long,
        episodeNumber: Double,
    ): AnimeEpisode {
        return AnimeEpisode.create().copy(
            id = id,
            animeId = animeId,
            episodeNumber = episodeNumber,
            name = "Episode $id",
            url = "/episode/$id",
        )
    }
}
