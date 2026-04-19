package mihon.feature.profiles.core

import android.app.Application
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.AnimeSource
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.profile.model.ProfileType

class ProfileManagerTest {

    @Test
    fun `create profile seeds manga and anime hidden sources from installed extensions`() = runTest {
        val insertedProfileId = 2L
        val profileDatabase = mockk<ProfileDatabase>()
        val profileStores = mutableMapOf<Long, TestPreferenceStore>()
        val appStateStores = mutableMapOf<Long, TestPreferenceStore>()
        val privateStores = mutableMapOf<Long, TestPreferenceStore>()
        val profileStore = mockk<ProfileStoreImpl>()
        val profilesPreferences = ProfilesPreferences(TestPreferenceStore())
        val extensionManager = mockk<ExtensionManager>()
        val existingProfiles = listOf(defaultProfile())

        coEvery { profileDatabase.subscribeProfiles(any()) } returns flowOf(existingProfiles)
        coEvery { profileDatabase.getProfiles(includeArchived = true) } returns existingProfiles
        coEvery {
            profileDatabase.insertProfile(
                uuid = any(),
                name = "Anime",
                type = ProfileType.ANIME,
                colorSeed = any(),
                position = 1L,
                requiresAuth = false,
                isArchived = false,
            )
        } returns insertedProfileId
        coEvery { profileDatabase.clearProfileData(insertedProfileId) } just runs
        coEvery { profileDatabase.getProfileById(insertedProfileId) } returns Profile(
            id = insertedProfileId,
            uuid = "profile-$insertedProfileId",
            name = "Anime",
            type = ProfileType.ANIME,
            colorSeed = 1L,
            position = 1L,
            requiresAuth = false,
            isArchived = false,
        )
        every { extensionManager.installedExtensionsFlow } returns MutableStateFlow(
            listOf(
                installedMangaExtension(
                    "manga",
                    listOf(FakeMangaSource(10L, "Manga A"), FakeMangaSource(11L, "Manga B")),
                ),
                installedAnimeExtension(
                    "anime",
                    listOf(FakeAnimeSource(20L, "Anime A"), FakeAnimeSource(21L, "Anime B")),
                ),
            ),
        )
        every { profileStore.currentProfileId } returns ProfileConstants.DEFAULT_PROFILE_ID
        every { profileStore.currentProfileIdFlow } returns flowOf(ProfileConstants.DEFAULT_PROFILE_ID)
        every { profileStore.activeProfileId } returns ProfileConstants.DEFAULT_PROFILE_ID
        every { profileStore.activeProfileIdFlow } returns flowOf(ProfileConstants.DEFAULT_PROFILE_ID)
        every { profileStore.profileStore(any()) } answers {
            profileStores.getOrPut(firstArg()) { TestPreferenceStore() }
        }
        every { profileStore.profileStore() } answers {
            profileStores.getOrPut(ProfileConstants.DEFAULT_PROFILE_ID) { TestPreferenceStore() }
        }
        every { profileStore.appStateStore(any()) } answers {
            appStateStores.getOrPut(firstArg()) { TestPreferenceStore() }
        }
        every { profileStore.appStateStore() } answers {
            appStateStores.getOrPut(ProfileConstants.DEFAULT_PROFILE_ID) { TestPreferenceStore() }
        }
        every { profileStore.privateStore(any()) } answers {
            privateStores.getOrPut(firstArg()) { TestPreferenceStore() }
        }
        every { profileStore.privateStore() } answers {
            privateStores.getOrPut(ProfileConstants.DEFAULT_PROFILE_ID) { TestPreferenceStore() }
        }
        every { profileStore.basePreferenceStore() } returns TestPreferenceStore()
        every { profileStore.deleteProfileState(insertedProfileId) } answers {
            profileStores.remove(insertedProfileId)
            appStateStores.remove(insertedProfileId)
            privateStores.remove(insertedProfileId)
        }

        val manager = ProfileManager(
            application = mockk<Application>(relaxed = true),
            profileDatabase = profileDatabase,
            profileStore = profileStore,
            profilesPreferences = profilesPreferences,
            extensionManager = extensionManager,
        )

        val profile = manager.createProfile(name = "Anime", type = ProfileType.ANIME)

        profile.id shouldBe insertedProfileId
        profileStore.profileStore(insertedProfileId)
            .getStringSet(SourcePreferences.MANGA_HIDDEN_SOURCES_KEY, emptySet())
            .get() shouldContainExactlyInAnyOrder setOf("10", "11")
        profileStore.profileStore(insertedProfileId)
            .getStringSet(SourcePreferences.ANIME_HIDDEN_SOURCES_KEY, emptySet())
            .get() shouldContainExactlyInAnyOrder setOf("20", "21")
    }

    private fun defaultProfile() = Profile(
        id = ProfileConstants.DEFAULT_PROFILE_ID,
        uuid = ProfileConstants.DEFAULT_PROFILE_UUID,
        name = ProfileConstants.DEFAULT_PROFILE_NAME,
        type = ProfileType.MANGA,
        colorSeed = 0L,
        position = 0L,
        requiresAuth = false,
        isArchived = false,
    )

    private fun installedMangaExtension(name: String, sources: List<Source>) = Extension.InstalledManga(
        name = name,
        pkgName = "$name.pkg",
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = sources,
        icon = null,
        isShared = false,
    )

    private fun installedAnimeExtension(name: String, sources: List<AnimeSource>) = Extension.InstalledAnime(
        name = name,
        pkgName = "$name.pkg",
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = sources,
        icon = null,
        isShared = false,
    )
}

private data class FakeMangaSource(
    override val id: Long,
    override val name: String,
    override val lang: String = "en",
) : Source

private data class FakeAnimeSource(
    override val id: Long,
    override val name: String,
    override val lang: String = "en",
) : AnimeSource {
    override suspend fun getAnimeDetails(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

    override suspend fun getEpisodeList(anime: eu.kanade.tachiyomi.source.model.SAnime) = error("Not used")

    override suspend fun getPlaybackData(
        episode: eu.kanade.tachiyomi.source.model.SEpisode,
        selection: eu.kanade.tachiyomi.source.model.VideoPlaybackSelection,
    ) = error("Not used")
}

private class TestPreferenceStore : PreferenceStore {
    private val backing = mutableMapOf<String, Any?>()

    override fun getString(key: String, defaultValue: String): Preference<String> = TestPreference(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = TestPreference(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = TestPreference(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = TestPreference(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = TestPreference(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = TestPreference(
        key,
        defaultValue,
    )

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

        override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
    }
}
