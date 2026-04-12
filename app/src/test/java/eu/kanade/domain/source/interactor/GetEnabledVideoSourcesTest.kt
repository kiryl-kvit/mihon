package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.VideoCatalogueSource
import eu.kanade.tachiyomi.source.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.SVideo
import eu.kanade.tachiyomi.source.model.VideoStream
import eu.kanade.tachiyomi.source.model.VideosPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.service.VideoSourceManager

class GetEnabledVideoSourcesTest {

    private val preferences = SourcePreferences(
        preferenceStore = InteractorTestPreferenceStore(),
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )
    private val videoSourceManager = FakeVideoSourceManager()
    private val interactor = GetEnabledVideoSources(videoSourceManager, preferences)

    @Test
    fun `video pinned sources are independent from manga pinned sources`() = runTest {
        preferences.pinnedSources.set(setOf("1"))
        preferences.pinnedVideoSources.set(setOf("2"))
        videoSourceManager.sources.value = listOf(
            FakeVideoCatalogueSource(id = 1, name = "Manga pin should not affect video", lang = "en"),
            FakeVideoCatalogueSource(id = 2, name = "Video pinned", lang = "en"),
        )

        val sources = interactor.subscribe().firstValue()

        (Pin.Pinned in sources.first { it.id == 1L }.pin) shouldBe false
        (Pin.Pinned in sources.first { it.id == 2L }.pin) shouldBe true
    }

    @Test
    fun `video list respects disabled and last used video preferences`() = runTest {
        preferences.enabledLanguages.set(setOf("en"))
        preferences.disabledVideoSources.set(setOf("2"))
        preferences.lastUsedVideoSource.set(1)
        videoSourceManager.sources.value = listOf(
            FakeVideoCatalogueSource(id = 1, name = "Alpha", lang = "en"),
            FakeVideoCatalogueSource(id = 2, name = "Beta", lang = "en"),
            FakeVideoCatalogueSource(id = 3, name = "Gamma", lang = "es"),
        )

        val sources = interactor.subscribe().firstValue()

        sources.map { it.id } shouldContainExactly listOf(1L, 1L)
        sources.last().isUsedLast shouldBe true
    }
}

private suspend fun <T> Flow<T>.firstValue(): T = this.first()

private class FakeVideoSourceManager : VideoSourceManager {
    private val initialized = MutableStateFlow(true)
    val sources = MutableStateFlow<List<VideoCatalogueSource>>(emptyList())

    override val isInitialized: StateFlow<Boolean> = initialized.asStateFlow()
    override val catalogueSources: Flow<List<VideoCatalogueSource>> = sources

    override fun get(sourceKey: Long): VideoSource? = sources.value.firstOrNull { it.id == sourceKey }

    override fun getCatalogueSources(): List<VideoCatalogueSource> = sources.value
}

private data class FakeVideoCatalogueSource(
    override val id: Long,
    override val name: String,
    override val lang: String,
    override val supportsLatest: Boolean = true,
) : VideoCatalogueSource {
    override suspend fun getPopularVideos(page: Int): VideosPage = error("Not used")

    override suspend fun getSearchVideos(page: Int, query: String, filters: FilterList): VideosPage = error("Not used")

    override suspend fun getLatestUpdates(page: Int): VideosPage = error("Not used")

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getVideoDetails(video: SVideo): SVideo = error("Not used")

    override suspend fun getEpisodeList(video: SVideo): List<SEpisode> = error("Not used")

    override suspend fun getStreamList(episode: SEpisode): List<VideoStream> = error("Not used")
}

internal class InteractorTestPreferenceStore : PreferenceStore {
    private val backing = mutableMapOf<String, Any?>()

    override fun getString(key: String, defaultValue: String): Preference<String> = TestPreference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = TestPreference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = TestPreference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = TestPreference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = TestPreference(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = TestPreference(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = TestPreference(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = TestPreference(key, defaultValue)

    override fun getAll(): Map<String, *> = backing.toMap()

    private inner class TestPreference<T>(
        private val key: String,
        private val defaultValue: T,
    ) : Preference<T> {
        private val state = MutableStateFlow(get())

        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = backing[key] as T? ?: defaultValue

        override fun set(value: T) {
            backing[key] = value
            state.value = value
        }

        override fun isSet(): Boolean = key in backing

        override fun delete() {
            backing.remove(key)
            state.value = defaultValue
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = state

        override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<T> = state
    }
}
