package eu.kanade.tachiyomi.ui.anime.updates

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.addOrRemove
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.updates.UpdatesSelectionState
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.presentation.updates.toUpdatesUiModels
import eu.kanade.tachiyomi.data.library.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetAnimeUpdates
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisodeUpdate
import tachiyomi.domain.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.anime.repository.AnimeEpisodeRepository
import tachiyomi.domain.anime.repository.AnimePlaybackStateRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime

class AnimeUpdatesScreenModel(
    private val getAnime: GetAnime = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = Injekt.get(),
    private val getAnimeUpdates: GetAnimeUpdates = Injekt.get(),
    private val animeEpisodeRepository: AnimeEpisodeRepository = Injekt.get(),
    private val animePlaybackStateRepository: AnimePlaybackStateRepository = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    private val application: Application = Injekt.get(),
) : StateScreenModel<AnimeUpdatesScreenModel.State>(State()) {

    private val after = ZonedDateTime.now().minusMonths(3).toInstant()
    val lastUpdated: Long
        get() = libraryPreferences.lastUpdatedTimestamp.get()

    private val selectionState = UpdatesSelectionState()
    private val selectedEpisodeIds = hashSetOf<Long>()

    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            getUpdatesItemPreferenceFlow()
                .distinctUntilChanged()
                .flatMapLatest {
                    getAnimeUpdates.subscribe(
                        instant = after,
                        unread = it.filterUnread.toBooleanOrNull(),
                        started = it.filterStarted.toBooleanOrNull(),
                    )
                }
                .catch { error ->
                    logcat(LogPriority.ERROR, error)
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = withUIContext { application.stringResource(MR.strings.unknown_error) },
                        )
                    }
                }
                .collectLatest { updates ->
                    val items = updates.toUpdateItems().toPersistentList()

                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            items = items,
                        )
                    }
                }
        }

        getUpdatesItemPreferenceFlow()
            .map { prefs ->
                listOf(
                    prefs.filterUnread,
                    prefs.filterStarted,
                ).any { it != TriState.DISABLED }
            }
            .distinctUntilChanged()
            .onEach { hasActiveFilters ->
                mutableState.update { state -> state.copy(hasActiveFilters = hasActiveFilters) }
            }
            .launchIn(screenModelScope)
    }

    fun updateLibrary(): Boolean {
        val started = AnimeLibraryUpdateJob.startNow(Injekt.get<Application>())
        screenModelScope.launch {
            _events.send(Event.LibraryUpdateTriggered(started))
        }
        return started
    }

    fun markUpdatesWatched(updates: List<AnimeUpdatesItem>, watched: Boolean) {
        if (updates.isEmpty()) return

        screenModelScope.launchNonCancellable {
            val episodeUpdates = updates.mapNotNull { item ->
                animeEpisodeRepository.getEpisodeById(item.update.episodeId)
                    ?.takeIf { episode ->
                        when (watched) {
                            true -> !episode.completed || !episode.watched
                            false -> episode.completed || episode.watched
                        }
                    }
                    ?.let { episode ->
                        AnimeEpisodeUpdate(
                            id = episode.id,
                            watched = watched,
                            completed = watched,
                        )
                    }
            }
            if (episodeUpdates.isNotEmpty()) {
                animeEpisodeRepository.updateAll(episodeUpdates)
            }

            updates.forEach { item ->
                animePlaybackStateRepository.getByEpisodeId(item.update.episodeId)
                    ?.let { playbackState ->
                        animePlaybackStateRepository.upsert(
                            playbackState.copy(
                                positionMs = if (watched) playbackState.positionMs else 0L,
                                completed = watched,
                            ),
                        )
                    }
            }
        }

        toggleAllSelection(false)
    }

    fun toggleSelection(
        item: AnimeUpdatesItem,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        mutableState.update { state ->
            val newItems = state.items.toMutableList().apply {
                val selectedIndex = indexOfFirst { it.update.episodeId == item.update.episodeId }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if (selectedItem.selected == selected) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedEpisodeIds.addOrRemove(item.update.episodeId, selected)

                if (selected && fromLongPress) {
                    selectionState.updateRangeSelection(selectedIndex, firstSelection).forEach {
                        val inbetweenItem = get(it)
                        if (!inbetweenItem.selected) {
                            selectedEpisodeIds.add(inbetweenItem.update.episodeId)
                            set(it, inbetweenItem.copy(selected = true))
                        }
                    }
                } else if (!fromLongPress) {
                    selectionState.updateSelectionBounds(
                        selectedIndex = selectedIndex,
                        selected = selected,
                        firstSelectedIndex = indexOfFirst { it.selected },
                        lastSelectedIndex = indexOfLast { it.selected },
                    )
                }
            }
            state.copy(items = newItems.toPersistentList())
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedEpisodeIds.addOrRemove(it.update.episodeId, selected)
                it.copy(selected = selected)
            }
            state.copy(items = newItems.toPersistentList())
        }

        selectionState.reset()
    }

    fun invertSelection() {
        mutableState.update { state ->
            val newItems = state.items.map {
                selectedEpisodeIds.addOrRemove(it.update.episodeId, !it.selected)
                it.copy(selected = !it.selected)
            }
            state.copy(items = newItems.toPersistentList())
        }
        selectionState.reset()
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun showFilterDialog() {
        mutableState.update { it.copy(dialog = Dialog.FilterSheet) }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount.set(0)
    }

    suspend fun getVisibleAnimeId(animeId: Long): Long {
        return getMergedAnime.awaitVisibleTargetId(animeId)
    }

    private suspend fun List<AnimeUpdatesWithRelations>.toUpdateItems(): List<AnimeUpdatesItem> {
        val visibleTargetCache = mutableMapOf<Long, Long>()
        val visibleAnimeTitleCache = mutableMapOf<Long, Pair<String, MangaCover>?>()

        return map { update ->
            val visibleAnimeId = visibleTargetCache.getOrPut(update.animeId) {
                getMergedAnime.awaitVisibleTargetId(update.animeId)
            }
            val visibleAnime = visibleAnimeTitleCache.getOrPut(visibleAnimeId) {
                getAnime.await(visibleAnimeId)?.let { anime ->
                    anime.displayTitle to anime.toMangaCover()
                }
            }
            AnimeUpdatesItem(
                update = update,
                visibleAnimeId = visibleAnimeId,
                visibleAnimeTitle = visibleAnime?.first ?: update.animeTitle,
                visibleCoverData = visibleAnime?.second ?: update.coverData,
                selected = update.episodeId in selectedEpisodeIds,
            )
        }
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterUnread.changes(),
            updatesPreferences.filterStarted.changes(),
        ) { unread, started ->
            ItemPreferences(
                filterUnread = unread,
                filterStarted = started,
            )
        }
    }

    @Immutable
    private data class ItemPreferences(
        val filterUnread: TriState,
        val filterStarted: TriState,
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val hasActiveFilters: Boolean = false,
        val error: String? = null,
        val items: PersistentList<AnimeUpdatesItem> = persistentListOf(),
        val dialog: Dialog? = null,
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()

        fun getUiModel(): List<UpdatesUiModel<AnimeUpdatesItem>> {
            return items.toUpdatesUiModels { it.update.dateFetch.toLocalDate() }
        }
    }

    sealed interface Dialog {
        data object FilterSheet : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
        data class LibraryUpdateTriggered(val started: Boolean) : Event
    }
}

@Immutable
data class AnimeUpdatesItem(
    val update: AnimeUpdatesWithRelations,
    val visibleAnimeId: Long,
    val visibleAnimeTitle: String,
    val visibleCoverData: MangaCover,
    val selected: Boolean = false,
)

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}
