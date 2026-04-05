package eu.kanade.domain.manga.interactor

import android.app.Application
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.DuplicatePreferences

class GetEnhancedDuplicateLibraryMangaTest {

    private val application = mockk<Application>()
    private val getDuplicateLibraryManga = mockk<GetDuplicateLibraryManga>()
    private val enhanceDuplicateLibraryManga = mockk<EnhanceDuplicateLibraryManga>()
    private val duplicatePreferences = mockk<DuplicatePreferences>()
    private val extendedEnabledPreference = MutablePreference(true)
    private val minimumMatchScorePreference = MutablePreference(DuplicatePreferences.DEFAULT_MINIMUM_MATCH_SCORE)
    private val coverWeightPreference = MutablePreference(DuplicatePreferences.DEFAULT_COVER_WEIGHT)

    private val interactor = GetEnhancedDuplicateLibraryManga(
        application = application,
        getDuplicateLibraryManga = getDuplicateLibraryManga,
        enhanceDuplicateLibraryManga = enhanceDuplicateLibraryManga,
        duplicatePreferences = duplicatePreferences,
    )

    init {
        every { duplicatePreferences.extendedDuplicateDetectionEnabled } returns extendedEnabledPreference
        every { duplicatePreferences.minimumMatchScore } returns minimumMatchScorePreference
        every { duplicatePreferences.coverWeight } returns coverWeightPreference
    }

    @Test
    fun `invoke enhances duplicate candidates`() = runTest {
        val manga = manga(1, "Frieren")
        val baseCandidates = listOf(candidate(2, 40, coverHashChecked = false))
        val enhancedCandidates = listOf(candidate(2, 54, coverHashChecked = true))

        coEvery { getDuplicateLibraryManga(manga) } returns baseCandidates
        coEvery { enhanceDuplicateLibraryManga(application, manga, baseCandidates) } returns enhancedCandidates

        interactor(manga) shouldBe enhancedCandidates

        coVerify(exactly = 1) { enhanceDuplicateLibraryManga(application, manga, baseCandidates) }
    }

    @Test
    fun `subscribe re-enhances when cover weight changes`() = runTest {
        val manga = manga(1, "Frieren")
        val baseCandidates = listOf(candidate(2, 40, coverHashChecked = false))
        val firstEnhancedCandidates = listOf(candidate(2, 54, coverHashChecked = true))
        val secondEnhancedCandidates = listOf(candidate(2, 44, coverHashChecked = true))
        val duplicateFlow = MutableStateFlow(baseCandidates)

        every {
            getDuplicateLibraryManga.subscribe(any(), any())
        } returns duplicateFlow.asStateFlow()
        coEvery {
            enhanceDuplicateLibraryManga(application, manga, baseCandidates)
        } returnsMany listOf(firstEnhancedCandidates, secondEnhancedCandidates)

        val subscriptionScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        try {
            val results = interactor.subscribe(flowOf(manga), subscriptionScope)
            val emissions = mutableListOf<List<DuplicateMangaCandidate>>()
            val job = subscriptionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                results.take(3).toList(emissions)
            }

            testScheduler.advanceUntilIdle()
            coverWeightPreference.set(0)
            testScheduler.advanceUntilIdle()
            job.join()

            emissions shouldBe listOf(emptyList(), firstEnhancedCandidates, secondEnhancedCandidates)
            coVerify(exactly = 2) { enhanceDuplicateLibraryManga(application, manga, baseCandidates) }
        } finally {
            subscriptionScope.cancel()
        }
    }

    private fun manga(id: Long, title: String): Manga {
        return Manga.create().copy(
            id = id,
            source = id,
            title = title,
        )
    }

    private fun candidate(mangaId: Long, score: Int, coverHashChecked: Boolean): DuplicateMangaCandidate {
        return DuplicateMangaCandidate(
            manga = manga(mangaId, "Candidate $mangaId"),
            chapterCount = 12,
            cheapScore = 40,
            scoreMax = 86,
            score = score,
            reasons = emptyList(),
            coverHashChecked = coverHashChecked,
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
}
