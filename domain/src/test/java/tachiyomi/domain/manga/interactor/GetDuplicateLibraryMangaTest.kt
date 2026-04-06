package tachiyomi.domain.manga.interactor

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.MergedMangaRepository
import tachiyomi.domain.manga.service.DuplicatePreferences
import tachiyomi.domain.manga.service.DuplicateTitleExclusions
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

class GetDuplicateLibraryMangaTest {

    private val mangaRepository = mockk<MangaRepository>()
    private val mergedMangaRepository = mockk<MergedMangaRepository>()
    private val trackRepository = mockk<TrackRepository>()
    private val duplicatePreferences = mockk<DuplicatePreferences>()
    private val extendedEnabledPreference = MutablePreference(true)
    private val minimumMatchScorePreference = MutablePreference(DuplicatePreferences.DEFAULT_MINIMUM_MATCH_SCORE)
    private val descriptionWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_DESCRIPTION_WEIGHT)
    private val authorWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_AUTHOR_WEIGHT)
    private val artistWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_ARTIST_WEIGHT)
    private val coverWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_COVER_WEIGHT)
    private val genreWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_GENRE_WEIGHT)
    private val statusWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_STATUS_WEIGHT)
    private val chapterCountWeightPreference =
        MutablePreference(DuplicatePreferences.DEFAULT_CHAPTER_COUNT_WEIGHT)
    private val titleWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_TITLE_WEIGHT)
    private val titleExclusionPatternsPreference = MutablePreference(DuplicateTitleExclusions.defaultPatterns)
    private val libraryFlow = MutableStateFlow<List<LibraryManga>>(emptyList())
    private val mergeFlow = MutableStateFlow<List<MangaMerge>>(emptyList())
    private val trackFlow = MutableStateFlow<List<Track>>(emptyList())

    private val getDuplicateLibraryManga = GetDuplicateLibraryManga(
        mangaRepository = mangaRepository,
        mergedMangaRepository = mergedMangaRepository,
        trackRepository = trackRepository,
        duplicatePreferences = duplicatePreferences,
    )

    init {
        every { duplicatePreferences.extendedDuplicateDetectionEnabled } returns extendedEnabledPreference
        every { duplicatePreferences.minimumMatchScore } returns minimumMatchScorePreference
        every { duplicatePreferences.descriptionWeight } returns descriptionWeightPreference
        every { duplicatePreferences.authorWeight } returns authorWeightPreference
        every { duplicatePreferences.artistWeight } returns artistWeightPreference
        every { duplicatePreferences.coverWeight } returns coverWeightPreference
        every { duplicatePreferences.genreWeight } returns genreWeightPreference
        every { duplicatePreferences.statusWeight } returns statusWeightPreference
        every { duplicatePreferences.chapterCountWeight } returns chapterCountWeightPreference
        every { duplicatePreferences.titleWeight } returns titleWeightPreference
        every { duplicatePreferences.titleExclusionPatterns } returns titleExclusionPatternsPreference
        every { duplicatePreferences.getWeightBudget() } answers {
            DuplicatePreferences.DuplicateWeightBudget(
                description = descriptionWeightPreference.get(),
                author = authorWeightPreference.get(),
                artist = artistWeightPreference.get(),
                cover = coverWeightPreference.get(),
                genre = genreWeightPreference.get(),
                status = statusWeightPreference.get(),
                chapterCount = chapterCountWeightPreference.get(),
                title = titleWeightPreference.get(),
            ).normalized()
        }
        every { mangaRepository.getLibraryMangaAsFlow() } returns libraryFlow.asStateFlow()
        every { mergedMangaRepository.subscribeAll() } returns mergeFlow.asStateFlow()
        every { trackRepository.getTracksAsFlow() } returns trackFlow.asStateFlow()
    }

    @Test
    fun `returns strong match for normalized same title`() = runTest {
        val description =
            "The mage Frieren journeys onward after the demon king falls and reflects on the lives she outlived."
        val current = manga(
            id = 1,
            title = "Frieren: Beyond Journey's End",
            description = description,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "Frieren Beyond Journeys End",
                author = "Kanehito Yamada",
                description = description,
            ),
            totalChapters = 140,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        val results = getDuplicateLibraryManga(current)

        results shouldHaveSize 1
        results.single().manga.id shouldBe 2L
        results.single().score shouldBeGreaterThanOrEqual 40
        results.single().reasons shouldContain DuplicateMangaMatchReason.TITLE
        results.single().cheapScore shouldBe results.single().score
    }

    @Test
    fun `does not double count exact normalized title weight`() = runTest {
        val description =
            "The mage Frieren journeys onward after the demon king falls and reflects on the lives she outlived."
        titleWeightPreference.set(20)
        descriptionWeightPreference.set(30)
        authorWeightPreference.set(0)
        artistWeightPreference.set(0)
        genreWeightPreference.set(0)
        statusWeightPreference.set(0)
        chapterCountWeightPreference.set(0)
        val current = manga(
            id = 1,
            title = "One-Punch Man",
            description = description,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "One Punch Man",
                author = "Different Author",
                description = description,
            ),
            totalChapters = 140,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        val result = getDuplicateLibraryManga(current).single()

        result.reasons shouldContain DuplicateMangaMatchReason.TITLE
        result.score shouldBe 50
        result.score shouldBeLessThan 70
    }

    @Test
    fun `uses creator and status markers to boost likely duplicate`() = runTest {
        descriptionWeightPreference.set(34)
        authorWeightPreference.set(11)
        artistWeightPreference.set(7)
        coverWeightPreference.set(14)
        genreWeightPreference.set(9)
        statusWeightPreference.set(4)
        chapterCountWeightPreference.set(4)
        titleWeightPreference.set(17)
        val description =
            "Noor keeps parrying impossible attacks and accidentally becomes the strongest while believing he is weak."
        val current = manga(
            id = 1,
            title = "I Parry Everything",
            author = "Nabeshiki",
            status = 1,
            genre = listOf("Action", "Fantasy"),
            description = description,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "I Parry Everything: What Do You Mean I'm the Strongest",
                author = "Nabeshiki",
                status = 1,
                genre = listOf("Fantasy", "Action"),
                description = description,
            ),
            totalChapters = 40,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        val result = getDuplicateLibraryManga(current).single()

        result.reasons shouldContain DuplicateMangaMatchReason.TITLE
        result.reasons shouldContain DuplicateMangaMatchReason.AUTHOR
        result.reasons shouldContain DuplicateMangaMatchReason.STATUS
        result.reasons shouldContain DuplicateMangaMatchReason.GENRE
    }

    @Test
    fun `collapses merged library members into one candidate`() = runTest {
        val description = "Japan assembles an elite striker program to create the world's most selfish goal scorer."
        val current = manga(id = 1, title = "Blue Lock", description = description)
        val target =
            libraryManga(manga = manga(id = 10, title = "Blue Lock", description = description), totalChapters = 20)
        val member =
            libraryManga(
                manga = manga(id = 11, title = "Blue Lock (Official)", description = description),
                totalChapters = 22,
            )
        val merges = listOf(
            MangaMerge(targetId = 10, mangaId = 10, position = 0),
            MangaMerge(targetId = 10, mangaId = 11, position = 1),
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(target, member)
        coEvery { mergedMangaRepository.getAll() } returns merges

        val results = getDuplicateLibraryManga(current)

        results shouldHaveSize 1
        results.single().manga.id shouldBe 10L
        results.single().chapterCount shouldBe 42L
    }

    @Test
    fun `configured exclusions are applied on both titles in extended mode`() = runTest {
        titleExclusionPatternsPreference.set(listOf("[*]"))

        val current = manga(
            id = 1,
            title = "One Punch Man [English] [Scanlator]",
            description = LONG_DESCRIPTION,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "One Punch Man [Spanish] [Another Group]",
                description = LONG_DESCRIPTION,
            ),
            totalChapters = 140,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        val result = getDuplicateLibraryManga(current).single()

        result.reasons shouldContain DuplicateMangaMatchReason.TITLE
        result.manga.id shouldBe 2L
    }

    @Test
    fun `configured exclusions can remove non bracketed suffixes`() = runTest {
        titleExclusionPatternsPreference.set(listOf(" - Season *", " Vol. *"))

        val current = manga(
            id = 1,
            title = "One Punch Man - Season 2 Vol. 1",
            description = LONG_DESCRIPTION,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "One Punch Man",
                description = LONG_DESCRIPTION,
            ),
            totalChapters = 140,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        getDuplicateLibraryManga(current).single().manga.id shouldBe 2L
    }

    @Test
    fun `subscribe updates when title exclusions change`() = runTest {
        descriptionWeightPreference.set(0)
        authorWeightPreference.set(0)
        artistWeightPreference.set(0)
        coverWeightPreference.set(0)
        genreWeightPreference.set(0)
        statusWeightPreference.set(0)
        chapterCountWeightPreference.set(0)
        titleWeightPreference.set(40)
        titleExclusionPatternsPreference.set(emptyList())

        val current = manga(id = 1, title = "One Punch Man [English]")
        val duplicate = libraryManga(
            manga = manga(id = 2, title = "One Punch Man [Spanish]"),
            totalChapters = 140,
        )

        libraryFlow.value = listOf(duplicate)
        mergeFlow.value = emptyList()
        trackFlow.value = emptyList()

        val results = getDuplicateLibraryManga.subscribe(flowOf(current), backgroundScope)
        val emissions = mutableListOf<List<tachiyomi.domain.manga.model.DuplicateMangaCandidate>>()
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            results.take(2).toList(emissions)
        }

        titleExclusionPatternsPreference.set(listOf("[*]"))

        testScheduler.advanceUntilIdle()
        job.join()

        emissions shouldHaveSize 2
        emissions.first() shouldBe emptyList()
        emissions.last().single().manga.id shouldBe 2L
    }

    @Test
    fun `ignores low similarity titles`() = runTest {
        val current = manga(
            id = 1,
            title = "Frieren",
            description =
            "An elf mage travels after the end of the hero's journey and learns what life means.",
        )
        val unrelated = libraryManga(
            manga = manga(
                id = 2,
                title = "One Piece",
                description =
                "A rubber pirate sails the seas to find a legendary treasure and become king of the pirates.",
            ),
            totalChapters = 1000,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(unrelated)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        getDuplicateLibraryManga(current) shouldBe emptyList()
    }

    @Test
    fun `ignores title overlap from common words alone`() = runTest {
        val current = manga(
            id = 1,
            title = "The Two Lovebirds Are Hiding Their Fangs: The Story of a Couple with a Crazy Gap",
        )
        val unrelated = listOf(
            libraryManga(
                manga = manga(
                    id = 2,
                    title = "The Bastard of Swordborne",
                ),
                totalChapters = 75,
            ),
            libraryManga(
                manga = manga(
                    id = 3,
                    title = "The Patron of Villains",
                ),
                totalChapters = 24,
            ),
        )

        coEvery { mangaRepository.getLibraryManga() } returns unrelated
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        getDuplicateLibraryManga(current) shouldBe emptyList()
    }

    @Test
    fun `matches on strong description despite different title`() = runTest {
        descriptionWeightPreference.set(34)
        authorWeightPreference.set(11)
        artistWeightPreference.set(7)
        coverWeightPreference.set(14)
        genreWeightPreference.set(9)
        statusWeightPreference.set(4)
        chapterCountWeightPreference.set(4)
        titleWeightPreference.set(17)
        val description =
            "Encrid dreamed of becoming a knight, but those words poisoned his childhood and he keeps returning to today."
        val current = manga(
            id = 1,
            title = "Eternally Regressing Knight",
            author = "Kanara",
            status = 1,
            genre = listOf("Action", "Fantasy", "Regression"),
            description = description,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "The Knight Only Lives Today",
                author = "Ian",
                status = 1,
                genre = listOf("Action", "Fantasy", "Manhwa"),
                description = description,
            ),
            totalChapters = 105,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        val result = getDuplicateLibraryManga(current).single()

        result.reasons shouldContain DuplicateMangaMatchReason.DESCRIPTION
        result.reasons shouldContain DuplicateMangaMatchReason.STATUS
        result.reasons shouldContain DuplicateMangaMatchReason.GENRE
        result.score shouldBeGreaterThanOrEqual 32
    }

    @Test
    fun `filters weak matches below configured minimum score`() = runTest {
        descriptionWeightPreference.set(0)
        authorWeightPreference.set(0)
        artistWeightPreference.set(0)
        coverWeightPreference.set(0)
        genreWeightPreference.set(0)
        statusWeightPreference.set(0)
        chapterCountWeightPreference.set(0)
        titleWeightPreference.set(40)
        minimumMatchScorePreference.set(45)

        val current = manga(
            id = 1,
            title = "One-Punch Man",
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "One Punch Man",
            ),
            totalChapters = 140,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()

        getDuplicateLibraryManga(current) shouldBe emptyList()

        minimumMatchScorePreference.set(40)

        val result = getDuplicateLibraryManga(current).single()

        result.reasons shouldContain DuplicateMangaMatchReason.TITLE
        result.score shouldBe 40
    }

    @Test
    fun `keeps tracker-only matches even below content thresholds`() = runTest {
        val current = manga(id = 1, title = "Alpha Series", description = "Short description")
        val duplicate = libraryManga(manga = manga(id = 2, title = "Totally Different Title"), totalChapters = 12)
        val trackerId = 7L
        val remoteId = 99L

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()
        trackFlow.value = listOf(
            track(id = 1, mangaId = 1, trackerId = trackerId, remoteId = remoteId),
            track(id = 2, mangaId = 2, trackerId = trackerId, remoteId = remoteId),
        )

        val result = getDuplicateLibraryManga(current).single()

        result.reasons shouldContain DuplicateMangaMatchReason.TRACKER
        result.score shouldBe 58
    }

    @Test
    fun `subscribe updates when library entries change`() = runTest {
        val current = manga(id = 1, title = "Alpha Series", description = "Short description")
        val trackedDuplicate =
            libraryManga(manga = manga(id = 2, title = "Totally Different Title"), totalChapters = 12)
        val trackerId = 7L
        val remoteId = 99L

        libraryFlow.value = emptyList()
        mergeFlow.value = emptyList()
        trackFlow.value = listOf(
            track(id = 1, mangaId = 1, trackerId = trackerId, remoteId = remoteId),
        )

        val results = getDuplicateLibraryManga.subscribe(flowOf(current), backgroundScope)
        val emissions = mutableListOf<List<tachiyomi.domain.manga.model.DuplicateMangaCandidate>>()
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            results.take(2).toList(emissions)
        }

        libraryFlow.value = listOf(trackedDuplicate)
        trackFlow.value = listOf(
            track(id = 1, mangaId = 1, trackerId = trackerId, remoteId = remoteId),
            track(id = 2, mangaId = 2, trackerId = trackerId, remoteId = remoteId),
        )

        testScheduler.advanceUntilIdle()
        job.join()

        emissions shouldHaveSize 2
        emissions.first() shouldBe emptyList()
        emissions.last() shouldHaveSize 1
        emissions.last().single().manga.id shouldBe 2L
    }

    @Test
    fun `subscribe updates when minimum score changes`() = runTest {
        descriptionWeightPreference.set(0)
        authorWeightPreference.set(0)
        artistWeightPreference.set(0)
        coverWeightPreference.set(0)
        genreWeightPreference.set(0)
        statusWeightPreference.set(0)
        chapterCountWeightPreference.set(0)
        titleWeightPreference.set(40)
        minimumMatchScorePreference.set(45)

        val current = manga(
            id = 1,
            title = "One-Punch Man",
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "One Punch Man",
            ),
            totalChapters = 140,
        )

        libraryFlow.value = listOf(duplicate)
        mergeFlow.value = emptyList()
        trackFlow.value = emptyList()

        val results = getDuplicateLibraryManga.subscribe(flowOf(current), backgroundScope)
        val emissions = mutableListOf<List<tachiyomi.domain.manga.model.DuplicateMangaCandidate>>()
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            results.take(2).toList(emissions)
        }

        minimumMatchScorePreference.set(40)

        testScheduler.advanceUntilIdle()
        job.join()

        emissions shouldHaveSize 2
        emissions.first() shouldBe emptyList()
        emissions.last().single().score shouldBe 40
    }

    @Test
    fun `invoke reflects updated title weight without counting cover budget upfront`() = runTest {
        descriptionWeightPreference.set(34)
        authorWeightPreference.set(11)
        artistWeightPreference.set(7)
        coverWeightPreference.set(14)
        genreWeightPreference.set(9)
        statusWeightPreference.set(4)
        chapterCountWeightPreference.set(4)
        titleWeightPreference.set(17)
        val description =
            "The mage Frieren journeys onward after the demon king falls and reflects on the lives she outlived."
        val current = manga(
            id = 1,
            title = "Frieren: Beyond Journey's End",
            description = description,
        )
        val duplicate = libraryManga(
            manga = manga(
                id = 2,
                title = "Frieren Beyond Journeys End",
                author = "Kanehito Yamada",
                description = description,
            ),
            totalChapters = 140,
        )

        coEvery { mangaRepository.getLibraryManga() } returns listOf(duplicate)
        coEvery { mergedMangaRepository.getAll() } returns emptyList()
        trackFlow.value = emptyList()

        val initialResult = getDuplicateLibraryManga(current).single()

        initialResult.scoreMax shouldBe DuplicatePreferences.TOTAL_SCORE_BUDGET - 14

        titleWeightPreference.set(0)

        val updatedResult = getDuplicateLibraryManga(current).single()

        updatedResult.scoreMax shouldBe initialResult.scoreMax - 17
        (updatedResult.score < initialResult.score) shouldBe true
    }

    private fun manga(
        id: Long,
        title: String,
        author: String? = null,
        status: Long = 0,
        genre: List<String>? = null,
        description: String? = null,
    ): Manga {
        return Manga.create().copy(
            id = id,
            source = id,
            title = title,
            author = author,
            status = status,
            genre = genre,
            description = description,
        )
    }

    private fun libraryManga(
        manga: Manga,
        totalChapters: Long,
    ): LibraryManga {
        return LibraryManga(
            manga = manga.copy(favorite = true),
            categories = emptyList(),
            totalChapters = totalChapters,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
    }

    private fun track(
        id: Long,
        mangaId: Long,
        trackerId: Long,
        remoteId: Long,
    ): Track {
        return Track(
            id = id,
            mangaId = mangaId,
            trackerId = trackerId,
            remoteId = remoteId,
            libraryId = null,
            title = "",
            lastChapterRead = 0.0,
            totalChapters = 0,
            status = 0,
            score = 0.0,
            remoteUrl = "",
            startDate = 0,
            finishDate = 0,
            private = false,
        )
    }

    private class MutablePreference<T>(
        private val initialDefault: T,
    ) : Preference<T> {
        private val state = MutableStateFlow(initialDefault)

        override fun key(): String = "test"

        override fun get(): T = state.value

        override fun set(value: T) {
            state.value = value
        }

        override fun isSet(): Boolean = true

        override fun delete() {
            state.value = initialDefault
        }

        override fun defaultValue(): T = initialDefault

        override fun changes(): Flow<T> = state.asStateFlow()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> {
            return changes().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, get())
        }
    }

    private companion object {
        private const val LONG_DESCRIPTION =
            "Long enough description text to pass normalization and act as stable duplicate evidence for tests."
    }
}
