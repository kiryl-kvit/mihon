package mihon.feature.profiles.core

import android.content.Context
import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.core.common.CustomPreferences
import mihon.core.common.HomeScreenTabs
import mihon.core.common.defaultHomeScreenTabOrder
import mihon.core.common.defaultHomeScreenTabs
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

class ProfileAwareLibraryPreferencesTest {

    @Test
    fun `library settings stay isolated per profile`() {
        val fixture = createFixture()

        with(fixture.libraryPreferences) {
            downloadedOnly.set(true)
            displayMode.set(LibraryDisplayMode.List)
            sortingMode.set(LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending))
            filterUnread.set(TriState.ENABLED_IS)
            groupType.set(LibraryGroupType.Extension)
        }

        fixture.activeProfileId.value = 2L

        with(fixture.libraryPreferences) {
            downloadedOnly.get() shouldBe false
            displayMode.get() shouldBe LibraryDisplayMode.default
            sortingMode.get() shouldBe LibrarySort.default
            filterUnread.get() shouldBe TriState.DISABLED
            groupType.get() shouldBe LibraryGroupType.Category

            downloadedOnly.set(false)
            displayMode.set(LibraryDisplayMode.ComfortableGrid)
            sortingMode.set(LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Ascending))
            filterUnread.set(TriState.ENABLED_NOT)
            groupType.set(LibraryGroupType.CategoryExtension)
        }

        fixture.activeProfileId.value = 1L

        with(fixture.libraryPreferences) {
            downloadedOnly.get() shouldBe true
            displayMode.get() shouldBe LibraryDisplayMode.List
            sortingMode.get() shouldBe LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending)
            filterUnread.get() shouldBe TriState.ENABLED_IS
            groupType.get() shouldBe LibraryGroupType.Extension
        }

        fixture.activeProfileId.value = 2L

        with(fixture.libraryPreferences) {
            downloadedOnly.get() shouldBe false
            displayMode.get() shouldBe LibraryDisplayMode.ComfortableGrid
            sortingMode.get() shouldBe LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Ascending)
            filterUnread.get() shouldBe TriState.ENABLED_NOT
            groupType.get() shouldBe LibraryGroupType.CategoryExtension
        }
    }

    @Test
    fun `library preference flow follows active profile`() = runTest {
        val fixture = createFixture()
        fixture.libraryPreferences.downloadedOnly.set(true)

        val values = mutableListOf<Boolean>()
        val job = launch {
            fixture.libraryPreferences.downloadedOnly.changes().take(4).toList(values)
        }

        advanceUntilIdle()
        values.last() shouldBe true

        fixture.activeProfileId.value = 2L
        advanceUntilIdle()
        values.last() shouldBe false

        fixture.libraryPreferences.downloadedOnly.set(true)
        advanceUntilIdle()
        values.last() shouldBe true

        job.cancel()
    }

    @Test
    fun `downloaded only is included in profile-owned library preferences`() {
        val key = createFixture().libraryPreferences.downloadedOnly.key()

        key shouldBe ProfileAwarePreferenceStore.Namespace.namespacedKey(
            Preference.appStateKey("pref_downloaded_only"),
            1L,
        )
    }

    @Test
    fun `home screen tab visibility stays isolated per profile`() {
        val fixture = createFixture()

        fixture.customPreferences.homeScreenTabs.set(
            setOf(HomeScreenTabs.Library.name, HomeScreenTabs.More.name, HomeScreenTabs.Profiles.name),
        )
        fixture.activeProfileId.value = 2L
        fixture.customPreferences.homeScreenTabs.get() shouldBe defaultHomeScreenTabs()

        fixture.customPreferences.homeScreenTabs.set(setOf(HomeScreenTabs.More.name))
        fixture.activeProfileId.value = 1L
        fixture.customPreferences.homeScreenTabs.get() shouldBe setOf(
            HomeScreenTabs.Library.name,
            HomeScreenTabs.More.name,
            HomeScreenTabs.Profiles.name,
        )
    }

    @Test
    fun `home screen tab order stays isolated per profile`() {
        val fixture = createFixture()

        fixture.customPreferences.homeScreenTabOrder.set(
            listOf(HomeScreenTabs.More, HomeScreenTabs.Library, HomeScreenTabs.Updates),
        )
        fixture.activeProfileId.value = 2L
        fixture.customPreferences.homeScreenTabOrder.get() shouldBe defaultHomeScreenTabOrder()

        fixture.customPreferences.homeScreenTabOrder.set(
            listOf(HomeScreenTabs.Browse, HomeScreenTabs.More, HomeScreenTabs.Library),
        )
        fixture.activeProfileId.value = 1L
        fixture.customPreferences.homeScreenTabOrder.get() shouldBe listOf(
            HomeScreenTabs.More,
            HomeScreenTabs.Library,
            HomeScreenTabs.Updates,
            HomeScreenTabs.History,
            HomeScreenTabs.Browse,
            HomeScreenTabs.Profiles,
        )
    }

    private fun createFixture(): Fixture {
        val activeProfileId = MutableStateFlow(1L)
        val backing = AndroidPreferenceStore(
            context = mockk<Context>(relaxed = true),
            sharedPreferences = FakeSharedPreferences(),
        )
        val preferenceStore = ProfileAwarePreferenceStore(
            backing = backing,
            profileProvider = { activeProfileId.value },
            profileFlow = activeProfileId,
            namespace = ProfileAwarePreferenceStore.Namespace.PROFILE,
        )
        return Fixture(
            activeProfileId = activeProfileId,
            libraryPreferences = LibraryPreferences(preferenceStore),
            customPreferences = CustomPreferences(preferenceStore),
        )
    }

    private data class Fixture(
        val activeProfileId: MutableStateFlow<Long>,
        val libraryPreferences: LibraryPreferences,
        val customPreferences: CustomPreferences,
    )

    private class FakeSharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()
        private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        override fun getString(key: String?, defValue: String?): String? {
            return data[key] as? String ?: defValue
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val value = data[key] as? Set<String>
            return value?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return data[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return data[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return data[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return data[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean {
            return data.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners -= listener
        }

        private inner class Editor : SharedPreferences.Editor {
            private var clearRequested = false
            private val removals = linkedSetOf<String>()
            private val updates = linkedMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) updates[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
            }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                val changedKeys = linkedSetOf<String>()
                if (clearRequested) {
                    changedKeys += data.keys
                    data.clear()
                }
                removals.forEach { key ->
                    if (data.remove(key) != null) {
                        changedKeys += key
                    }
                }
                updates.forEach { (key, value) ->
                    if (value == null) {
                        if (data.remove(key) != null) {
                            changedKeys += key
                        }
                    } else {
                        data[key] = value
                        changedKeys += key
                    }
                }
                changedKeys.forEach { key ->
                    listeners.forEach { listener ->
                        listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key)
                    }
                }
            }
        }
    }
}
