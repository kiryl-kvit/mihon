package tachiyomi.domain.anime.model

import androidx.compose.runtime.Immutable
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason

@Immutable
data class DuplicateAnimeCandidate(
    val anime: AnimeTitle,
    val episodeCount: Long,
    val cheapScore: Int,
    val scoreMax: Int,
    val score: Int,
    val reasons: List<DuplicateMangaMatchReason>,
    val contentSignature: Long = anime.lastModifiedAt,
) {
    val scorePercent: Int
        get() = if (scoreMax <= 0) 0 else ((score.toDouble() / scoreMax.toDouble()) * 100).toInt().coerceIn(0, 100)

    val isStrongMatch: Boolean
        get() = scorePercent >= STRONG_MATCH_PERCENT

    companion object {
        const val STRONG_MATCH_PERCENT = 82
    }
}
