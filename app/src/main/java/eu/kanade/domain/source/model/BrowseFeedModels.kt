package eu.kanade.domain.source.model

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tachiyomi.domain.source.interactor.GetRemoteManga

const val BUILTIN_POPULAR_PRESET_ID = "builtin:popular"
const val BUILTIN_LATEST_PRESET_ID = "builtin:latest"

@Serializable
data class SourceFeedPreset(
    val id: String,
    val sourceId: Long,
    val name: String,
    val listingMode: FeedListingMode,
    val query: String? = null,
    val filters: List<FilterStateNode> = emptyList(),
)

@Serializable
data class SourceFeed(
    val id: String,
    val sourceId: Long,
    val presetId: String,
    val enabled: Boolean = true,
)

fun popularFeedPreset(sourceId: Long, name: String): SourceFeedPreset {
    return SourceFeedPreset(
        id = BUILTIN_POPULAR_PRESET_ID,
        sourceId = sourceId,
        name = name,
        listingMode = FeedListingMode.Popular,
    )
}

fun latestFeedPreset(sourceId: Long, name: String): SourceFeedPreset {
    return SourceFeedPreset(
        id = BUILTIN_LATEST_PRESET_ID,
        sourceId = sourceId,
        name = name,
        listingMode = FeedListingMode.Latest,
    )
}

@Serializable
enum class FeedListingMode {
    @SerialName("popular")
    Popular,

    @SerialName("latest")
    Latest,

    @SerialName("search")
    Search,
}

@Serializable
sealed interface FilterStateNode {
    val name: String

    @Serializable
    @SerialName("header")
    data class Header(
        override val name: String,
    ) : FilterStateNode

    @Serializable
    @SerialName("separator")
    data class Separator(
        override val name: String,
    ) : FilterStateNode

    @Serializable
    @SerialName("select")
    data class Select(
        override val name: String,
        val state: Int,
    ) : FilterStateNode

    @Serializable
    @SerialName("text")
    data class Text(
        override val name: String,
        val state: String,
    ) : FilterStateNode

    @Serializable
    @SerialName("checkbox")
    data class CheckBox(
        override val name: String,
        val state: Boolean,
    ) : FilterStateNode

    @Serializable
    @SerialName("tristate")
    data class TriState(
        override val name: String,
        val state: Int,
    ) : FilterStateNode

    @Serializable
    @SerialName("sort")
    data class Sort(
        override val name: String,
        val index: Int?,
        val ascending: Boolean?,
    ) : FilterStateNode

    @Serializable
    @SerialName("group")
    data class Group(
        override val name: String,
        val state: List<FilterStateNode>,
    ) : FilterStateNode
}

fun SourceFeedPreset.toListing(): FeedSavedListing {
    return FeedSavedListing(
        mode = listingMode,
        query = query,
        filters = filters,
    )
}

data class FeedSavedListing(
    val mode: FeedListingMode,
    val query: String? = null,
    val filters: List<FilterStateNode> = emptyList(),
) {
    val requestQuery: String?
        get() = when (mode) {
            FeedListingMode.Popular -> GetRemoteManga.QUERY_POPULAR
            FeedListingMode.Latest -> GetRemoteManga.QUERY_LATEST
            FeedListingMode.Search -> query
        }
}

fun FilterList.snapshot(): List<FilterStateNode> {
    return map(Filter<*>::toNode)
}

fun FilterList.applySnapshot(snapshot: List<FilterStateNode>): FilterList {
    applyNodes(filters = this, nodes = snapshot)
    return this
}

private fun applyNodes(filters: List<Filter<*>>, nodes: List<FilterStateNode>) {
    filters.zip(nodes).forEach { (filter, node) ->
        when {
            filter.name != node.name -> return@forEach
            filter is Filter.Select<*> && node is FilterStateNode.Select -> {
                filter.state = node.state.coerceIn(0, filter.values.lastIndex)
            }
            filter is Filter.Text && node is FilterStateNode.Text -> {
                filter.state = node.state
            }
            filter is Filter.CheckBox && node is FilterStateNode.CheckBox -> {
                filter.state = node.state
            }
            filter is Filter.TriState && node is FilterStateNode.TriState -> {
                filter.state = node.state
            }
            filter is Filter.Sort && node is FilterStateNode.Sort -> {
                filter.state = if (node.index == null || node.ascending == null) {
                    null
                } else {
                    Filter.Sort.Selection(
                        index = node.index.coerceIn(0, filter.values.lastIndex),
                        ascending = node.ascending,
                    )
                }
            }
            filter is Filter.Group<*> && node is FilterStateNode.Group -> {
                applyNodes(filter.state.filterIsInstance<Filter<*>>(), node.state)
            }
        }
    }
}

private fun Filter<*>.toNode(): FilterStateNode {
    return when (this) {
        is Filter.Header -> FilterStateNode.Header(name)
        is Filter.Separator -> FilterStateNode.Separator(name)
        is Filter.Select<*> -> FilterStateNode.Select(name, state)
        is Filter.Text -> FilterStateNode.Text(name, state)
        is Filter.CheckBox -> FilterStateNode.CheckBox(name, state)
        is Filter.TriState -> FilterStateNode.TriState(name, state)
        is Filter.Sort -> FilterStateNode.Sort(name, state?.index, state?.ascending)
        is Filter.Group<*> -> FilterStateNode.Group(
            name = name,
            state = state.filterIsInstance<Filter<*>>().map(Filter<*>::toNode),
        )
    }
}
