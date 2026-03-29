package eu.kanade.tachiyomi.source

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface ConfigurableSource : Source {

    /**
     * Gets instance of [SharedPreferences] scoped to the specific source.
     *
     * @since extensions-lib 1.5
     */
    fun getSourcePreferences(): SharedPreferences =
        sourcePreferences(preferenceKey())

    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableSource.preferenceKey(): String = getSourcePreferenceProvider().key(id)

// TODO: use getSourcePreferences once all extensions are on ext-lib 1.5
fun ConfigurableSource.sourcePreferences(): SharedPreferences = getSourcePreferenceProvider().preferences(id)

fun sourcePreferences(key: String): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences(key, Context.MODE_PRIVATE)
