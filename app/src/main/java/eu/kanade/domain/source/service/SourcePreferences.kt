package eu.kanade.domain.source.service

import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.model.SourceFeed
import eu.kanade.domain.source.model.SourceFeedAnchor
import eu.kanade.domain.source.model.SourceFeedPreset
import eu.kanade.domain.source.model.SourceFeedTimeline
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode

class SourcePreferences(
    private val preferenceStore: PreferenceStore,
    private val json: Json,
) {
    companion object {
        const val LEGACY_HIDDEN_SOURCES_KEY = "hidden_catalogues"
        const val MANGA_HIDDEN_SOURCES_KEY = "hidden_manga_catalogues"
        const val ANIME_HIDDEN_SOURCES_KEY = "hidden_anime_catalogues"
        const val MANGA_PINNED_SOURCES_KEY = "pinned_catalogues"
        const val ANIME_PINNED_SOURCES_KEY = "pinned_anime_catalogues"
    }

    val sourceDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet(MANGA_HIDDEN_SOURCES_KEY, emptySet())

    val disabledAnimeSources: Preference<Set<String>> = preferenceStore.getStringSet(
        ANIME_HIDDEN_SOURCES_KEY,
        emptySet(),
    )

    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet(MANGA_PINNED_SOURCES_KEY, emptySet())

    val pinnedAnimeSources: Preference<Set<String>> = preferenceStore.getStringSet(ANIME_PINNED_SOURCES_KEY, emptySet())

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    val lastUsedAnimeSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_anime_catalogue_source"),
        -1,
    )

    val showNsfwSource: Preference<Boolean> = preferenceStore.getBoolean("show_nsfw_source", true)

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    val globalSearchFilterState: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_chapters",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )

    val savedFeedPresets: Preference<List<SourceFeedPreset>> = preferenceStore.getObjectFromString(
        key = "saved_feed_presets",
        defaultValue = emptyList(),
        serializer = { json.encodeToString(it) },
        deserializer = { json.decodeFromString<List<SourceFeedPreset>>(it) },
    )

    val savedFeeds: Preference<List<SourceFeed>> = preferenceStore.getObjectFromString(
        key = "saved_source_feeds",
        defaultValue = emptyList(),
        serializer = { json.encodeToString(it) },
        deserializer = { json.decodeFromString<List<SourceFeed>>(it) },
    )

    val selectedFeedId: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("selected_source_feed_id"),
        "",
    )

    fun feedTimeline(feedId: String): Preference<SourceFeedTimeline> {
        return preferenceStore.getObjectFromString(
            key = Preference.appStateKey("source_feed_timeline_$feedId"),
            defaultValue = SourceFeedTimeline(),
            serializer = { json.encodeToString(it) },
            deserializer = { json.decodeFromString<SourceFeedTimeline>(it) },
        )
    }

    fun feedAnchor(feedId: String): Preference<SourceFeedAnchor> {
        return preferenceStore.getObjectFromString(
            key = Preference.appStateKey("source_feed_anchor_$feedId"),
            defaultValue = SourceFeedAnchor(),
            serializer = { json.encodeToString(it) },
            deserializer = { json.decodeFromString<SourceFeedAnchor>(it) },
        )
    }
}
