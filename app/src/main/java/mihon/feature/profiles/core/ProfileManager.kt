package mihon.feature.profiles.core

import android.app.Application
import android.content.Intent
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
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

    val shouldShowPicker: Flow<Boolean> = visibleProfiles.map { it.size > 1 }

    suspend fun ensureDefaultProfile() {
        val defaultProfile = profileDatabase.getProfileById(ProfileConstants.defaultProfileId)
        if (defaultProfile != null) return

        profileDatabase.insertProfile(
            uuid = ProfileConstants.defaultProfileUuid,
            name = ProfileConstants.defaultProfileName,
            colorSeed = 0x4F6AF2,
            position = 0,
            requiresAuth = false,
            isArchived = false,
        )

        val migration = ProfilePreferenceMigration(
            PreferenceManager.getDefaultSharedPreferences(application),
        )
        migration.migrateLegacyPreferenceKeys(
            profileId = ProfileConstants.defaultProfileId,
            profileKeys = ProfileScopedPreferenceSets.profile,
            appStateKeys = ProfileScopedPreferenceSets.appState,
            privateKeys = ProfileScopedPreferenceSets.private,
        )
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

    suspend fun setProfileRequiresAuth(profileId: Long, enabled: Boolean) {
        profileDatabase.updateProfile(id = profileId, requiresAuth = enabled)
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

    suspend fun getProfileBundles(includeArchived: Boolean = true): List<ProfileBundle> {
        return profileDatabase.getProfiles(includeArchived).map { profile ->
            ProfileBundle(
                profile = profile,
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
}
