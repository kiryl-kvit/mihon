package eu.kanade.presentation.manga.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@Immutable
data class MergeTarget(
    val id: Long,
    val searchableTitle: String,
    val isMerged: Boolean,
    val memberMangas: ImmutableList<Manga>,
    val categoryIds: List<Long>,
    val entry: MergeEditorEntry,
)

internal fun buildMergeTargets(
    libraryManga: List<LibraryManga>,
    sourceManager: SourceManager,
    excludedMangaIds: Set<Long> = emptySet(),
): ImmutableList<MergeTarget> {
    return libraryManga.mapNotNull { item ->
        if (item.memberMangaIds.any { it in excludedMangaIds }) {
            return@mapNotNull null
        }
        MergeTarget(
            id = item.id,
            searchableTitle = listOfNotNull(item.manga.title, item.manga.displayName).joinToString(" "),
            isMerged = item.isMerged,
            memberMangas = item.memberMangas.toImmutableList(),
            categoryIds = item.categories,
            entry = MergeEditorEntry(
                id = item.id,
                manga = item.manga,
                subtitle = buildString {
                    append(sourceManager.getOrStub(item.displaySourceId).name)
                    if (item.isMerged) {
                        append(" • ")
                        append(item.memberMangas.size)
                        append(" ")
                        append(if (item.memberMangas.size == 1) "member" else "members")
                    }
                },
            ),
        )
    }.toImmutableList()
}
