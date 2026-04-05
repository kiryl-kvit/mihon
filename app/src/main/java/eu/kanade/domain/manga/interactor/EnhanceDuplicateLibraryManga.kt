package eu.kanade.domain.manga.interactor

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.DuplicateMangaMatchReason
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.service.DuplicatePreferences
import kotlin.math.roundToInt

class EnhanceDuplicateLibraryManga(
    private val mangaRepository: MangaRepository,
    private val duplicatePreferences: DuplicatePreferences,
) {

    data class EnhancementRequest(
        val mangaId: Long,
        val contentSignature: Long,
        val candidateSignatures: List<Pair<Long, Long>>,
    )

    suspend operator fun invoke(
        context: Context,
        manga: Manga,
        candidates: List<DuplicateMangaCandidate>,
        limit: Int = DEFAULT_CANDIDATE_LIMIT,
    ): List<DuplicateMangaCandidate> {
        val weightBudget = duplicatePreferences.getWeightBudget()
        if (!duplicatePreferences.extendedDuplicateDetectionEnabled.get()) return candidates
        if (weightBudget.cover <= 0) return candidates
        if (candidates.isEmpty() || manga.thumbnailUrl.isNullOrBlank()) return candidates

        val prioritized = candidates.sortedByDescending(DuplicateMangaCandidate::cheapScore)
        val topCandidates = prioritized.take(limit)
        val remainingCandidates = prioritized.drop(limit)

        val sourceHash = getOrComputeCoverHash(context, manga) ?: return prioritized

        val updatedTopCandidates = withIOContext {
            topCandidates.map { candidate ->
                async {
                    val candidateHash = getOrComputeCoverHash(context, candidate.manga)
                    if (candidateHash == null) {
                        candidate.copy(coverHashChecked = true)
                    } else {
                        val coverScore = coverScoreFromDistance(
                            distance = hammingDistance(sourceHash, candidateHash),
                            maxScore = weightBudget.cover,
                        )
                        val reasons = if (coverScore > 0 && DuplicateMangaMatchReason.COVER !in candidate.reasons) {
                            candidate.reasons + DuplicateMangaMatchReason.COVER
                        } else {
                            candidate.reasons
                        }
                        candidate.copy(
                            coverScore = coverScore,
                            scoreMax = (candidate.scoreMax - candidate.coverScore + weightBudget.cover).coerceAtLeast(
                                1,
                            ),
                            score = (candidate.cheapScore + coverScore)
                                .coerceIn(
                                    0,
                                    (candidate.scoreMax - candidate.coverScore + weightBudget.cover).coerceAtLeast(1),
                                ),
                            reasons = reasons,
                            coverHashChecked = true,
                        )
                    }
                }
            }
                .awaitAll()
        }

        return (updatedTopCandidates + remainingCandidates)
            .sortedWith(
                compareByDescending<DuplicateMangaCandidate> { it.score }
                    .thenByDescending { it.coverHashChecked }
                    .thenBy { it.manga.title.lowercase() },
            )
    }

    fun requestFor(manga: Manga, candidates: List<DuplicateMangaCandidate>): EnhancementRequest {
        return EnhancementRequest(
            mangaId = manga.id,
            contentSignature = manga.lastModifiedAt,
            candidateSignatures = candidates.map { it.manga.id to it.contentSignature },
        )
    }

    private suspend fun getOrComputeCoverHash(context: Context, manga: Manga): Long? {
        if (manga.coverLastModified != 0L) {
            mangaRepository.getCoverHash(manga.id, manga.coverLastModified)?.let { return it }
        }

        val bitmap = loadBitmap(context, manga) ?: return null
        val hash = bitmap.differenceHash()

        if (manga.coverLastModified != 0L) {
            mangaRepository.upsertCoverHash(manga.id, manga.coverLastModified, hash)
        }

        return hash
    }

    private suspend fun loadBitmap(context: Context, manga: Manga): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(manga)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
        val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources)
        return drawable?.getBitmapOrNull()
    }

    private fun Bitmap.differenceHash(): Long {
        val resized = scale(HASH_WIDTH, HASH_HEIGHT, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                val left = resized.getPixel(x, y).grayscale()
                val right = resized.getPixel(x + 1, y).grayscale()
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        if (resized !== this) {
            resized.recycle()
        }
        return hash
    }

    private fun Int.grayscale(): Int {
        val red = (this shr 16) and 0xFF
        val green = (this shr 8) and 0xFF
        val blue = this and 0xFF
        return ((red * 299) + (green * 587) + (blue * 114)) / 1000
    }

    private fun hammingDistance(left: Long, right: Long): Int {
        return java.lang.Long.bitCount(left xor right)
    }

    private fun coverScoreFromDistance(distance: Int, maxScore: Int): Int {
        if (maxScore <= 0) return 0
        return when {
            distance <= 4 -> maxScore
            distance <= 8 -> (maxScore * 0.75f).roundToInt()
            distance <= 12 -> (maxScore * 0.5f).roundToInt()
            distance <= 16 -> (maxScore * 0.25f).roundToInt()
            else -> 0
        }
    }

    private companion object {
        private const val DEFAULT_CANDIDATE_LIMIT = 12
        private const val HASH_WIDTH = 9
        private const val HASH_HEIGHT = 8
    }
}
