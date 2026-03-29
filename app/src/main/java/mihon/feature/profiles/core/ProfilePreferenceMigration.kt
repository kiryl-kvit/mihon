package mihon.feature.profiles.core

import android.content.SharedPreferences
import androidx.core.content.edit
import tachiyomi.core.common.preference.Preference

class ProfilePreferenceMigration(
    private val sharedPreferences: SharedPreferences,
) {
    fun migrateLegacyPreferenceKeys(
        profileId: Long,
        profileKeys: Set<String>,
        appStateKeys: Set<String> = emptySet(),
        privateKeys: Set<String> = emptySet(),
    ) {
        sharedPreferences.edit(commit = true) {
            profileKeys.forEach { key ->
                migrateKey(
                    key,
                    targetKey = ProfileAwarePreferenceStore.Namespace.namespacedKey(key, profileId),
                    editor = this,
                )
            }
            appStateKeys.forEach { key ->
                migrateKey(
                    Preference.appStateKey(key),
                    targetKey = ProfileAwarePreferenceStore.Namespace.namespacedKey(Preference.appStateKey(key), profileId),
                    editor = this,
                )
            }
            privateKeys.forEach { key ->
                migrateKey(
                    Preference.privateKey(key),
                    targetKey = ProfileAwarePreferenceStore.Namespace.namespacedKey(Preference.privateKey(key), profileId),
                    editor = this,
                )
            }
        }
    }

    private fun migrateKey(
        sourceKey: String,
        targetKey: String,
        editor: SharedPreferences.Editor,
    ) {
        if (!sharedPreferences.contains(sourceKey)) return
        if (sharedPreferences.contains(targetKey)) return
        when (val value = sharedPreferences.all[sourceKey]) {
            is String -> editor.putString(targetKey, value)
            is Int -> editor.putInt(targetKey, value)
            is Long -> editor.putLong(targetKey, value)
            is Float -> editor.putFloat(targetKey, value)
            is Boolean -> editor.putBoolean(targetKey, value)
            is Set<*> -> editor.putStringSet(targetKey, value.filterIsInstance<String>().toSet())
        }
    }
}
