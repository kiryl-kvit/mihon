package eu.kanade.tachiyomi.source

import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface VideoSourcePreferenceProvider {
    fun key(sourceId: Long): String
    fun preferences(sourceId: Long): SharedPreferences
}

object DefaultVideoSourcePreferenceProvider : VideoSourcePreferenceProvider {
    override fun key(sourceId: Long): String = "video_source_$sourceId"

    override fun preferences(sourceId: Long): SharedPreferences {
        return sourcePreferences(key(sourceId))
    }
}

fun getVideoSourcePreferenceProvider(): VideoSourcePreferenceProvider {
    return runCatching { Injekt.get<VideoSourcePreferenceProvider>() }
        .getOrElse { DefaultVideoSourcePreferenceProvider }
}
