package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ToggleAnimeSourceTest {

    private val preferences = SourcePreferences(
        preferenceStore = InteractorTestPreferenceStore(),
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    @Test
    fun `toggle video source updates only video hidden sources`() {
        preferences.disabledSources.set(setOf("1"))

        ToggleAnimeSource(preferences).await(2L, enable = false)

        preferences.disabledSources.get() shouldContainExactly setOf("1")
        preferences.disabledAnimeSources.get() shouldContainExactly setOf("2")
    }

    @Test
    fun `toggle video source pin updates only video pinned sources`() {
        preferences.pinnedSources.set(setOf("1"))

        ToggleAnimeSourcePin(preferences).await(
            tachiyomi.domain.source.model.Source(
                id = 2L,
                lang = "en",
                name = "Video",
                supportsLatest = true,
                isStub = false,
            ),
        )

        preferences.pinnedSources.get() shouldContainExactly setOf("1")
        preferences.pinnedAnimeSources.get() shouldContainExactly setOf("2")
    }
}
