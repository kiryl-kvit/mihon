package eu.kanade.tachiyomi.ui.anime

import cafe.adriel.voyager.navigator.Navigator
import tachiyomi.domain.anime.interactor.GetMergedAnime

internal fun sourceAnimeBypassesMerge(animeId: Long, visibleTargetId: Long): Boolean {
    return animeId != visibleTargetId
}

suspend fun getSourceAnimeScreen(animeId: Long, getMergedAnime: GetMergedAnime): AnimeScreen {
    val visibleTargetId = getMergedAnime.awaitVisibleTargetId(animeId)
    return AnimeScreen(
        animeId = animeId,
        fromSource = true,
        bypassMerge = sourceAnimeBypassesMerge(animeId, visibleTargetId),
    )
}

suspend fun Navigator.pushSourceAnimeScreen(animeId: Long, getMergedAnime: GetMergedAnime) {
    push(getSourceAnimeScreen(animeId, getMergedAnime))
}

suspend fun Navigator.replaceSourceAnimeScreen(animeId: Long, getMergedAnime: GetMergedAnime) {
    replace(getSourceAnimeScreen(animeId, getMergedAnime))
}
