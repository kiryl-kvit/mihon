package eu.kanade.tachiyomi.ui.browse.feed

import eu.kanade.domain.source.model.BUILTIN_LATEST_PRESET_ID
import eu.kanade.domain.source.model.BUILTIN_POPULAR_PRESET_ID
import eu.kanade.domain.source.model.FeedListingMode
import eu.kanade.domain.source.model.SourceFeed
import eu.kanade.domain.source.model.SourceFeedPreset
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableList
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source

class FeedsScreenModelTest {

    @Test
    fun `missing source makes feed invalid after sources load`() {
        val state = FeedsScreenModel.State(
            presets = listOf(
                SourceFeedPreset(id = "preset", sourceId = 1L, name = "Custom", listingMode = FeedListingMode.Search),
            ).toImmutableListForTest(),
            feeds = listOf(
                SourceFeed(id = "feed", sourceId = 2L, presetId = BUILTIN_POPULAR_PRESET_ID),
            ).toImmutableListForTest(),
            sourcesLoaded = true,
        )

        state.isFeedValid(state.feeds.first()) shouldBe false
        state.validFeeds shouldBe emptyList<SourceFeed>().toImmutableListForTest()
    }

    @Test
    fun `latest feed becomes invalid when source no longer supports latest`() {
        val state = FeedsScreenModel.State(
            sources = listOf(
                Source(id = 1L, lang = "en", name = "Source", supportsLatest = false, isStub = false),
            ).toImmutableListForTest(),
            feeds = listOf(
                SourceFeed(id = "feed", sourceId = 1L, presetId = BUILTIN_LATEST_PRESET_ID),
            ).toImmutableListForTest(),
            sourcesLoaded = true,
        )

        state.isFeedValid(state.feeds.first()) shouldBe false
    }

    @Test
    fun `custom preset remains valid only for matching source`() {
        val state = FeedsScreenModel.State(
            sources = listOf(
                Source(id = 1L, lang = "en", name = "Source", supportsLatest = true, isStub = false),
            ).toImmutableListForTest(),
            presets = listOf(
                SourceFeedPreset(id = "preset", sourceId = 2L, name = "Custom", listingMode = FeedListingMode.Search),
            ).toImmutableListForTest(),
            feeds = listOf(SourceFeed(id = "feed", sourceId = 1L, presetId = "preset")).toImmutableListForTest(),
            sourcesLoaded = true,
        )

        state.isFeedValid(state.feeds.first()) shouldBe false
    }
}

private fun <T> List<T>.toImmutableListForTest() = toImmutableList()
