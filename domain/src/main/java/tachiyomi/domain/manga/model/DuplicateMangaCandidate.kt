package tachiyomi.domain.manga.model

import androidx.compose.runtime.Immutable

@Immutable
data class DuplicateMangaCandidate(
    val manga: Manga,
    val chapterCount: Long,
    val cheapScore: Int,
    val coverScore: Int = 0,
    val scoreMax: Int,
    val score: Int,
    val reasons: List<DuplicateMangaMatchReason>,
    val coverHashChecked: Boolean = false,
    val contentSignature: Long = manga.lastModifiedAt,
) {
    val scorePercent: Int
        get() = if (scoreMax <= 0) 0 else ((score.toDouble() / scoreMax.toDouble()) * 100).toInt().coerceIn(0, 100)

    val isStrongMatch: Boolean
        get() = scorePercent >= STRONG_MATCH_PERCENT

    companion object {
        const val STRONG_MATCH_PERCENT = 82
    }
}

enum class DuplicateMangaMatchReason {
    DESCRIPTION,
    TITLE,
    TRACKER,
    AUTHOR,
    ARTIST,
    COVER,
    STATUS,
    GENRE,
    CHAPTER_COUNT,
}
