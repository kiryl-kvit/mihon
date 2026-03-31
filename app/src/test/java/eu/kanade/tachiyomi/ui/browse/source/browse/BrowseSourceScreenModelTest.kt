package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.domain.source.model.FeedListingMode
import eu.kanade.domain.source.model.SourceFeedPreset
import eu.kanade.domain.source.model.snapshot
import eu.kanade.domain.source.model.toListing
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BrowseSourceScreenModelTest {

    @Test
    fun `edited filters from popular save as search preset without sentinel query`() {
        val (filters, textFilter) = testFilters()
        textFilter.state = "romance"

        val saved = BrowseSourceScreenModel.State(
            listing = BrowseSourceScreenModel.Listing.Popular,
            filters = filters,
        ).toSavedPresetState(defaultFilters = testFilters().first)

        saved.listingMode shouldBe FeedListingMode.Search
        saved.query shouldBe null
        saved.filters shouldBe filters.snapshot()
    }

    @Test
    fun `search listing keeps trimmed query when saving preset`() {
        val (filters, _) = testFilters()

        val saved = BrowseSourceScreenModel.State(
            listing = BrowseSourceScreenModel.Listing.Search(
                query = "  frieren  ",
                filters = filters,
            ),
            filters = filters,
        ).toSavedPresetState(defaultFilters = testFilters().first)

        saved.listingMode shouldBe FeedListingMode.Search
        saved.query shouldBe "frieren"
        saved.filters shouldBe filters.snapshot()
    }

    @Test
    fun `filter only preset keeps null request query`() {
        val preset = SourceFeedPreset(
            id = "preset",
            sourceId = 1L,
            name = "Filtered",
            listingMode = FeedListingMode.Search,
        )

        preset.toListing().requestQuery shouldBe null
    }

    @Test
    fun `search listing initializes with saved filter snapshot`() {
        val (savedFilters, savedTextFilter) = testFilters()
        savedTextFilter.state = "romance"
        val snapshot = savedFilters.snapshot()

        val initialized = BrowseSourceScreenModel.State(
            listing = BrowseSourceScreenModel.Listing.Search(
                query = null,
                filters = FilterList(),
            ),
        ).initializeForSource(
            sourceFilters = testFilters().first,
            initialFilterSnapshot = snapshot,
        )

        initialized.filters.snapshot() shouldBe snapshot
        (initialized.listing as BrowseSourceScreenModel.Listing.Search).query shouldBe null
        initialized.listing.filters.snapshot() shouldBe snapshot
    }

    private fun testFilters(): Pair<FilterList, TestTextFilter> {
        val textFilter = TestTextFilter()
        return FilterList(textFilter) to textFilter
    }

    private class TestTextFilter : Filter.Text("Keyword")
}
