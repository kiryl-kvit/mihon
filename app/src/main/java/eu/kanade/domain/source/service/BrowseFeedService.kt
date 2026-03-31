package eu.kanade.domain.source.service

import eu.kanade.domain.source.model.SourceFeed
import eu.kanade.domain.source.model.SourceFeedPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class BrowseFeedService(
    private val preferences: SourcePreferences,
) {

    fun stateSnapshot(): State {
        return State(
            presets = preferences.savedFeedPresets.get(),
            feeds = preferences.savedFeeds.get(),
            selectedFeedId = preferences.selectedFeedId.get().takeIf { it.isNotBlank() },
        )
    }

    fun presets(): Flow<List<SourceFeedPreset>> {
        return preferences.savedFeedPresets.changes()
    }

    fun feeds(): Flow<List<SourceFeed>> {
        return preferences.savedFeeds.changes()
    }

    fun state(): Flow<State> {
        return combine(
            presets(),
            feeds(),
            preferences.selectedFeedId.changes(),
        ) { presets, feeds, selectedFeedId ->
            State(
                presets = presets,
                feeds = feeds,
                selectedFeedId = selectedFeedId.takeIf { it.isNotBlank() },
            )
        }
    }

    fun savePreset(preset: SourceFeedPreset) {
        preferences.savedFeedPresets.set(
            preferences.savedFeedPresets.get()
                .filterNot { it.id == preset.id }
                .plus(preset)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
        )
    }

    fun removePreset(presetId: String) {
        preferences.savedFeedPresets.set(
            preferences.savedFeedPresets.get().filterNot { it.id == presetId },
        )

        val remainingFeeds = preferences.savedFeeds.get().filterNot { it.presetId == presetId }
        preferences.savedFeeds.set(remainingFeeds)

        val selectedFeedId = preferences.selectedFeedId.get()
        if (selectedFeedId.isNotBlank() && remainingFeeds.none { it.id == selectedFeedId }) {
            preferences.selectedFeedId.set(remainingFeeds.firstOrNull { it.enabled }?.id.orEmpty())
        }
    }

    fun createFeed(feed: SourceFeed) {
        preferences.savedFeeds.set(
            preferences.savedFeeds.get()
                .filterNot { it.id == feed.id }
                .plus(feed),
        )
        if (feed.enabled) {
            preferences.selectedFeedId.set(feed.id)
        }
    }

    fun updateFeed(feed: SourceFeed) {
        preferences.savedFeeds.set(
            preferences.savedFeeds.get().map { if (it.id == feed.id) feed else it },
        )
        if (feed.enabled) {
            preferences.selectedFeedId.set(feed.id)
        } else if (preferences.selectedFeedId.get() == feed.id) {
            preferences.selectedFeedId.set("")
        }
    }

    fun removeFeed(feedId: String) {
        preferences.savedFeeds.set(
            preferences.savedFeeds.get().filterNot { it.id == feedId },
        )
        if (preferences.selectedFeedId.get() == feedId) {
            preferences.selectedFeedId.set("")
        }
    }

    fun selectFeed(feedId: String) {
        preferences.selectedFeedId.set(feedId)
    }

    data class State(
        val presets: List<SourceFeedPreset>,
        val feeds: List<SourceFeed>,
        val selectedFeedId: String?,
    )
}
