package eu.kanade.domain.manga.interactor

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.DuplicatePreferences

class GetEnhancedDuplicateLibraryManga(
    private val application: Application,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val enhanceDuplicateLibraryManga: EnhanceDuplicateLibraryManga,
    private val duplicatePreferences: DuplicatePreferences,
) {

    suspend operator fun invoke(manga: Manga): List<DuplicateMangaCandidate> {
        val duplicates = getDuplicateLibraryManga(manga)
        return enhanceDuplicateLibraryManga(application, manga, duplicates)
    }

    fun subscribe(
        manga: Flow<Manga>,
        scope: CoroutineScope,
    ): StateFlow<List<DuplicateMangaCandidate>> {
        return manga
            .distinctUntilChanged()
            .flatMapLatest { currentManga ->
                combine(
                    getDuplicateLibraryManga.subscribe(flowOf(currentManga), scope),
                    enhancementConfigFlow(),
                ) { candidates, _ -> candidates }
                    .mapLatest { candidates ->
                        enhanceDuplicateLibraryManga(application, currentManga, candidates)
                    }
            }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )
    }

    private fun enhancementConfigFlow(): Flow<Unit> {
        return combine(
            duplicatePreferences.extendedDuplicateDetectionEnabled.changes(),
            duplicatePreferences.coverWeight.changes(),
        ) { _, _ -> Unit }
    }

    private companion object {
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
