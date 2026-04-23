package eu.kanade.tachiyomi.ui.main

import eu.kanade.tachiyomi.ui.home.HomeScreen
import io.kotest.matchers.shouldBe
import mihon.feature.profiles.core.Profile
import org.junit.jupiter.api.Test
import tachiyomi.core.common.Constants
import tachiyomi.domain.profile.model.ProfileType

class MainActivityShortcutIntentTest {

    @Test
    fun `anime shortcut resolves to anime library entry tab`() {
        resolveShortcutTab(
            action = Constants.SHORTCUT_ANIME,
            animeIdToOpen = 42L,
        ) shouldBe HomeScreen.Tab.Library(animeIdToOpen = 42L)
    }

    @Test
    fun `manga shortcut resolves to manga library entry tab`() {
        resolveShortcutTab(
            action = Constants.SHORTCUT_MANGA,
            mangaIdToOpen = 24L,
        ) shouldBe HomeScreen.Tab.Library(mangaIdToOpen = 24L)
    }

    @Test
    fun `extension shortcut keeps active profile when types already match`() {
        val mangaProfile = profile(id = 1L, type = ProfileType.MANGA)

        resolveExtensionShortcutProfile(
            profiles = listOf(mangaProfile, profile(id = 2L, type = ProfileType.ANIME)),
            activeProfileId = mangaProfile.id,
            requestedType = ProfileType.MANGA,
        ) shouldBe mangaProfile
    }

    @Test
    fun `extension shortcut switches to matching profile type`() {
        val mangaProfile = profile(id = 1L, type = ProfileType.MANGA)
        val animeProfile = profile(id = 2L, type = ProfileType.ANIME)

        resolveExtensionShortcutProfile(
            profiles = listOf(mangaProfile, animeProfile),
            activeProfileId = mangaProfile.id,
            requestedType = ProfileType.ANIME,
        ) shouldBe animeProfile
    }

    @Test
    fun `extension shortcut falls back to active profile when requested type is unavailable`() {
        val mangaProfile = profile(id = 1L, type = ProfileType.MANGA)

        resolveExtensionShortcutProfile(
            profiles = listOf(mangaProfile),
            activeProfileId = mangaProfile.id,
            requestedType = ProfileType.ANIME,
        ) shouldBe mangaProfile
    }

    private fun profile(id: Long, type: ProfileType) = Profile(
        id = id,
        uuid = "uuid-$id",
        name = "Profile $id",
        type = type,
        colorSeed = 0L,
        position = id,
        requiresAuth = false,
        isArchived = false,
    )
}
