package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.profile.model.ProfileType

class CustomSettingsScreenTest {

    @Test
    fun `anime profiles only expose anime-safe custom settings sections`() {
        visibleCustomSettingsSectionsForProfileType(ProfileType.ANIME) shouldBe setOf(
            CustomSettingsSection.General,
            CustomSettingsSection.DuplicateDetection,
            CustomSettingsSection.Profiles,
            CustomSettingsSection.Advanced,
        )
    }

    @Test
    fun `manga profiles expose all custom settings sections`() {
        visibleCustomSettingsSectionsForProfileType(ProfileType.MANGA) shouldBe CustomSettingsSection.entries.toSet()
    }
}
