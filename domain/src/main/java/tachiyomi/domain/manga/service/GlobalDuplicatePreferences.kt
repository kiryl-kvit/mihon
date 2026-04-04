package tachiyomi.domain.manga.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class GlobalDuplicatePreferences(
    preferenceStore: PreferenceStore,
) {
    val extendedDuplicateDetectionEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        key = "extended_duplicate_detection_enabled",
        defaultValue = false,
    )

    val minimumMatchScore: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_minimum_match_score",
        defaultValue = DEFAULT_MINIMUM_MATCH_SCORE,
    )

    val descriptionWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_description_weight",
        defaultValue = DEFAULT_DESCRIPTION_WEIGHT,
    )

    val authorWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_author_weight",
        defaultValue = DEFAULT_AUTHOR_WEIGHT,
    )

    val artistWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_artist_weight",
        defaultValue = DEFAULT_ARTIST_WEIGHT,
    )

    val coverWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_cover_weight",
        defaultValue = DEFAULT_COVER_WEIGHT,
    )

    val genreWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_genre_weight",
        defaultValue = DEFAULT_GENRE_WEIGHT,
    )

    val statusWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_status_weight",
        defaultValue = DEFAULT_STATUS_WEIGHT,
    )

    val chapterCountWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_chapter_count_weight",
        defaultValue = DEFAULT_CHAPTER_COUNT_WEIGHT,
    )

    val titleWeight: Preference<Int> = preferenceStore.getInt(
        key = "extended_duplicate_detection_title_weight",
        defaultValue = DEFAULT_TITLE_WEIGHT,
    )

    fun getWeightBudget(): DuplicateWeightBudget {
        return DuplicateWeightBudget(
            description = descriptionWeight.get().coerceAtLeast(0),
            author = authorWeight.get().coerceAtLeast(0),
            artist = artistWeight.get().coerceAtLeast(0),
            cover = coverWeight.get().coerceAtLeast(0),
            genre = genreWeight.get().coerceAtLeast(0),
            status = statusWeight.get().coerceAtLeast(0),
            chapterCount = chapterCountWeight.get().coerceAtLeast(0),
            title = titleWeight.get().coerceAtLeast(0),
        ).normalized()
    }

    fun resetDetectionSettings() {
        minimumMatchScore.set(DEFAULT_MINIMUM_MATCH_SCORE)
        setWeightBudget(DuplicateWeightBudget.defaults())
    }

    fun setWeightBudget(weightBudget: DuplicateWeightBudget) {
        val normalized = weightBudget.normalized()
        descriptionWeight.set(normalized.description)
        authorWeight.set(normalized.author)
        artistWeight.set(normalized.artist)
        coverWeight.set(normalized.cover)
        genreWeight.set(normalized.genre)
        statusWeight.set(normalized.status)
        chapterCountWeight.set(normalized.chapterCount)
        titleWeight.set(normalized.title)
    }

    data class DuplicateWeightBudget(
        val description: Int,
        val author: Int,
        val artist: Int,
        val cover: Int,
        val genre: Int,
        val status: Int,
        val chapterCount: Int,
        val title: Int,
    ) {
        val total: Int
            get() = description + author + artist + cover + genre + status + chapterCount + title

        val remainingBudget: Int
            get() = (TOTAL_SCORE_BUDGET - total).coerceAtLeast(0)

        fun normalized(): DuplicateWeightBudget {
            val clamped = listOf(
                description,
                author,
                artist,
                cover,
                genre,
                status,
                chapterCount,
                title,
            ).map { it.coerceAtLeast(0) }

            val total = clamped.sum()
            if (total <= TOTAL_SCORE_BUDGET) {
                return DuplicateWeightBudget(
                    description = clamped[0],
                    author = clamped[1],
                    artist = clamped[2],
                    cover = clamped[3],
                    genre = clamped[4],
                    status = clamped[5],
                    chapterCount = clamped[6],
                    title = clamped[7],
                )
            }

            val scaled = clamped.map { value ->
                ((value.toDouble() / total.toDouble()) * TOTAL_SCORE_BUDGET).toInt()
            }.toMutableList()
            var remaining = TOTAL_SCORE_BUDGET - scaled.sum()
            var index = 0
            while (remaining > 0) {
                if (clamped[index] > 0) {
                    scaled[index] += 1
                    remaining--
                }
                index = (index + 1) % scaled.size
            }

            return DuplicateWeightBudget(
                description = scaled[0],
                author = scaled[1],
                artist = scaled[2],
                cover = scaled[3],
                genre = scaled[4],
                status = scaled[5],
                chapterCount = scaled[6],
                title = scaled[7],
            )
        }

        companion object {
            fun defaults(): DuplicateWeightBudget {
                return DuplicateWeightBudget(
                    description = DEFAULT_DESCRIPTION_WEIGHT,
                    author = DEFAULT_AUTHOR_WEIGHT,
                    artist = DEFAULT_ARTIST_WEIGHT,
                    cover = DEFAULT_COVER_WEIGHT,
                    genre = DEFAULT_GENRE_WEIGHT,
                    status = DEFAULT_STATUS_WEIGHT,
                    chapterCount = DEFAULT_CHAPTER_COUNT_WEIGHT,
                    title = DEFAULT_TITLE_WEIGHT,
                )
            }
        }
    }

    companion object {
        const val TOTAL_SCORE_BUDGET = 100

        const val DEFAULT_DESCRIPTION_WEIGHT = 35
        const val DEFAULT_AUTHOR_WEIGHT = 5
        const val DEFAULT_ARTIST_WEIGHT = 5
        const val DEFAULT_COVER_WEIGHT = 15
        const val DEFAULT_GENRE_WEIGHT = 0
        const val DEFAULT_STATUS_WEIGHT = 0
        const val DEFAULT_CHAPTER_COUNT_WEIGHT = 0
        const val DEFAULT_TITLE_WEIGHT = 40
        const val DEFAULT_MINIMUM_MATCH_SCORE = 25
    }
}
