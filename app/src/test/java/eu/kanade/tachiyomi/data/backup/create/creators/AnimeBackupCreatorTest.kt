package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeHistory
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimePlaybackPreferences
import tachiyomi.domain.anime.model.AnimePlaybackState
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.PlayerQualityMode
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.anime.repository.AnimePlaybackPreferencesRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import java.util.Date

class AnimeBackupCreatorTest {

    private val handler = mockk<DatabaseHandler>()
    private val profileProvider = mockk<ActiveProfileProvider>()
    private val getAnimeCategories = mockk<GetAnimeCategories>()
    private val getMergedAnime = mockk<GetMergedAnime>()
    private val animeRepository = mockk<AnimeRepository>()
    private val animeEpisodeRepository = mockk<AnimeEpisodeRepository>()
    private val animeHistoryRepository = mockk<AnimeHistoryRepository>()
    private val animePlaybackPreferencesRepository = mockk<AnimePlaybackPreferencesRepository>()
    private val animePlaybackStateRepository = mockk<AnimePlaybackStateRepository>()

    private val creator = AnimeBackupCreator(
        handler = handler,
        profileProvider = profileProvider,
        getAnimeCategories = getAnimeCategories,
        getMergedAnime = getMergedAnime,
        animeRepository = animeRepository,
        animeEpisodeRepository = animeEpisodeRepository,
        animeHistoryRepository = animeHistoryRepository,
        animePlaybackPreferencesRepository = animePlaybackPreferencesRepository,
        animePlaybackStateRepository = animePlaybackStateRepository,
    )

    init {
        every { profileProvider.activeProfileId } returns 1L
        coEvery { getMergedAnime.awaitGroupByAnimeId(any()) } returns emptyList()
        coEvery { animeRepository.getAllAnimeByProfile(any()) } returns emptyList()
    }

    @Test
    fun `non-active profile backup reads child video data from requested profile`() = runTest {
        val video = AnimeTitle.create().copy(
            id = 100L,
            source = 10L,
            url = "/video",
            title = "Video",
            originalTitle = "Original Video",
            country = "Japan",
            studio = "Studio A",
            producer = "Producer A",
            director = "Director A",
            writer = "Writer A",
            year = "2026",
            duration = "24 min.",
            favorite = true,
            initialized = true,
            status = 1L,
        )
        val episode = AnimeEpisode.create().copy(
            id = 200L,
            animeId = video.id,
            url = "/episode-1",
            name = "Episode 1",
            watched = true,
            dateFetch = 123L,
            dateUpload = 456L,
            episodeNumber = 1.0,
            sourceOrder = 1L,
        )
        val playbackState = AnimePlaybackState(
            episodeId = episode.id,
            positionMs = 12_000L,
            durationMs = 24_000L,
            completed = false,
            lastWatchedAt = 789L,
        )
        val history = AnimeHistory(
            id = 300L,
            episodeId = episode.id,
            watchedAt = Date(999L),
            watchedDuration = 1_500L,
        )
        val playbackPreferences = AnimePlaybackPreferences(
            animeId = video.id,
            dubKey = "dub-1",
            streamKey = "stream-1",
            sourceQualityKey = "720p",
            playerQualityMode = PlayerQualityMode.AUTO,
            playerQualityHeight = null,
            updatedAt = 1_234L,
        )

        coEvery { handler.awaitList<Any>(false, any()) } returnsMany listOf(
            listOf(episode),
            listOf(history),
        )
        coEvery { handler.awaitOneOrNull<Any>(false, any()) } returnsMany listOf(
            playbackPreferences,
            episode,
            playbackState,
            episode,
        )

        val backup = creator.invoke(
            profileId = 2L,
            animes = listOf(video),
            options = BackupOptions(categories = false, chapters = true, history = true),
        )

        backup.size shouldBe 1
        backup.single().originalTitle shouldBe video.originalTitle
        backup.single().country shouldBe video.country
        backup.single().studio shouldBe video.studio
        backup.single().producer shouldBe video.producer
        backup.single().director shouldBe video.director
        backup.single().writer shouldBe video.writer
        backup.single().year shouldBe video.year
        backup.single().duration shouldBe video.duration
        backup.single().status shouldBe video.status
        backup.single().playbackPreferences?.dubKey shouldBe playbackPreferences.dubKey
        backup.single().playbackPreferences?.streamKey shouldBe playbackPreferences.streamKey
        backup.single().playbackPreferences?.sourceQualityKey shouldBe playbackPreferences.sourceQualityKey
        backup.single().episodes.single().url shouldBe episode.url
        backup.single().playbackStates.single().url shouldBe episode.url
        backup.single().playbackStates.single().positionMs shouldBe playbackState.positionMs
        backup.single().history.single().url shouldBe episode.url
        backup.single().history.single().lastWatched shouldBe history.watchedAt?.time
        backup.single().history.single().watchedDuration shouldBe history.watchedDuration

        coVerify(exactly = 2) { handler.awaitList<Any>(false, any()) }
        coVerify(exactly = 4) { handler.awaitOneOrNull<Any>(false, any()) }

        coVerify(exactly = 0) { animeEpisodeRepository.getEpisodesByAnimeId(any()) }
        coVerify(exactly = 0) { animeEpisodeRepository.getEpisodeByUrlAndAnimeId(any(), any()) }
        coVerify(exactly = 0) { animeEpisodeRepository.getEpisodeById(any()) }
        coVerify(exactly = 0) { animeHistoryRepository.getHistoryByAnimeId(any()) }
        coVerify(exactly = 0) { animePlaybackStateRepository.getByEpisodeId(any()) }
    }

    @Test
    fun `backup stores anime merge metadata`() = runTest {
        val targetAnime = AnimeTitle.create().copy(
            id = 10L,
            source = 100L,
            url = "/target",
            title = "Target",
        )
        val memberAnime = AnimeTitle.create().copy(
            id = 11L,
            source = 200L,
            url = "/member",
            title = "Member",
        )

        coEvery { animeRepository.getAllAnimeByProfile(1L) } returns listOf(targetAnime, memberAnime)
        coEvery { getMergedAnime.awaitGroupByAnimeId(memberAnime.id) } returns listOf(
            AnimeMerge(targetId = targetAnime.id, animeId = targetAnime.id, position = 0L),
            AnimeMerge(targetId = targetAnime.id, animeId = memberAnime.id, position = 1L),
        )
        coEvery { animePlaybackPreferencesRepository.getByAnimeId(memberAnime.id) } returns null

        val backup = creator.invoke(
            animes = listOf(memberAnime),
            options = BackupOptions(categories = false, chapters = false, history = false),
        )

        backup.single().mergeTargetSource shouldBe targetAnime.source
        backup.single().mergeTargetUrl shouldBe targetAnime.url
        backup.single().mergePosition shouldBe 1
    }
}
