package tachiyomi.domain.manga.interactor

import com.aallam.similarity.NormalizedLevenshtein
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.MergedMangaRepository
import tachiyomi.domain.manga.service.DuplicatePreferences
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GetDuplicateLibraryManga(
    private val mangaRepository: MangaRepository,
    private val mergedMangaRepository: MergedMangaRepository,
    private val trackRepository: TrackRepository,
    private val duplicatePreferences: DuplicatePreferences,
) {

    private val normalizedLevenshtein = NormalizedLevenshtein()

    suspend operator fun invoke(manga: Manga): List<DuplicateMangaCandidate> {
        val libraryManga = mangaRepository.getLibraryManga()
        val merges = mergedMangaRepository.getAll()
        val tracks = trackRepository.getTracksAsFlow().first()
        val duplicateConfig = duplicatePreferences.toConfig()
        return withContext(Dispatchers.Default) {
            detectDuplicates(manga, libraryManga, merges, tracks, duplicateConfig)
        }
    }

    fun subscribe(
        manga: Flow<Manga>,
        scope: CoroutineScope,
    ): StateFlow<List<DuplicateMangaCandidate>> {
        val duplicateConfigFlow = combine(
            duplicatePreferences.extendedDuplicateDetectionEnabled.changes(),
            duplicatePreferences.minimumMatchScore.changes(),
            combine(
                duplicatePreferences.descriptionWeight.changes(),
                duplicatePreferences.authorWeight.changes(),
                duplicatePreferences.artistWeight.changes(),
                duplicatePreferences.coverWeight.changes(),
            ) { _, _, _, _ -> Unit },
            combine(
                duplicatePreferences.genreWeight.changes(),
                duplicatePreferences.statusWeight.changes(),
                duplicatePreferences.chapterCountWeight.changes(),
                duplicatePreferences.titleWeight.changes(),
            ) { _, _, _, _ -> Unit },
        ) { _, _, _, _ ->
            duplicatePreferences.toConfig()
        }

        val snapshot = combine(
            mangaRepository.getLibraryMangaAsFlow(),
            mergedMangaRepository.subscribeAll(),
            trackRepository.getTracksAsFlow(),
            duplicateConfigFlow,
        ) { libraryManga, merges, tracks, config ->
            DuplicateLibrarySnapshot(
                libraryManga = libraryManga,
                merges = merges,
                tracks = tracks,
                config = config,
            )
        }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = DuplicateLibrarySnapshot(config = duplicatePreferences.toConfig()),
            )

        return combine(manga, snapshot) { currentManga, duplicateLibrarySnapshot ->
            detectDuplicates(
                manga = currentManga,
                libraryManga = duplicateLibrarySnapshot.libraryManga,
                merges = duplicateLibrarySnapshot.merges,
                tracks = duplicateLibrarySnapshot.tracks,
                config = duplicateLibrarySnapshot.config,
            )
        }
            .distinctUntilChanged()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )
    }

    private fun detectDuplicates(
        manga: Manga,
        libraryManga: List<LibraryManga>,
        merges: List<MangaMerge>,
        tracks: List<Track>,
        config: DuplicateConfig,
    ): List<DuplicateMangaCandidate> {
        val current = manga.toPreparedDuplicateManga()
        if (current.normalizedTitle.isBlank()) return emptyList()

        val collapsedLibrary = collapseLibraryManga(libraryManga, merges)
        val excludedIds = buildExcludedIds(manga.id, merges)
        val trackerDuplicateIds = trackerDuplicateIds(manga.id, collapsedLibrary, tracks)

        return collapsedLibrary.asSequence()
            .filterNot { libraryItem -> libraryItem.memberMangaIds.any { it in excludedIds } }
            .mapNotNull { libraryItem ->
                if (config.extendedEnabled) {
                    scoreExtendedDuplicate(current, libraryItem, trackerDuplicateIds, config)
                } else {
                    scoreLegacyDuplicate(current, libraryItem, trackerDuplicateIds)
                }
            }
            .sortedWith(
                compareByDescending<DuplicateMangaCandidate> { it.score }
                    .thenBy { it.manga.title.lowercase(Locale.ROOT) },
            )
            .toList()
    }

    private fun buildExcludedIds(
        mangaId: Long,
        merges: List<MangaMerge>,
    ): Set<Long> {
        val mergeTargetId = merges.firstOrNull { it.mangaId == mangaId }?.targetId
        if (mergeTargetId == null) {
            return setOf(mangaId)
        }

        return merges.asSequence()
            .filter { it.targetId == mergeTargetId }
            .mapTo(linkedSetOf(mergeTargetId)) { it.mangaId }
    }

    private fun collapseLibraryManga(
        libraryManga: List<LibraryManga>,
        merges: List<MangaMerge>,
    ): List<LibraryManga> {
        val byId = libraryManga.associateBy { it.manga.id }
        val groupedMerges = merges.groupBy { it.targetId }

        val collapsed = mutableListOf<LibraryManga>()
        val consumedIds = mutableSetOf<Long>()

        groupedMerges.forEach { (targetId, group) ->
            val members = group.sortedBy { it.position }
                .mapNotNull { byId[it.mangaId] }
            if (members.size <= 1) return@forEach

            val target = members.firstOrNull { it.manga.id == targetId } ?: members.first()
            consumedIds += members.map { it.manga.id }
            collapsed += target.copy(
                categories = members.flatMap { it.categories }.distinct(),
                totalChapters = members.sumOf { it.totalChapters },
                readCount = members.sumOf { it.readCount },
                bookmarkCount = members.sumOf { it.bookmarkCount },
                latestUpload = members.maxOfOrNull { it.latestUpload } ?: 0L,
                chapterFetchedAt = members.maxOfOrNull { it.chapterFetchedAt } ?: 0L,
                lastRead = members.maxOfOrNull { it.lastRead } ?: 0L,
                memberMangaIds = members.map { it.manga.id },
                memberMangas = members.map { it.manga },
                displaySourceId = target.displaySourceId,
                sourceIds = members.flatMapTo(linkedSetOf()) { it.sourceIds },
            )
        }

        collapsed += libraryManga.filterNot { it.manga.id in consumedIds }
        return collapsed
    }

    private fun trackerDuplicateIds(
        mangaId: Long,
        collapsedLibrary: List<LibraryManga>,
        tracks: List<Track>,
    ): Set<Long> {
        val libraryMemberIds = collapsedLibrary.flatMapTo(linkedSetOf()) { it.memberMangaIds }
        val currentTrackKeys = tracks.asSequence()
            .filter { it.mangaId == mangaId }
            .map { it.trackerId to it.remoteId }
            .toSet()

        if (currentTrackKeys.isEmpty()) return emptySet()

        return tracks.asSequence()
            .filter { it.mangaId != mangaId }
            .filter { it.mangaId in libraryMemberIds }
            .filter { (it.trackerId to it.remoteId) in currentTrackKeys }
            .mapTo(linkedSetOf()) { it.mangaId }
    }

    private fun scoreExtendedDuplicate(
        current: PreparedDuplicateManga,
        libraryItem: LibraryManga,
        trackerDuplicateIds: Set<Long>,
        config: DuplicateConfig,
    ): DuplicateMangaCandidate? {
        val weights = config.weights
        val bestMatch = libraryItem.memberMangas.asSequence()
            .map { it.toPreparedDuplicateManga(chapterCount = libraryItem.totalChapters) }
            .mapNotNull { candidate -> scoreExtendedDuplicate(current, candidate, trackerDuplicateIds, config) }
            .maxByOrNull { it.score }
            ?: return null

        return DuplicateMangaCandidate(
            manga = libraryItem.manga,
            chapterCount = libraryItem.totalChapters,
            cheapScore = bestMatch.score,
            scoreMax = weights.baseScoreMax(),
            score = if (libraryItem.memberMangaIds.any { it in trackerDuplicateIds }) {
                max(bestMatch.score, LEGACY_TRACKER_MATCH_SCORE)
            } else {
                bestMatch.score
            },
            reasons = if (
                libraryItem.memberMangaIds.any { it in trackerDuplicateIds } &&
                DuplicateMangaMatchReason.TRACKER !in bestMatch.reasons
            ) {
                bestMatch.reasons + DuplicateMangaMatchReason.TRACKER
            } else {
                bestMatch.reasons
            },
        )
    }

    private fun scoreExtendedDuplicate(
        current: PreparedDuplicateManga,
        candidate: PreparedDuplicateManga,
        trackerDuplicateIds: Set<Long>,
        config: DuplicateConfig,
    ): ScoredDuplicate? {
        val weights = config.weights
        if (current.normalizedTitle.isBlank() || candidate.normalizedTitle.isBlank()) return null

        val titleSimilarity = calculateTitleSimilarity(current, candidate)
        val descriptionSimilarity = calculateDescriptionSimilarity(current, candidate)
        val chapterCountSimilarity = calculateChapterCountSimilarity(current.chapterCount, candidate.chapterCount)

        val reasons = linkedSetOf<DuplicateMangaMatchReason>()
        var score = 0

        if (weights.description > 0 && descriptionSimilarity >= DESCRIPTION_REASON_THRESHOLD) {
            reasons += DuplicateMangaMatchReason.DESCRIPTION
        }
        score += scaledSimilarity(descriptionSimilarity, weights.description)

        if (weights.title > 0 && titleSimilarity >= TITLE_REASON_THRESHOLD) {
            reasons += DuplicateMangaMatchReason.TITLE
        }
        score += scaledSimilarity(titleSimilarity, weights.title)

        val authorMatched = current.author != null && candidate.creators.any { namesMatch(current.author, it) }
        val artistMatched = current.artist != null && candidate.creators.any { namesMatch(current.artist, it) }

        if (weights.author > 0 && authorMatched) {
            reasons += DuplicateMangaMatchReason.AUTHOR
            score += weights.author
        }
        if (weights.artist > 0 && artistMatched) {
            reasons += DuplicateMangaMatchReason.ARTIST
            score += weights.artist
        }
        if (current.creators.isNotEmpty() && candidate.creators.isNotEmpty() && !authorMatched && !artistMatched) {
            score -= CREATOR_MISMATCH_PENALTY
        }

        when {
            weights.status > 0 && hasKnownMatchingStatus(current.manga.status, candidate.manga.status) -> {
                reasons += DuplicateMangaMatchReason.STATUS
                score += weights.status
            }
            hasKnownConflictingStatus(current.manga.status, candidate.manga.status) -> {
                score -= STATUS_MISMATCH_PENALTY
            }
        }

        val genreOverlap = current.genres.intersect(candidate.genres).size
        if (weights.genre > 0 && genreOverlap > 0) {
            reasons += DuplicateMangaMatchReason.GENRE
            score += scaledGenreWeight(genreOverlap, weights.genre)
        }

        if (weights.chapterCount > 0 && chapterCountSimilarity >= CHAPTER_COUNT_REASON_THRESHOLD) {
            reasons += DuplicateMangaMatchReason.CHAPTER_COUNT
        }
        score += scaledSimilarity(chapterCountSimilarity, weights.chapterCount)

        if (candidate.manga.id in trackerDuplicateIds) {
            reasons += DuplicateMangaMatchReason.TRACKER
            score = max(score, LEGACY_TRACKER_MATCH_SCORE)
        }

        if (reasons.isEmpty()) return null

        score = score.coerceIn(0, DuplicatePreferences.TOTAL_SCORE_BUDGET)
        if (score < config.minimumMatchScore && DuplicateMangaMatchReason.TRACKER !in reasons) return null

        val hasCreatorMatch = authorMatched || artistMatched
        val hasStrongNonTitleEvidence =
            descriptionSimilarity >= STRONG_DESCRIPTION_THRESHOLD ||
                (hasCreatorMatch && descriptionSimilarity >= SUPPORTING_DESCRIPTION_THRESHOLD) ||
                (hasCreatorMatch && genreOverlap >= 2) ||
                (descriptionSimilarity >= MEDIUM_DESCRIPTION_THRESHOLD && genreOverlap >= 2) ||
                (
                    descriptionSimilarity >= SUPPORTING_DESCRIPTION_THRESHOLD &&
                        chapterCountSimilarity >= CHAPTER_COUNT_REASON_THRESHOLD
                    ) ||
                (chapterCountSimilarity >= CHAPTER_COUNT_REASON_THRESHOLD && genreOverlap >= 2) ||
                DuplicateMangaMatchReason.TRACKER in reasons

        val hasSupportingTitleEvidence =
            titleSimilarity >= TITLE_REASON_THRESHOLD && (authorMatched || artistMatched || genreOverlap >= 1)
        val hasStrongTitleEvidence = titleSimilarity >= STRONG_TITLE_THRESHOLD || hasSupportingTitleEvidence
        if (!hasStrongNonTitleEvidence && !hasStrongTitleEvidence) {
            return null
        }

        return ScoredDuplicate(
            score = score,
            reasons = reasons.toList(),
        )
    }

    private fun scoreLegacyDuplicate(
        current: PreparedDuplicateManga,
        libraryItem: LibraryManga,
        trackerDuplicateIds: Set<Long>,
    ): DuplicateMangaCandidate? {
        val reasons = linkedSetOf<DuplicateMangaMatchReason>()

        val titleMatched = libraryItem.memberMangas.any { candidate ->
            val normalizedTitle = candidate.title.lowercase(Locale.ROOT)
            normalizedTitle.contains(current.rawLowercaseTitle)
        }
        if (titleMatched) {
            reasons += DuplicateMangaMatchReason.TITLE
        }

        val trackerMatched = libraryItem.memberMangaIds.any { it in trackerDuplicateIds }
        if (trackerMatched) {
            reasons += DuplicateMangaMatchReason.TRACKER
        }

        if (reasons.isEmpty()) return null

        val score = when {
            trackerMatched && titleMatched -> LEGACY_TITLE_MATCH_SCORE.coerceAtLeast(LEGACY_TRACKER_MATCH_SCORE)
            trackerMatched -> LEGACY_TRACKER_MATCH_SCORE
            else -> LEGACY_TITLE_MATCH_SCORE
        }

        return DuplicateMangaCandidate(
            manga = libraryItem.manga,
            chapterCount = libraryItem.totalChapters,
            cheapScore = score,
            scoreMax = LEGACY_SCORE_MAX,
            score = score,
            reasons = reasons.toList(),
        )
    }

    private fun calculateTitleSimilarity(
        current: PreparedDuplicateManga,
        candidate: PreparedDuplicateManga,
    ): Double {
        if (current.normalizedTitle == candidate.normalizedTitle) return 1.0

        val levenshtein = normalizedLevenshtein.similarity(current.normalizedTitle, candidate.normalizedTitle)

        val sharedTokens = current.titleTokens.intersect(candidate.titleTokens)
        val subsetCoverage = if (sharedTokens.size >= MIN_SHARED_TITLE_TOKENS) {
            sharedTokens.size.toDouble() / min(current.titleTokens.size, candidate.titleTokens.size)
        } else {
            0.0
        }

        val containsSimilarity = if (
            min(current.normalizedTitle.length, candidate.normalizedTitle.length) >= CONTAINS_MIN_TITLE_LENGTH &&
            (
                current.normalizedTitle.contains(candidate.normalizedTitle) ||
                    candidate.normalizedTitle.contains(current.normalizedTitle)
                )
        ) {
            max(
                min(current.normalizedTitle.length, candidate.normalizedTitle.length).toDouble() /
                    max(current.normalizedTitle.length, candidate.normalizedTitle.length),
                if (min(current.titleTokens.size, candidate.titleTokens.size) == 1) {
                    SINGLE_TOKEN_CONTAINS_SIMILARITY
                } else {
                    0.0
                },
            )
        } else {
            0.0
        }

        return max(levenshtein, max(subsetCoverage, containsSimilarity))
    }

    private fun calculateDescriptionSimilarity(
        current: PreparedDuplicateManga,
        candidate: PreparedDuplicateManga,
    ): Double {
        val currentDescription = current.normalizedDescription ?: return 0.0
        val candidateDescription = candidate.normalizedDescription ?: return 0.0

        val levenshtein = normalizedLevenshtein.similarity(currentDescription, candidateDescription)
        val sharedTokens = current.descriptionTokens.intersect(candidate.descriptionTokens)
        val overlap = if (
            sharedTokens.isNotEmpty() &&
            current.descriptionTokens.isNotEmpty() &&
            candidate.descriptionTokens.isNotEmpty()
        ) {
            sharedTokens.size.toDouble() / min(current.descriptionTokens.size, candidate.descriptionTokens.size)
        } else {
            0.0
        }

        return max(levenshtein, overlap)
    }

    private fun calculateChapterCountSimilarity(
        currentChapterCount: Long?,
        candidateChapterCount: Long?,
    ): Double {
        if (currentChapterCount == null || candidateChapterCount == null) return 0.0
        if (currentChapterCount <= 0 || candidateChapterCount <= 0) return 0.0

        val diff = abs(currentChapterCount - candidateChapterCount)
        val maxCount = max(currentChapterCount, candidateChapterCount)
        return (1.0 - (diff.toDouble() / maxCount.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun namesMatch(
        current: String,
        candidate: String,
    ): Boolean {
        return current == candidate ||
            (
                min(current.length, candidate.length) >= CONTAINS_MIN_TITLE_LENGTH &&
                    (current.contains(candidate) || candidate.contains(current))
                ) ||
            normalizedLevenshtein.similarity(current, candidate) >= MIN_NAME_SIMILARITY
    }

    private fun hasKnownMatchingStatus(
        currentStatus: Long,
        candidateStatus: Long,
    ): Boolean {
        return currentStatus != SManga.UNKNOWN.toLong() &&
            candidateStatus != SManga.UNKNOWN.toLong() &&
            currentStatus == candidateStatus
    }

    private fun hasKnownConflictingStatus(
        currentStatus: Long,
        candidateStatus: Long,
    ): Boolean {
        return currentStatus != SManga.UNKNOWN.toLong() &&
            candidateStatus != SManga.UNKNOWN.toLong() &&
            currentStatus != candidateStatus
    }

    private fun Manga.toPreparedDuplicateManga(
        chapterCount: Long? = null,
    ): PreparedDuplicateManga {
        val normalizedTitle = normalizeTitle(title)
        val normalizedDescription = normalizeDescription(description)
        return PreparedDuplicateManga(
            manga = this,
            normalizedTitle = normalizedTitle,
            rawLowercaseTitle = title.lowercase(Locale.ROOT).trim(),
            titleTokens = normalizedTitle.split(' ')
                .asSequence()
                .filter { it.length >= MIN_TITLE_TOKEN_LENGTH }
                .toSet(),
            normalizedDescription = normalizedDescription,
            descriptionTokens = normalizedDescription
                ?.split(' ')
                ?.asSequence()
                ?.filter { it.length >= MIN_DESCRIPTION_TOKEN_LENGTH }
                ?.toSet()
                .orEmpty(),
            author = normalizeValue(author),
            artist = normalizeValue(artist),
            genres = genre.orEmpty()
                .mapNotNull(::normalizeValue)
                .toSet(),
            chapterCount = chapterCount,
        )
    }

    private fun normalizeTitle(title: String): String {
        val raw = title.lowercase(Locale.ROOT)
        val debracketed = removeBracketedText(raw, opening = "([<{", closing = ")]}>")
            .takeIf { it.length > SHORT_TITLE_LENGTH }
            ?: removeBracketedText(raw, opening = ")]}>", closing = "([<{", reverse = true)

        return debracketed
            .replace(CHAPTER_REFERENCE_REGEX, " ")
            .replace(NON_TEXT_REGEX, " ")
            .replace(CONSECUTIVE_SPACES_REGEX, " ")
            .trim()
    }

    private fun normalizeValue(value: String?): String? {
        return value
            ?.lowercase(Locale.ROOT)
            ?.replace(NON_TEXT_REGEX, " ")
            ?.replace(CONSECUTIVE_SPACES_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeDescription(description: String?): String? {
        return description
            ?.lowercase(Locale.ROOT)
            ?.replace(DESCRIPTION_NOISE_REGEX, " ")
            ?.replace(NON_TEXT_REGEX, " ")
            ?.replace(CONSECUTIVE_SPACES_REGEX, " ")
            ?.trim()
            ?.takeIf { it.length >= MIN_DESCRIPTION_LENGTH }
    }

    private fun removeBracketedText(
        text: String,
        opening: String,
        closing: String,
        reverse: Boolean = false,
    ): String {
        var depth = 0
        return buildString {
            for (char in if (reverse) text.reversed() else text) {
                when (char) {
                    in opening -> depth++
                    in closing -> if (depth > 0) depth--
                    else -> if (depth == 0) {
                        if (reverse) {
                            insert(0, char)
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    private fun scaledSimilarity(similarity: Double, weight: Int): Int {
        return (similarity * weight).roundToInt()
    }

    private fun scaledGenreWeight(genreOverlap: Int, weight: Int): Int {
        if (weight <= 0 || genreOverlap <= 0) return 0
        val overlapScale = min(genreOverlap, MAX_GENRE_MATCH_COUNT).toDouble() / MAX_GENRE_MATCH_COUNT.toDouble()
        return (weight * overlapScale).roundToInt().coerceAtLeast(1)
    }

    private fun DuplicatePreferences.toConfig(): DuplicateConfig {
        return DuplicateConfig(
            extendedEnabled = extendedDuplicateDetectionEnabled.get(),
            minimumMatchScore = minimumMatchScore.get().coerceIn(0, DuplicatePreferences.TOTAL_SCORE_BUDGET),
            weights = getWeightBudget().toExtendedWeights(),
        )
    }

    private fun DuplicatePreferences.DuplicateWeightBudget.toExtendedWeights(): ExtendedWeights {
        return ExtendedWeights(
            description = description,
            author = author,
            artist = artist,
            cover = cover,
            genre = genre,
            status = status,
            chapterCount = chapterCount,
            title = title,
        )
    }

    private fun ExtendedWeights.baseScoreMax(): Int {
        return (description + author + artist + genre + status + chapterCount + title).coerceAtLeast(1)
    }

    private data class PreparedDuplicateManga(
        val manga: Manga,
        val normalizedTitle: String,
        val rawLowercaseTitle: String,
        val titleTokens: Set<String>,
        val normalizedDescription: String?,
        val descriptionTokens: Set<String>,
        val author: String?,
        val artist: String?,
        val genres: Set<String>,
        val chapterCount: Long?,
    ) {
        val creators: Set<String> = listOfNotNull(author, artist).toSet()
    }

    private data class ScoredDuplicate(
        val score: Int,
        val reasons: List<DuplicateMangaMatchReason>,
    )

    private data class DuplicateLibrarySnapshot(
        val libraryManga: List<LibraryManga> = emptyList(),
        val merges: List<MangaMerge> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val config: DuplicateConfig,
    )

    data class DuplicateConfig(
        val extendedEnabled: Boolean,
        val minimumMatchScore: Int,
        val weights: ExtendedWeights,
    )

    data class ExtendedWeights(
        val description: Int,
        val author: Int,
        val artist: Int,
        val cover: Int,
        val genre: Int,
        val status: Int,
        val chapterCount: Int,
        val title: Int,
    )

    companion object {
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        private const val CREATOR_MISMATCH_PENALTY = 10
        private const val STATUS_MISMATCH_PENALTY = 2
        private const val LEGACY_TITLE_MATCH_SCORE = 46
        private const val LEGACY_TRACKER_MATCH_SCORE = 58
        private const val LEGACY_SCORE_MAX = 100
        private const val MIN_NAME_SIMILARITY = 0.9
        private const val SHORT_TITLE_LENGTH = 4
        private const val CONTAINS_MIN_TITLE_LENGTH = 6
        private const val MIN_SHARED_TITLE_TOKENS = 2
        private const val MIN_TITLE_TOKEN_LENGTH = 2
        private const val MIN_DESCRIPTION_TOKEN_LENGTH = 4
        private const val MIN_DESCRIPTION_LENGTH = 48
        private const val TITLE_REASON_THRESHOLD = 0.45
        private const val DESCRIPTION_REASON_THRESHOLD = 0.35
        private const val CHAPTER_COUNT_REASON_THRESHOLD = 0.95
        private const val STRONG_TITLE_THRESHOLD = 0.85
        private const val STRONG_DESCRIPTION_THRESHOLD = 0.62
        private const val MEDIUM_DESCRIPTION_THRESHOLD = 0.5
        private const val SUPPORTING_DESCRIPTION_THRESHOLD = 0.35
        private const val SINGLE_TOKEN_CONTAINS_SIMILARITY = 0.78
        private const val MAX_GENRE_MATCH_COUNT = 4

        private val NON_TEXT_REGEX = Regex("[^\\p{L}0-9 ]")
        private val CONSECUTIVE_SPACES_REGEX = Regex(" +")
        private val CHAPTER_REFERENCE_REGEX = Regex("""((- часть|- глава) \d*)""")
        private val DESCRIPTION_NOISE_REGEX = Regex("""\b(chapter|chapters|read|summary|synopsis|description)\b""")
    }
}
