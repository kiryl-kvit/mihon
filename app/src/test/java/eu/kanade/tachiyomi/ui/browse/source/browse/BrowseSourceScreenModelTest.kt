package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.domain.source.model.FeedListingMode
import eu.kanade.domain.source.model.SourceFeedPreset
import eu.kanade.domain.source.model.latestFeedPreset
import eu.kanade.domain.source.model.popularFeedPreset
import eu.kanade.domain.source.model.snapshot
import eu.kanade.domain.source.model.toListing
import eu.kanade.presentation.manga.components.buildMergeTargets
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

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
    fun `custom presets default to chronological`() {
        val preset = SourceFeedPreset(
            id = "preset",
            sourceId = 1L,
            name = "Filtered",
            listingMode = FeedListingMode.Search,
        )

        preset.chronological shouldBe true
    }

    @Test
    fun `builtin feed presets keep explicit chronological semantics`() {
        popularFeedPreset(1L, "Popular").chronological shouldBe false
        latestFeedPreset(1L, "Latest").chronological shouldBe true
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

    @Test
    fun `buildMergeTargets includes plain and merged library targets`() {
        val targets = buildMergeTargets(
            libraryManga = listOf(
                libraryManga(id = 1L, title = "Solo", sourceId = 1L),
                libraryManga(
                    id = 2L,
                    title = "Merged Root",
                    sourceId = 2L,
                    memberMangas = listOf(
                        manga(id = 2L, title = "Merged Root", sourceId = 2L),
                        manga(id = 3L, title = "Merged Child", sourceId = 3L),
                    ),
                ),
            ),
            sourceManager = fakeSourceManager(),
        )

        targets.map { it.id } shouldBe listOf(1L, 2L)
        targets.map { it.isMerged } shouldBe listOf(false, true)
        targets.map { it.memberMangas.size } shouldBe listOf(1, 2)
        targets[0].entry.subtitle shouldBe "Source 1"
        targets[1].entry.subtitle shouldBe "Source 2 • 2 members"
    }

    @Test
    fun `buildMergeTargets excludes current manga and current merge members`() {
        val targets = buildMergeTargets(
            libraryManga = listOf(
                libraryManga(id = 1L, title = "Current", sourceId = 1L),
                libraryManga(
                    id = 2L,
                    title = "Current Merge Root",
                    sourceId = 2L,
                    memberMangas = listOf(
                        manga(id = 2L, title = "Current Merge Root", sourceId = 2L),
                        manga(id = 3L, title = "Current Merge Child", sourceId = 3L),
                    ),
                ),
                libraryManga(id = 4L, title = "Valid Target", sourceId = 4L),
            ),
            sourceManager = fakeSourceManager(),
            excludedMangaIds = setOf(1L, 2L, 3L),
        )

        targets.map { it.id } shouldBe listOf(4L)
    }

    @Test
    fun `library action chooser only carries manga payload`() {
        val dialog = BrowseSourceScreenModel.Dialog.LibraryActionChooser(
            manga = manga(id = 10L, title = "Remote", sourceId = 1L),
        )

        dialog.manga.id shouldBe 10L
    }

    private fun testFilters(): Pair<FilterList, TestTextFilter> {
        val textFilter = TestTextFilter()
        return FilterList(textFilter) to textFilter
    }

    private class TestTextFilter : Filter.Text("Keyword")
}

private fun libraryManga(
    id: Long,
    title: String,
    sourceId: Long,
    memberMangas: List<Manga> = listOf(manga(id = id, title = title, sourceId = sourceId)),
): LibraryManga {
    return LibraryManga(
        manga = manga(id = id, title = title, sourceId = sourceId),
        categories = listOf(0L),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
        memberMangaIds = memberMangas.map(Manga::id),
        memberMangas = memberMangas,
        displaySourceId = sourceId,
        sourceIds = memberMangas.mapTo(linkedSetOf()) { it.source },
    )
}

private fun manga(id: Long, title: String, sourceId: Long): Manga {
    return Manga.create().copy(
        id = id,
        source = sourceId,
        title = title,
        favorite = true,
        initialized = true,
    )
}

private fun fakeSourceManager(): SourceManager {
    return object : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = MutableStateFlow(
            emptyList<eu.kanade.tachiyomi.source.CatalogueSource>(),
        ).asStateFlow()

        override fun get(sourceKey: Long): Source? = object : Source {
            override val id: Long = sourceKey
            override val lang: String = "en"
            override val name: String = "Source $sourceKey"
            override suspend fun getMangaDetails(manga: SManga): SManga = error("Not used")
            override suspend fun getChapterList(manga: SManga) = error("Not used")
            override suspend fun getPageList(chapter: eu.kanade.tachiyomi.source.model.SChapter) = error("Not used")
        }

        override fun getOrStub(sourceKey: Long): Source = get(sourceKey) ?: error("Missing source")

        override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()

        override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()

        override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
    }
}
