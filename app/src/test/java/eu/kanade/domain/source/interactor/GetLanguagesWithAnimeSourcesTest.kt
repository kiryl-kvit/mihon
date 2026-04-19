package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.AnimeSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.model.VideoPlaybackData
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.AnimeSourceManager

class GetLanguagesWithAnimeSourcesTest {

    private val preferences = SourcePreferences(
        preferenceStore = InteractorTestPreferenceStore(),
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )
    private val sourceManager = GroupingFakeAnimeSourceManager()
    private val interactor = GetLanguagesWithAnimeSources(sourceManager, preferences)

    @Test
    fun `groups anime sources by language with enabled languages first and disabled sources last`() = runTest {
        preferences.enabledLanguages.set(setOf("en", "all"))
        preferences.disabledAnimeSources.set(setOf("2"))
        sourceManager.sources.value = listOf(
            GroupingFakeAnimeCatalogueSource(id = 2, name = "Zulu", lang = "en"),
            GroupingFakeAnimeCatalogueSource(id = 1, name = "Alpha", lang = "en"),
            GroupingFakeAnimeCatalogueSource(id = 3, name = "Bravo", lang = "es"),
        )

        val result = interactor.subscribe().first()

        result.keys.toList() shouldContainExactly listOf("en", "es")
        result.getValue("en").map { it.id } shouldContainExactly listOf(1L, 2L)
        result.getValue("es").map { it.id } shouldContainExactly listOf(3L)
    }
}

private class GroupingFakeAnimeSourceManager : AnimeSourceManager {
    private val initialized = MutableStateFlow(true)
    val sources = MutableStateFlow<List<AnimeCatalogueSource>>(emptyList())

    override val isInitialized: StateFlow<Boolean> = initialized.asStateFlow()
    override val catalogueSources: Flow<List<AnimeCatalogueSource>> = sources

    override fun get(sourceKey: Long): AnimeSource? = sources.value.firstOrNull { it.id == sourceKey }

    override fun getCatalogueSources(): List<AnimeCatalogueSource> = sources.value
}

private data class GroupingFakeAnimeCatalogueSource(
    override val id: Long,
    override val name: String,
    override val lang: String,
    override val supportsLatest: Boolean = true,
) : AnimeCatalogueSource {
    override suspend fun getPopularAnime(page: Int): AnimesPage = error("Not used")

    override suspend fun getSearchAnime(page: Int, query: String, filters: FilterList): AnimesPage = error("Not used")

    override suspend fun getLatestUpdates(page: Int): AnimesPage = error("Not used")

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = error("Not used")

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = error("Not used")

    override suspend fun getPlaybackData(
        episode: SEpisode,
        selection: VideoPlaybackSelection,
    ): VideoPlaybackData = error("Not used")
}
