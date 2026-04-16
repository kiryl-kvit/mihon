package eu.kanade.tachiyomi.ui.manga

import cafe.adriel.voyager.navigator.Navigator
import tachiyomi.domain.manga.interactor.GetMergedManga

internal fun sourceMangaBypassesMerge(mangaId: Long, visibleTargetId: Long): Boolean {
    return mangaId != visibleTargetId
}

suspend fun getSourceMangaScreen(mangaId: Long, getMergedManga: GetMergedManga): MangaScreen {
    val visibleTargetId = getMergedManga.awaitVisibleTargetId(mangaId)
    return MangaScreen(
        mangaId = mangaId,
        fromSource = true,
        bypassMerge = sourceMangaBypassesMerge(mangaId, visibleTargetId),
    )
}

suspend fun Navigator.pushSourceMangaScreen(mangaId: Long, getMergedManga: GetMergedManga) {
    push(getSourceMangaScreen(mangaId, getMergedManga))
}

suspend fun Navigator.replaceSourceMangaScreen(mangaId: Long, getMergedManga: GetMergedManga) {
    replace(getSourceMangaScreen(mangaId, getMergedManga))
}
