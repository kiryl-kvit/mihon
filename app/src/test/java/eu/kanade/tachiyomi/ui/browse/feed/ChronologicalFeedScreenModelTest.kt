package eu.kanade.tachiyomi.ui.browse.feed

import androidx.paging.PagingSource
import eu.kanade.domain.source.model.SourceFeedAnchor
import eu.kanade.domain.source.model.SourceFeedTimeline
import eu.kanade.domain.source.service.BrowseFeedService
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

class ChronologicalFeedScreenModelTest {

    @Test
    fun `init cleans saved favorites while preserving surviving anchor`() = runTest {
        val preferences = SourcePreferences(TestPreferenceStore(), testJson)
        preferences.hideInLibraryItems.set(true)

        val browseFeedService = BrowseFeedService(preferences)
        browseFeedService.saveTimeline(
            feedId = FEED_ID,
            timeline = SourceFeedTimeline(mangaIds = listOf(1L, 2L), nextPageKey = 2L),
        )
        browseFeedService.saveAnchor(
            feedId = FEED_ID,
            anchor = SourceFeedAnchor(mangaId = 2L, scrollOffset = 24),
        )

        val getManga = fakeGetManga(
            manga(1L, favorite = true),
            manga(2L, favorite = false),
            manga(3L, favorite = true),
            manga(4L, favorite = false),
        )
        val screenModel = ChronologicalFeedScreenModel(
            feedId = FEED_ID,
            sourceId = SOURCE_ID,
            listingQuery = null,
            initialFilterSnapshot = emptyList(),
            browseFeedService = browseFeedService,
            sourcePreferences = preferences,
            sourceManager = fakeSourceManager(),
            getRemoteManga = fakeGetRemoteManga { RecordingPagingSource(emptyMap()) },
            getManga = getManga,
        )

        try {
            eventually(2.seconds) {
                screenModel.state.value.mangaIds shouldBe listOf(2L)
                screenModel.state.value.savedAnchor shouldBe SourceFeedAnchor(mangaId = 2L, scrollOffset = 24)
                browseFeedService.timelineSnapshot(FEED_ID) shouldBe SourceFeedTimeline(
                    mangaIds = listOf(2L),
                    nextPageKey = 2L,
                )
                browseFeedService.anchorSnapshot(FEED_ID) shouldBe SourceFeedAnchor(mangaId = 2L, scrollOffset = 24)
            }
        } finally {
            screenModel.onDispose()
        }
    }

    @Test
    fun `initial refresh filters favorites when hide in library items is enabled`() = runTest {
        val preferences = SourcePreferences(TestPreferenceStore(), testJson)
        preferences.hideInLibraryItems.set(true)
        val browseFeedService = BrowseFeedService(preferences)

        val getManga = fakeGetManga(
            manga(3L, favorite = true),
            manga(4L, favorite = false),
        )
        val pagingSources = mutableListOf<RecordingPagingSource>()

        val screenModel = ChronologicalFeedScreenModel(
            feedId = FEED_ID,
            sourceId = SOURCE_ID,
            listingQuery = null,
            initialFilterSnapshot = emptyList(),
            browseFeedService = browseFeedService,
            sourcePreferences = preferences,
            sourceManager = fakeSourceManager(),
            getRemoteManga = fakeGetRemoteManga {
                RecordingPagingSource(
                    pages = mapOf(
                        null to mangaPageResult(
                            data = listOf(
                                manga(3L, favorite = true),
                                manga(4L, favorite = false),
                            ),
                            nextKey = null,
                        ),
                    ),
                ).also(pagingSources::add)
            },
            getManga = getManga,
        )

        try {
            eventually(2.seconds) {
                screenModel.state.value.mangaIds shouldBe listOf(4L)
                browseFeedService.timelineSnapshot(FEED_ID) shouldBe SourceFeedTimeline(
                    mangaIds = listOf(4L),
                    nextPageKey = null,
                )
                pagingSources.single().loadKeys shouldBe listOf<Long?>(null)
            }
        } finally {
            screenModel.onDispose()
        }

        coVerify(exactly = 0) { getManga.await(any()) }
    }

    @Test
    fun `refresh reuses one paging source so duplicates across scanned pages are suppressed`() = runTest {
        val preferences = SourcePreferences(TestPreferenceStore(), testJson)
        val browseFeedService = BrowseFeedService(preferences)
        browseFeedService.saveTimeline(
            FEED_ID,
            SourceFeedTimeline(mangaIds = listOf(12L), nextPageKey = 3L),
        )

        val getManga = fakeGetManga(
            manga(10L),
            manga(11L),
            manga(12L),
        )
        val pagingSources = mutableListOf<RecordingPagingSource>()

        val screenModel = ChronologicalFeedScreenModel(
            feedId = FEED_ID,
            sourceId = SOURCE_ID,
            listingQuery = null,
            initialFilterSnapshot = emptyList(),
            browseFeedService = browseFeedService,
            sourcePreferences = preferences,
            sourceManager = fakeSourceManager(),
            getRemoteManga = fakeGetRemoteManga {
                RecordingPagingSource(
                    pages = mapOf(
                        null to pageResult(listOf(10L, 11L), nextKey = 2L),
                        2L to pageResult(listOf(10L, 12L), nextKey = null),
                    ),
                    dedupeByIdWithinSource = true,
                ).also(pagingSources::add)
            },
            getManga = getManga,
        )

        try {
            eventually(2.seconds) {
                screenModel.state.value.mangaIds shouldBe listOf(12L)
            }

            screenModel.refresh(manual = true)

            eventually(2.seconds) {
                screenModel.state.value.mangaIds shouldBe listOf(10L, 11L, 12L)
                screenModel.state.value.newItemsAvailableCount shouldBe 2
                browseFeedService.timelineSnapshot(FEED_ID) shouldBe SourceFeedTimeline(
                    mangaIds = listOf(10L, 11L, 12L),
                    nextPageKey = 3L,
                )
                pagingSources.single().loadKeys shouldBe listOf<Long?>(null, 2L)
            }
        } finally {
            screenModel.onDispose()
        }

        coVerify(exactly = 0) { getManga.await(any()) }
    }

    @Test
    fun `save anchor skips redundant writes`() = runTest {
        val preferences = SourcePreferences(TestPreferenceStore(), testJson)
        val browseFeedService = BrowseFeedService(preferences)
        browseFeedService.saveTimeline(
            feedId = FEED_ID,
            timeline = SourceFeedTimeline(mangaIds = listOf(7L), nextPageKey = null),
        )
        browseFeedService.saveAnchor(
            feedId = FEED_ID,
            anchor = SourceFeedAnchor(mangaId = 7L, scrollOffset = 12),
        )

        val screenModel = ChronologicalFeedScreenModel(
            feedId = FEED_ID,
            sourceId = SOURCE_ID,
            listingQuery = null,
            initialFilterSnapshot = emptyList(),
            browseFeedService = browseFeedService,
            sourcePreferences = preferences,
            sourceManager = fakeSourceManager(),
            getRemoteManga = fakeGetRemoteManga { RecordingPagingSource(emptyMap()) },
            getManga = fakeGetManga(),
        )

        val anchorPreference = preferences.feedAnchor(FEED_ID) as TestPreference<SourceFeedAnchor>

        try {
            eventually(2.seconds) {
                screenModel.state.value.savedAnchor shouldBe SourceFeedAnchor(mangaId = 7L, scrollOffset = 12)
            }

            val writesBeforeDuplicate = anchorPreference.setCount
            screenModel.saveAnchor(mangaId = 7L, scrollOffset = 12)
            anchorPreference.setCount shouldBe writesBeforeDuplicate

            screenModel.saveAnchor(mangaId = 8L, scrollOffset = 18)
            eventually(2.seconds) {
                anchorPreference.setCount shouldBe writesBeforeDuplicate + 1
                browseFeedService.anchorSnapshot(FEED_ID) shouldBe SourceFeedAnchor(mangaId = 8L, scrollOffset = 18)
            }
        } finally {
            screenModel.onDispose()
        }
    }

    companion object {
        private const val FEED_ID = "feed"
        private const val SOURCE_ID = 1L

        private val testJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val mainThreadSurrogate = newSingleThreadContext("UI thread")

        @JvmStatic
        @BeforeAll
        fun setUp() {
            Dispatchers.setMain(mainThreadSurrogate)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            Dispatchers.resetMain()
            mainThreadSurrogate.close()
        }
    }
}

private fun fakeSourceManager(): SourceManager {
    val source = object : CatalogueSource {
        override val id: Long = 1L
        override val name: String = "Test Source"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true

        override fun getFilterList(): FilterList = FilterList()

        override suspend fun getPopularManga(page: Int): MangasPage = error("Not used")

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = error(
            "Not used",
        )

        override suspend fun getLatestUpdates(page: Int): MangasPage = error("Not used")
    }

    return object : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = MutableStateFlow(listOf(source)).asStateFlow()

        override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }

        override fun getOrStub(sourceKey: Long): Source = get(sourceKey) ?: error("Missing source")

        override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()

        override fun getCatalogueSources() = listOf(source)

        override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
    }
}

private fun fakeGetRemoteManga(factory: () -> PagingSource<Long, Manga>): GetRemoteManga {
    val remoteManga = mockk<GetRemoteManga>()
    every { remoteManga(any(), any(), any()) } answers { factory() }
    return remoteManga
}

private fun fakeGetManga(vararg manga: Manga): GetManga {
    val byId = manga.associateBy(Manga::id)
    val getManga = mockk<GetManga>()
    coEvery { getManga.await(any()) } answers { byId[firstArg<Long>()] }
    coEvery { getManga.awaitNonFavoriteIds(any()) } answers {
        firstArg<List<Long>>().filter { id -> byId[id]?.favorite == false }
    }
    coEvery { getManga.subscribe(any()) } answers {
        MutableStateFlow(byId[firstArg<Long>()] ?: error("Missing manga")).asStateFlow()
    }
    return getManga
}

private fun pageResult(ids: List<Long>, nextKey: Long?): PagingSource.LoadResult.Page<Long, Manga> {
    return mangaPageResult(data = ids.map(::manga), nextKey = nextKey)
}

private fun mangaPageResult(data: List<Manga>, nextKey: Long?): PagingSource.LoadResult.Page<Long, Manga> {
    return PagingSource.LoadResult.Page(
        data = data,
        prevKey = null,
        nextKey = nextKey,
    )
}

private fun manga(id: Long, favorite: Boolean = false): Manga {
    return Manga.create().copy(
        id = id,
        source = 1L,
        favorite = favorite,
        title = "Manga $id",
        url = "/manga/$id",
        initialized = true,
    )
}

private class RecordingPagingSource(
    private val pages: Map<Long?, PagingSource.LoadResult.Page<Long, Manga>>,
    private val dedupeByIdWithinSource: Boolean = false,
) : PagingSource<Long, Manga>() {
    val loadKeys = mutableListOf<Long?>()
    private val seenIds = linkedSetOf<Long>()

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        val key = params.key
        loadKeys += key
        val page = pages.getValue(key)
        val data = if (dedupeByIdWithinSource) {
            page.data.filter { seenIds.add(it.id) }
        } else {
            page.data
        }
        return page.copy(data = data)
    }

    override fun getRefreshKey(state: androidx.paging.PagingState<Long, Manga>): Long? = null
}

private class TestPreferenceStore : PreferenceStore {
    private val values = mutableMapOf<String, TestPreference<*>>()

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = preference(
        key,
        defaultValue,
    )

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun getAll(): Map<String, *> = values.mapValues { it.value.get() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> preference(key: String, defaultValue: T): TestPreference<T> {
        return values.getOrPut(key) { TestPreference(key, defaultValue) } as TestPreference<T>
    }
}

private class TestPreference<T>(
    private val preferenceKey: String,
    private val initialDefault: T,
) : Preference<T> {
    private val state = MutableStateFlow<T?>(null)
    var setCount: Int = 0
        private set

    override fun key(): String = preferenceKey

    override fun get(): T = state.value ?: initialDefault

    override fun set(value: T) {
        setCount++
        state.value = value
    }

    override fun isSet(): Boolean = state.value != null

    override fun delete() {
        state.value = null
    }

    override fun defaultValue(): T = initialDefault

    override fun changes(): Flow<T> = state.asStateFlow().map { it ?: initialDefault }

    override fun stateIn(scope: CoroutineScope): StateFlow<T> {
        return changes().stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, get())
    }
}
