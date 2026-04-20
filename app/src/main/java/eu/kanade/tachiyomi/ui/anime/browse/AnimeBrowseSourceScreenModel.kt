package eu.kanade.tachiyomi.ui.anime.browse

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.anime.AnimeMergeTarget
import eu.kanade.presentation.anime.buildAnimeMergeTargets
import eu.kanade.presentation.anime.toMergeEditorEntry
import eu.kanade.presentation.manga.components.MergeEditorEntry
import eu.kanade.presentation.manga.components.buildMergeTargetQuery
import eu.kanade.presentation.manga.components.rankMergeTargets
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.source.AnimeCatalogueSource
import eu.kanade.tachiyomi.source.AsyncAnimeCatalogueFilterSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.resolveFilterList
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.anime.interactor.UpdateMergedAnime
import tachiyomi.domain.anime.model.AnimeMerge
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.model.AnimeTitleUpdate
import tachiyomi.domain.anime.model.DuplicateAnimeCandidate
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.service.DuplicatePreferences
import tachiyomi.domain.source.interactor.GetRemoteAnime
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

class AnimeBrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val getRemoteAnime: GetRemoteAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = Injekt.get(),
    private val duplicatePreferences: DuplicatePreferences = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val updateMergedAnime: UpdateMergedAnime = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val application: Application = Injekt.get(),
) : StateScreenModel<AnimeBrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)

    val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource

    init {
        source?.let { animeSource ->
            if (animeSource is AsyncAnimeCatalogueFilterSource && state.value.listing is Listing.Search) {
                mutableState.update {
                    it.copy(
                        filterState = BrowseFilterUiState.Loading,
                        isWaitingForInitialFilterLoad = true,
                    )
                }
            }

            screenModelScope.launchIO {
                loadFilters()
            }

            if (!getIncognitoState.await(animeSource.id)) {
                sourcePreferences.lastUsedAnimeSource.set(animeSource.id)
            }
        }
    }

    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val animePagerFlow = state.map { it.listing to it.isWaitingForInitialFilterLoad }
        .distinctUntilChanged()
        .map { (listing, isWaitingForInitialFilterLoad) ->
            if (isWaitingForInitialFilterLoad) {
                emptyFlow()
            } else {
                Pager(PagingConfig(pageSize = 25)) {
                    getRemoteAnime(sourceId, listing.query ?: "", listing.filters)
                }.flow.map { pagingData ->
                    pagingData.map { anime ->
                        getAnime.subscribe(anime.url, anime.source)
                            .map { it ?: anime }
                            .stateIn(ioCoroutineScope)
                    }
                        .filter { !hideInLibraryItems || !it.value.favorite }
                }
                    .cachedIn(ioCoroutineScope)
            }
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        if (source == null) return
        screenModelScope.launchIO {
            loadFilters()
        }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source == null) return
        mutableState.update { it.copy(filters = filters) }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source == null) return
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = state.value.filters)

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun openFilterSheet() {
        if (source == null) return
        mutableState.update { it.copy(dialog = Dialog.Filter) }
        if (state.value.filterState is BrowseFilterUiState.Uninitialized) {
            screenModelScope.launchIO {
                loadFilters()
            }
        }
    }

    fun retryFilterLoad() {
        if (source == null) return
        screenModelScope.launchIO {
            loadFilters()
        }
    }

    private suspend fun loadFilters() {
        val animeSource = source ?: return

        mutableState.update { it.copy(filterState = BrowseFilterUiState.Loading) }

        runCatching {
            animeSource.resolveFilterList()
        }.onSuccess { filters ->
            mutableState.update { currentState ->
                val updatedListing = when (val listing = currentState.listing) {
                    is Listing.Search -> listing.copy(filters = filters)
                    else -> listing
                }
                currentState.copy(
                    filters = filters,
                    listing = updatedListing,
                    filterState = BrowseFilterUiState.Ready,
                    isWaitingForInitialFilterLoad = false,
                )
            }
        }.onFailure { throwable ->
            mutableState.update {
                it.copy(
                    filterState = BrowseFilterUiState.Error(throwable),
                    isWaitingForInitialFilterLoad = it.isWaitingForInitialFilterLoad,
                )
            }
        }
    }

    fun onAnimeLibraryAction(anime: AnimeTitle) {
        screenModelScope.launchIO {
            showLibraryActionChooserOrHandle(anime)
        }
    }

    fun confirmBrowseLibraryAction(anime: AnimeTitle) {
        screenModelScope.launchIO {
            handleAnimeLibraryAction(anime)
        }
    }

    suspend fun onAnimeLongClick(anime: AnimeTitle): Boolean {
        showLibraryActionChooserOrHandle(anime)
        return true
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun addFavorite(anime: AnimeTitle) {
        screenModelScope.launchIO {
            addFavoriteInternal(anime)
        }
    }

    fun changeAnimeFavorite(anime: AnimeTitle) {
        screenModelScope.launchIO {
            val favorite = !anime.favorite
            if (favorite) {
                setAnimeDefaultEpisodeFlags.await(anime)
            }
            animeRepository.update(
                AnimeTitleUpdate(
                    id = anime.id,
                    favorite = favorite,
                    dateAdded = if (favorite) {
                        System.currentTimeMillis()
                    } else {
                        0L
                    },
                ),
            )
        }
    }

    fun moveAnimeToCategories(anime: AnimeTitle, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(anime.id, categoryIds)
        }
    }

    private suspend fun handleAnimeLibraryAction(anime: AnimeTitle) {
        val duplicates = getDuplicateLibraryAnime(anime)
        when {
            anime.favorite -> setDialog(Dialog.RemoveAnime(anime))
            duplicates.isNotEmpty() -> setDialog(Dialog.DuplicateAnime(anime, duplicates))
            else -> addFavoriteInternal(anime)
        }
    }

    fun showMergeTargetPicker(anime: AnimeTitle) {
        screenModelScope.launchIO {
            val targets = getMergeTargets(excludedAnimeIds = getMergeMemberIds(anime.id).toSet())
            if (targets.isEmpty()) return@launchIO
            val query = buildMergeTargetQuery(anime.displayTitle, duplicatePreferences)
            val visibleTargets = rankMergeTargets(targets, query).toImmutableList()
            setDialog(
                Dialog.SelectMergeTarget(
                    anime = anime,
                    query = query,
                    targets = targets,
                    visibleTargets = visibleTargets,
                ),
            )
        }
    }

    fun updateMergeTargetQuery(query: String) {
        val dialog = state.value.dialog as? Dialog.SelectMergeTarget ?: return
        val visibleTargets = rankMergeTargets(dialog.targets, query).toImmutableList()
        setDialog(dialog.copy(query = query, visibleTargets = visibleTargets))
    }

    fun openMergeEditor(targetId: Long) {
        val dialog = state.value.dialog as? Dialog.SelectMergeTarget ?: return
        screenModelScope.launchIO {
            val target = dialog.targets.firstOrNull { it.id == targetId } ?: return@launchIO
            setDialog(createMergeEditorDialog(dialog.anime, target))
        }
    }

    fun openMergeEditorForDuplicate(targetId: Long) {
        val dialog = state.value.dialog as? Dialog.DuplicateAnime ?: return
        screenModelScope.launchIO {
            val target = getMergeTargets(excludedAnimeIds = getMergeMemberIds(dialog.anime.id).toSet())
                .firstOrNull { it.id == targetId }
                ?: return@launchIO
            setDialog(createMergeEditorDialog(dialog.anime, target))
        }
    }

    fun moveMergeEntry(fromIndex: Int, toIndex: Int) {
        val dialog = state.value.dialog as? Dialog.EditMerge ?: return
        val entries = dialog.entries.toMutableList()
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return
        val entry = entries.removeAt(fromIndex)
        entries.add(toIndex, entry)
        setDialog(dialog.copy(entries = entries.toImmutableList()))
    }

    fun setMergeTarget(animeId: Long) {
        val dialog = state.value.dialog as? Dialog.EditMerge ?: return
        if (dialog.targetLocked || dialog.entries.none { it.id == animeId }) return
        setDialog(
            dialog.copy(
                targetId = animeId,
                removedIds = dialog.removedIds - animeId,
                libraryRemovalIds = dialog.libraryRemovalIds - animeId,
            ),
        )
    }

    fun toggleMergeEntryRemoval(animeId: Long) {
        val dialog = state.value.dialog as? Dialog.EditMerge ?: return
        val entry = dialog.entries.firstOrNull { it.id == animeId } ?: return
        if (!entry.isRemovable || animeId == dialog.targetId) return
        val removedIds = dialog.removedIds.toMutableSet().apply {
            if (!add(animeId)) remove(animeId)
        }
        setDialog(dialog.copy(removedIds = removedIds))
    }

    fun toggleMergeEntryLibraryRemoval(animeId: Long) {
        val dialog = state.value.dialog as? Dialog.EditMerge ?: return
        val entry = dialog.entries.firstOrNull { it.id == animeId } ?: return
        if (!entry.isRemovable || animeId == dialog.targetId) return
        val libraryRemovalIds = dialog.libraryRemovalIds.toMutableSet().apply {
            if (!add(animeId)) remove(animeId)
        }
        setDialog(dialog.copy(libraryRemovalIds = libraryRemovalIds))
    }

    fun confirmBrowseMerge() {
        val dialog = state.value.dialog as? Dialog.EditMerge ?: return
        screenModelScope.launchIO {
            val localAnime = networkToLocalAnime(dialog.anime)
            ensureFavorite(localAnime, dialog.categoryIds)

            val orderedIds = dialog.entries
                .filterNot { it.id in (dialog.removedIds + dialog.libraryRemovalIds) }
                .map(MergeEditorEntry::id)
                .distinct()

            if (orderedIds.size > 1) {
                updateMergedAnime.awaitMerge(dialog.targetId, orderedIds)
            }
            removeMembersFromLibrary(dialog.libraryRemovalIds)
            dismissDialog()
        }
    }

    private suspend fun addFavoriteInternal(anime: AnimeTitle) {
        val categories = getCategories()
        val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
        val defaultCategory = categories.find { it.id == defaultCategoryId }

        when {
            defaultCategory != null -> {
                setAnimeCategories.await(anime.id, listOf(defaultCategory.id))
                updateFavorite(anime, true)
            }
            defaultCategoryId == 0L || categories.isEmpty() -> {
                setAnimeCategories.await(anime.id, emptyList())
                updateFavorite(anime, true)
            }
            else -> {
                val preselectedIds = getAnimeCategories.await(anime.id).map { it.id }
                setDialog(
                    Dialog.ChangeAnimeCategory(
                        anime = anime,
                        initialSelection = categories
                            .mapAsCheckboxState { it.id in preselectedIds }
                            .toImmutableList(),
                    ),
                )
            }
        }
    }

    private suspend fun updateFavorite(anime: AnimeTitle, favorite: Boolean) {
        if (favorite) {
            setAnimeDefaultEpisodeFlags.await(anime)
        }
        animeRepository.update(
            AnimeTitleUpdate(
                id = anime.id,
                favorite = favorite,
                dateAdded = if (favorite) {
                    System.currentTimeMillis()
                } else {
                    0L
                },
            ),
        )
    }

    private suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    private suspend fun showLibraryActionChooserOrHandle(anime: AnimeTitle) {
        if (animeRepository.getFavorites().isEmpty()) {
            handleAnimeLibraryAction(anime)
        } else {
            setDialog(Dialog.LibraryActionChooser(anime))
        }
    }

    private suspend fun getMergeMemberIds(animeId: Long): List<Long> {
        return getMergedAnime.awaitGroupByAnimeId(animeId)
            .sortedBy(AnimeMerge::position)
            .map(AnimeMerge::animeId)
            .ifEmpty { listOf(animeId) }
    }

    private suspend fun getMergeTargets(excludedAnimeIds: Set<Long>): ImmutableList<AnimeMergeTarget> {
        val libraryAnime = animeRepository.getFavorites()
        val categoryIdsByAnimeId = libraryAnime.associate { anime ->
            anime.id to getAnimeCategories.await(anime.id).map(Category::id)
        }

        return buildAnimeMergeTargets(
            libraryAnime = libraryAnime,
            merges = getMergedAnime.awaitAll(),
            categoryIdsByAnimeId = categoryIdsByAnimeId,
            sourceNameForId = ::getSourceNameForId,
            multiSourceName = application.stringResource(MR.strings.multi_lang),
            excludedAnimeIds = excludedAnimeIds,
        )
    }

    private suspend fun createMergeEditorDialog(
        anime: AnimeTitle,
        target: AnimeMergeTarget,
    ): Dialog.EditMerge {
        val localAnime = networkToLocalAnime(anime)
        val orderedMembers = if (target.isMerged) {
            val membersById = target.memberAnimes.associateBy(AnimeTitle::id)
            getMergedAnime.awaitGroupByTargetId(target.id)
                .sortedBy(AnimeMerge::position)
                .mapNotNull { merge -> membersById[merge.animeId] }
                .ifEmpty { target.memberAnimes }
        } else {
            target.memberAnimes
        }

        val entries = buildList {
            if (target.isMerged && orderedMembers.none { it.id == localAnime.id }) {
                add(
                    localAnime.toMergeEditorEntry(
                        subtitle = buildMergeSubtitle(localAnime) + " • New",
                    ),
                )
            }
            orderedMembers.forEach { member ->
                add(
                    member.toMergeEditorEntry(
                        subtitle = buildMergeSubtitle(member),
                        isRemovable = true,
                        isMember = true,
                    ),
                )
            }
            if (!target.isMerged && none { it.id == localAnime.id }) {
                add(
                    localAnime.toMergeEditorEntry(
                        subtitle = buildMergeSubtitle(localAnime) + " • New",
                    ),
                )
            }
        }.toImmutableList()

        return Dialog.EditMerge(
            anime = localAnime,
            targetId = target.id,
            targetLocked = false,
            entries = entries,
            removedIds = emptySet(),
            libraryRemovalIds = emptySet(),
            categoryIds = target.categoryIds,
        )
    }

    private suspend fun ensureFavorite(anime: AnimeTitle, categoryIds: List<Long>) {
        if (!anime.favorite) {
            setAnimeDefaultEpisodeFlags.await(anime)
            animeRepository.update(
                AnimeTitleUpdate(
                    id = anime.id,
                    favorite = true,
                    dateAdded = Instant.now().toEpochMilli(),
                ),
            )
        }

        setAnimeCategories.await(anime.id, categoryIds)
    }

    private suspend fun removeMembersFromLibrary(animeIds: Collection<Long>) {
        animeIds.distinct().forEach { animeId ->
            animeRepository.update(
                AnimeTitleUpdate(
                    id = animeId,
                    favorite = false,
                    dateAdded = 0L,
                ),
            )
        }
    }

    private fun getSourceNameForId(sourceId: Long): String {
        return animeSourceManager.get(sourceId)?.name
            ?: application.stringResource(MR.strings.source_not_installed, sourceId.toString())
    }

    private fun buildMergeSubtitle(anime: AnimeTitle): String {
        val sourceName = getSourceNameForId(anime.source)
        val creator = listOf(anime.director, anime.studio, anime.producer, anime.writer)
            .firstOrNull { !it.isNullOrBlank() }
        return buildString {
            append(sourceName)
            if (!creator.isNullOrBlank() && !creator.equals(sourceName, ignoreCase = true)) {
                append(" • ")
                append(creator)
            }
        }
    }

    private fun moveAnimeToCategories(anime: AnimeTitle, vararg categories: Category) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    private fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteAnime.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteAnime.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteAnime.QUERY_POPULAR -> Popular
                    GetRemoteAnime.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList())
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog

        data class LibraryActionChooser(val anime: AnimeTitle) : Dialog

        data class RemoveAnime(val anime: AnimeTitle) : Dialog

        data class DuplicateAnime(
            val anime: AnimeTitle,
            val duplicates: List<DuplicateAnimeCandidate>,
        ) : Dialog

        data class SelectMergeTarget(
            val anime: AnimeTitle,
            val query: String = "",
            val targets: ImmutableList<AnimeMergeTarget>,
            val visibleTargets: ImmutableList<AnimeMergeTarget>,
        ) : Dialog

        data class EditMerge(
            val anime: AnimeTitle,
            val targetId: Long,
            val targetLocked: Boolean,
            val entries: ImmutableList<MergeEditorEntry>,
            val removedIds: Set<Long>,
            val libraryRemovalIds: Set<Long>,
            val categoryIds: List<Long>,
        ) : Dialog {
            val enabled: Boolean
                get() = entries.count { it.id !in (removedIds + libraryRemovalIds) } > 1
        }

        data class ChangeAnimeCategory(
            val anime: AnimeTitle,
            val initialSelection: kotlinx.collections.immutable.ImmutableList<CheckboxState<Category>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val filterState: BrowseFilterUiState = BrowseFilterUiState.Uninitialized,
        val isWaitingForInitialFilterLoad: Boolean = false,
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
        val hasFilterCapability get() = filterState !is BrowseFilterUiState.Unavailable
    }
}

sealed interface BrowseFilterUiState {
    data object Uninitialized : BrowseFilterUiState
    data object Loading : BrowseFilterUiState
    data object Ready : BrowseFilterUiState
    data class Error(val throwable: Throwable) : BrowseFilterUiState
    data object Unavailable : BrowseFilterUiState
}
