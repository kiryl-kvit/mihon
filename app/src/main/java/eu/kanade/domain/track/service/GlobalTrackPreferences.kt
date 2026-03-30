package eu.kanade.domain.track.service

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class GlobalTrackPreferences(
    preferenceStore: PreferenceStore,
) {
    val anilistScoreType: Preference<String> = preferenceStore.getString("anilist_score_type", Anilist.POINT_10)
}
