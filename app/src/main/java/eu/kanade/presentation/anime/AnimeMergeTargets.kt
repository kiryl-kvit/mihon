package eu.kanade.presentation.anime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.manga.components.MergeEditorEntry
import eu.kanade.presentation.manga.components.MergeSearchTarget
import eu.kanade.presentation.manga.components.MergeTargetPickerSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.manga.model.Manga

@Immutable
data class AnimeMergeTarget(
    val id: Long,
    val searchableTitle: String,
    val isMerged: Boolean,
    val memberAnimes: ImmutableList<AnimeTitle>,
    val categoryIds: List<Long>,
    val entry: MergeEditorEntry,
) : MergeSearchTarget {
    override val mergeSearchTitle: String
        get() = entry.title

    override val mergeSearchableTitle: String
        get() = searchableTitle
}

fun buildAnimeMergeTargets(
    libraryAnime: List<AnimeTitle>,
    merges: List<AnimeMerge>,
    categoryIdsByAnimeId: Map<Long, List<Long>>,
    sourceNameForId: (Long) -> String,
    multiSourceName: String,
    excludedAnimeIds: Set<Long> = emptySet(),
): ImmutableList<AnimeMergeTarget> {
    val byId = libraryAnime.associateBy(AnimeTitle::id)
    val groupedMerges = merges.groupBy(AnimeMerge::targetId)

    val collapsed = mutableListOf<AnimeMergeTarget>()
    val consumedIds = mutableSetOf<Long>()

    groupedMerges.forEach { (targetId, group) ->
        val members = group.sortedBy(AnimeMerge::position)
            .mapNotNull { byId[it.animeId] }
        if (members.size <= 1) return@forEach

        val memberIds = members.map(AnimeTitle::id)
        consumedIds += memberIds
        if (memberIds.any { it in excludedAnimeIds }) return@forEach

        val target = members.firstOrNull { it.id == targetId } ?: members.first()
        val sourceIds = members.map(AnimeTitle::source).toSet()
        val displaySourceName = if (sourceIds.size > 1) {
            multiSourceName
        } else {
            sourceNameForId(target.source)
        }

        collapsed += AnimeMergeTarget(
            id = target.id,
            searchableTitle = members.flatMap { member ->
                listOfNotNull(member.displayTitle, member.title, member.originalTitle)
            }
                .distinct()
                .joinToString(" "),
            isMerged = true,
            memberAnimes = members.toImmutableList(),
            categoryIds = members.flatMap { categoryIdsByAnimeId[it.id].orEmpty() }.distinct(),
            entry = target.toMergeEditorEntry(
                subtitle = buildString {
                    append(displaySourceName)
                    append(" • ")
                    append(members.size)
                    append(" ")
                    append(if (members.size == 1) "member" else "members")
                },
            ),
        )
    }

    collapsed += libraryAnime.filterNot { anime ->
        anime.id in consumedIds || anime.id in excludedAnimeIds
    }.map { anime ->
        AnimeMergeTarget(
            id = anime.id,
            searchableTitle = listOfNotNull(anime.displayTitle, anime.title, anime.originalTitle)
                .distinct()
                .joinToString(" "),
            isMerged = false,
            memberAnimes = listOf(anime).toImmutableList(),
            categoryIds = categoryIdsByAnimeId[anime.id].orEmpty(),
            entry = anime.toMergeEditorEntry(
                subtitle = sourceNameForId(anime.source),
            ),
        )
    }

    return collapsed.toImmutableList()
}

fun AnimeTitle.toMergeEditorEntry(
    subtitle: String,
    isRemovable: Boolean = false,
    isMember: Boolean = false,
): MergeEditorEntry {
    return MergeEditorEntry(
        id = id,
        manga = toMergeEditorManga(),
        subtitle = subtitle,
        isRemovable = isRemovable,
        isMember = isMember,
        titleOverride = displayTitle,
        coverData = toMangaCover(),
    )
}

@Composable
fun AnimeMergeTargetPickerDialog(
    title: String,
    query: String,
    visibleTargets: List<AnimeMergeTarget>,
    onDismissRequest: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectTarget: (Long) -> Unit,
) {
    MergeTargetPickerSheet(
        title = title,
        query = query,
        entries = visibleTargets.map { it.entry }.toImmutableList(),
        onDismissRequest = onDismissRequest,
        onQueryChange = onQueryChange,
        onSelectTarget = onSelectTarget,
    )
}

private fun AnimeTitle.toMergeEditorManga(): Manga {
    return Manga.create().copy(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        dateAdded = dateAdded,
        coverLastModified = coverLastModified,
        url = url,
        title = title,
        displayName = displayName,
        author = studio,
        artist = director,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        initialized = initialized,
        lastModifiedAt = lastModifiedAt,
        favoriteModifiedAt = favoriteModifiedAt,
        version = version,
        notes = notes,
    )
}
