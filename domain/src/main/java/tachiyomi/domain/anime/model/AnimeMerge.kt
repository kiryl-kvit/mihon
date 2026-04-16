package tachiyomi.domain.anime.model

data class AnimeMerge(
    val targetId: Long,
    val animeId: Long,
    val position: Long,
) {
    val isTarget: Boolean = targetId == animeId
}
