package eu.kanade.tachiyomi.source

import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface SourcePreferenceProvider {
    fun key(sourceId: Long): String
    fun preferences(sourceId: Long): SharedPreferences
}

object DefaultSourcePreferenceProvider : SourcePreferenceProvider {
    override fun key(sourceId: Long): String = "source_$sourceId"

    override fun preferences(sourceId: Long): SharedPreferences {
        return sourcePreferences(key(sourceId))
    }
}

fun getSourcePreferenceProvider(): SourcePreferenceProvider {
    return runCatching { Injekt.get<SourcePreferenceProvider>() }
        .getOrElse { DefaultSourcePreferenceProvider }
}
