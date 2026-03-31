package mihon.feature.profiles.core

import android.app.Application
import android.content.Intent
import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import tachiyomi.core.common.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ProfileManager(
    private val application: Application,
    private val profileDatabase: ProfileDatabase = Injekt.get(),
    private val profileStore: ProfileStoreImpl = Injekt.get(),
    private val profilesPreferences: ProfilesPreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val switchRequests = MutableStateFlow(profilesPreferences.activeProfileId.get())

    val activeProfileId: Long
        get() = profileStore.currentProfileId

    val profiles: StateFlow<List<Profile>> = profileDatabase.subscribeProfiles(includeArchived = true)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val visibleProfiles: StateFlow<List<Profile>> = profileDatabase.subscribeProfiles(includeArchived = false)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeProfile: StateFlow<Profile?> = combine(profiles, switchRequests) { profiles, activeId ->
        profiles.firstOrNull { it.id == activeId }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val shouldShowPicker: Flow<Boolean> = profileDatabase.subscribeProfiles(includeArchived = false)
        .map { it.size > 1 }

    suspend fun shouldShowPickerOnLaunch(): Boolean {
        return profilesPreferences.pickerEnabled.get() && profileDatabase.getVisibleProfileCount() > 1
    }

    suspend fun ensureDefaultProfile() {
        val defaultProfile = profileDatabase.getProfileById(ProfileConstants.defaultProfileId)
        if (defaultProfile == null) {
            profileDatabase.insertProfile(
                uuid = ProfileConstants.defaultProfileUuid,
                name = ProfileConstants.defaultProfileName,
                colorSeed = 0x4F6AF2,
                position = 0,
                requiresAuth = false,
                isArchived = false,
            )
        }

        migrateLegacyPreferencesIfNeeded()
    }

    suspend fun loadInitialProfile(): Profile? {
        ensureDefaultProfile()
        val activeId = profilesPreferences.activeProfileId.get()
        val profile = profileDatabase.getProfileById(activeId)
            ?: visibleProfiles.value.firstOrNull()
            ?: profileDatabase.getProfileById(ProfileConstants.defaultProfileId)
        if (profile != null) {
            setActiveProfile(profile.id, rescheduleJobs = false)
        }
        return profile
    }

    suspend fun setActiveProfile(profileId: Long, rescheduleJobs: Boolean = true) {
        val profile = profileDatabase.getProfileById(profileId) ?: return
        profileStore.setCurrentProfileId(profile.id)
        switchRequests.value = profile.id
        if (rescheduleJobs) {
            LibraryUpdateJob.setupTask(application)
        }
    }

    suspend fun createProfile(name: String): Profile {
        val position = profiles.value.maxOfOrNull(Profile::position)?.plus(1) ?: 1L
        val id = profileDatabase.insertProfile(
            uuid = newUuid(),
            name = name,
            colorSeed = Random.nextLong(0x00FFFFFF),
            position = position,
            requiresAuth = false,
            isArchived = false,
        )
        clearProfileState(id)
        val hiddenSourceIds = extensionManager.installedExtensionsFlow.value
            .flatMap { extension -> extension.sources.map { source -> source.id.toString() } }
            .toSet()
        profileStore.profileStore(id)
            .getStringSet(
                "hidden_catalogues",
                hiddenSourceIds,
            )
            .set(hiddenSourceIds)
        return requireNotNull(profileDatabase.getProfileById(id))
    }

    suspend fun renameProfile(profileId: Long, name: String) {
        profileDatabase.updateProfile(id = profileId, name = name)
    }

    suspend fun setProfileArchived(profileId: Long, archived: Boolean) {
        if (profileId == ProfileConstants.defaultProfileId && archived) return
        if (archived && activeProfileId == profileId) return
        profileDatabase.updateProfile(id = profileId, isArchived = archived)
    }

    suspend fun permanentlyDeleteProfile(profileId: Long) {
        if (profileId == ProfileConstants.defaultProfileId) return
        val profile = profileDatabase.getProfileById(profileId) ?: return
        if (!profile.isArchived) return
        val fallback = visibleProfiles.value.firstOrNull { it.id != profileId }
            ?: profileDatabase.getProfileById(ProfileConstants.defaultProfileId)
        if (activeProfileId == profileId && fallback != null) {
            setActiveProfile(fallback.id)
        }
        clearProfileState(profileId)
        profileDatabase.deleteProfile(profileId)
    }

    private suspend fun clearProfileState(profileId: Long) {
        profileDatabase.clearProfileData(profileId)
        profileStore.deleteProfileState(profileId)
    }

    fun profileRequiresUnlock(profileId: Long): Boolean {
        return SecurityPreferences(profileStore.profileStore(profileId)).useAuthenticator.get()
    }

    private suspend fun migrateLegacyPreferencesIfNeeded() {
        val currentVersion = profilesPreferences.legacyPreferenceMigrationVersion.get()
        if (currentVersion >= LEGACY_PROFILE_MIGRATION_VERSION) return

        correctProfileOwnershipMismatches(currentVersion)
        val ownership = ProfilePreferenceOwnership.derive()

        val migration = ProfilePreferenceMigration(
            PreferenceManager.getDefaultSharedPreferences(application),
        )
        migration.migrateLegacyPreferenceKeys(
            profileId = ProfileConstants.defaultProfileId,
            profileKeys = ownership.profile,
            appStateKeys = ownership.appState,
            privateKeys = ownership.private,
        )
        migrateLegacyProfileUnlockSettings()
        migrateLegacySourcePreferences()
        migration.cleanupLegacyPreferenceKeys(
            profileId = ProfileConstants.defaultProfileId,
            profileKeys = ownership.profile,
            appStateKeys = ownership.appState,
            privateKeys = ownership.private,
        )
        profilesPreferences.legacyPreferenceMigrationVersion.set(LEGACY_PROFILE_MIGRATION_VERSION)
    }

    private suspend fun migrateLegacyProfileUnlockSettings() {
        profileDatabase.getProfiles(includeArchived = true).forEach { profile ->
            if (profile.requiresAuth) {
                SecurityPreferences(profileStore.profileStore(profile.id)).useAuthenticator.set(true)
                profileDatabase.updateProfile(id = profile.id, requiresAuth = false)
            }
        }
    }

    private fun correctProfileOwnershipMismatches(currentVersion: Int) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
        if (currentVersion < 2) {
            migrateProfileOwnedKeyToNamespaced(
                sharedPreferences = sharedPreferences,
                baseKey = "show_nsfw_source",
            )
            migrateProfileOwnedKeyToNamespaced(
                sharedPreferences = sharedPreferences,
                baseKey = Preference.appStateKey("home_screen_startup_tab"),
            )
        }

        migrateKeyBackToGlobal(
            sharedPreferences = sharedPreferences,
            baseKey = "mark_duplicate_read_chapter_read",
        )
        migrateKeyBackToGlobal(
            sharedPreferences = sharedPreferences,
            baseKey = "anilist_score_type",
        )
        migrateKeyBackToGlobal(
            sharedPreferences = sharedPreferences,
            baseKey = "auto_clear_chapter_cache",
        )
        migrateKeyBackToGlobal(
            sharedPreferences = sharedPreferences,
            baseKey = "disallow_non_ascii_filenames",
        )
    }

    private fun migrateProfileOwnedKeyToNamespaced(
        sharedPreferences: android.content.SharedPreferences,
        baseKey: String,
    ) {
        val namespacedKey = ProfileAwarePreferenceStore.Namespace.namespacedKey(baseKey, ProfileConstants.defaultProfileId)
        if (!sharedPreferences.contains(baseKey) || sharedPreferences.contains(namespacedKey)) return

        sharedPreferences.edit(commit = true) {
            when (val value = sharedPreferences.all[baseKey]) {
                is String -> putString(namespacedKey, value)
                is Int -> putInt(namespacedKey, value)
                is Long -> putLong(namespacedKey, value)
                is Float -> putFloat(namespacedKey, value)
                is Boolean -> putBoolean(namespacedKey, value)
                is Set<*> -> putStringSet(namespacedKey, value.filterIsInstance<String>().toSet())
            }
            remove(baseKey)
        }
    }

    private fun migrateKeyBackToGlobal(
        sharedPreferences: android.content.SharedPreferences,
        baseKey: String,
    ) {
        val namespacedKey = ProfileAwarePreferenceStore.Namespace.namespacedKey(baseKey, ProfileConstants.defaultProfileId)
        if (!sharedPreferences.contains(namespacedKey)) return

        sharedPreferences.edit(commit = true) {
            if (!sharedPreferences.contains(baseKey)) {
                when (val value = sharedPreferences.all[namespacedKey]) {
                    is String -> putString(baseKey, value)
                    is Int -> putInt(baseKey, value)
                    is Long -> putLong(baseKey, value)
                    is Float -> putFloat(baseKey, value)
                    is Boolean -> putBoolean(baseKey, value)
                    is Set<*> -> putStringSet(baseKey, value.filterIsInstance<String>().toSet())
                }
            }
            remove(namespacedKey)
        }
    }

    private fun migrateLegacySourcePreferences() {
        val sharedPrefsDir = File(application.applicationInfo.dataDir, "shared_prefs")
        val sourceIds = sharedPrefsDir.listFiles()
            ?.asSequence()
            ?.map { it.name.removeSuffix(".xml") }
            ?.mapNotNull { name ->
                name.removePrefix("source_")
                    .takeIf { name.startsWith("source_") }
                    ?.toLongOrNull()
            }
            ?.toSet()
            .orEmpty()

        sourceIds.forEach { sourceId ->
            migrateLegacySourcePreferences(sourceId)
        }
    }

    private fun migrateLegacySourcePreferences(sourceId: Long) {
        val legacyName = "source_$sourceId"
        val legacyPrefs = application.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        if (legacyPrefs.all.isEmpty()) return

        val targetName = profileStore.sourcePreferenceKey(sourceId, ProfileConstants.defaultProfileId)
        val targetPrefs = application.getSharedPreferences(targetName, Context.MODE_PRIVATE)

        targetPrefs.edit(commit = true) {
            legacyPrefs.all.forEach { (key, value) ->
                if (targetPrefs.contains(key)) return@forEach

                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }

        if (targetPrefs.all.isNotEmpty()) {
            application.deleteSharedPreferences(legacyName)
        }
    }

    suspend fun getProfileBundles(includeArchived: Boolean = true): List<ProfileBundle> {
        return profileDatabase.getProfiles(includeArchived).map { profile ->
            ProfileBundle(
                profile = profile.copy(requiresAuth = profileRequiresUnlock(profile.id)),
                categories = profileDatabase.getAllCategories(profile.id).map { it.id },
                mangaCount = profileDatabase.getMangaCount(profile.id).toInt(),
            )
        }
    }

    fun storePendingIntent(intent: Intent?) {
        if (intent == null) {
            profilesPreferences.pendingIntentUri.delete()
            return
        }
        profilesPreferences.pendingIntentUri.set(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    fun consumePendingIntent(): Intent? {
        val intentUri = profilesPreferences.pendingIntentUri.get()
            .takeIf { it.isNotBlank() }
            ?: return null
        profilesPreferences.pendingIntentUri.delete()
        return runCatching {
            Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
        }.getOrNull()
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newUuid(): String {
        return Uuid.random().toString()
    }

    companion object {
        private const val LEGACY_PROFILE_MIGRATION_VERSION = 4
    }
}
