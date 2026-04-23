package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionManagerPendingUpdateCountsTest {

    @Test
    fun `pendingExtensionUpdateCounts separates manga and anime updates`() {
        pendingExtensionUpdateCounts(
            listOf(
                installedManga("manga-update", hasUpdate = true),
                installedManga("manga-stable", hasUpdate = false),
                installedAnime("anime-update", hasUpdate = true),
                installedAnime("anime-stable", hasUpdate = false),
            ),
        ) shouldBe PendingExtensionUpdateCounts(
            manga = 1,
            anime = 1,
        )
    }

    @Test
    fun `pendingExtensionUpdateCounts ignores entries without updates`() {
        pendingExtensionUpdateCounts(
            listOf(
                installedManga("manga-stable", hasUpdate = false),
                installedAnime("anime-stable", hasUpdate = false),
            ),
        ) shouldBe PendingExtensionUpdateCounts(
            manga = 0,
            anime = 0,
        )
    }

    private fun installedManga(name: String, hasUpdate: Boolean) = Extension.InstalledManga(
        name = name,
        pkgName = "$name.pkg",
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        hasUpdate = hasUpdate,
        isObsolete = false,
        isShared = false,
    )

    private fun installedAnime(name: String, hasUpdate: Boolean) = Extension.InstalledAnime(
        name = name,
        pkgName = "$name.pkg",
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        hasUpdate = hasUpdate,
        isObsolete = false,
        isShared = false,
    )
}
