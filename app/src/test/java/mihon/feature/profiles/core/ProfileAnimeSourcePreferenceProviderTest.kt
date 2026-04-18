package mihon.feature.profiles.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProfileAnimeSourcePreferenceProviderTest {

    @Test
    fun `anime source preference key uses separate namespace from manga source prefs`() {
        val mangaKey = "source_7_42"
        val animeKey = animeSourcePreferenceKey(profileId = 7L, sourceId = 42L)

        animeKey shouldBe "anime_source_7_42"
        animeKey shouldBeNot mangaKey
    }

    @Test
    fun `anime source preference key is profile scoped`() {
        animeSourcePreferenceKey(profileId = 1L, sourceId = 42L) shouldBe "anime_source_1_42"
        animeSourcePreferenceKey(profileId = 2L, sourceId = 42L) shouldBe "anime_source_2_42"
    }

    private infix fun String.shouldBeNot(other: String) {
        (this == other) shouldBe false
    }
}
