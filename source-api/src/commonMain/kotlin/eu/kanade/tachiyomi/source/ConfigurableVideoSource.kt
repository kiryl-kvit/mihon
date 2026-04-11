package eu.kanade.tachiyomi.source

import android.content.SharedPreferences

interface ConfigurableVideoSource : VideoSource {

    /**
     * Gets instance of [SharedPreferences] scoped to the specific video source.
     */
    fun getSourcePreferences(): SharedPreferences =
        sourcePreferences(videoPreferenceKey())

    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableVideoSource.videoPreferenceKey(): String = getVideoSourcePreferenceProvider().key(id)

fun ConfigurableVideoSource.videoSourcePreferences(): SharedPreferences =
    getVideoSourcePreferenceProvider().preferences(id)
