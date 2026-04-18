package eu.kanade.domain.extension.interactor

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.ExtensionType
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class GetAnimeExtensionsTest {

    @Test
    fun `buildAnimeExtensions keeps only video payloads and separates updates`() {
        val result = buildAnimeExtensions(
            installed = listOf(
                installedVideo("video-installed", hasUpdate = true),
                installedVideo("video-stable", hasUpdate = false),
            ),
            available = listOf(
                availableVideo("video-available"),
                availableVideo("video-stable"),
            ),
            untrusted = listOf(
                untrustedVideo("video-untrusted"),
            ),
        )

        result.updates.map { it.name } shouldContainExactly listOf("video-installed")
        result.installed.map { it.name } shouldContainExactly listOf("video-stable")
        result.available.map { it.name } shouldContainExactly listOf("video-available")
        result.untrusted.map { it.name } shouldContainExactly listOf("video-untrusted")
    }

    private fun installedVideo(name: String, hasUpdate: Boolean = false) = Extension.InstalledAnime(
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

    private fun availableVideo(name: String) = Extension.AvailableAnime(
        name = name,
        pkgName = "$name.pkg",
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkName = "$name.apk",
        iconUrl = "https://example.com/$name.png",
        repoUrl = "https://example.com",
    )

    private fun untrustedVideo(name: String) = Extension.Untrusted(
        name = name,
        pkgName = "$name.pkg",
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        signatureHash = "sig",
        type = ExtensionType.ANIME,
    )
}
