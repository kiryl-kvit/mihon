package eu.kanade.tachiyomi.ui.browse.source.browse

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
import eu.kanade.domain.manga.interactor.GetEnhancedDuplicateLibraryManga
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.model.BUILTIN_LATEST_PRESET_ID
import eu.kanade.domain.source.model.BUILTIN_POPULAR_PRESET_ID
import eu.kanade.domain.source.model.FeedListingMode
import eu.kanade.domain.source.model.FilterStateNode
import eu.kanade.domain.source.model.SourceFeedPreset
import eu.kanade.domain.source.model.applySnapshot
import eu.kanade.domain.source.model.latestFeedPreset
import eu.kanade.domain.source.model.popularFeedPreset
import eu.kanade.domain.source.model.snapshot
import eu.kanade.domain.source.service.BrowseFeedService
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.manga.components.MangaPreviewSizeUi
import eu.kanade.presentation.manga.components.MergeEditorEntry
import eu.kanade.presentation.manga.components.MergeTarget
import eu.kanade.presentation.manga.components.buildMergeTargets
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.common.CustomPreferences
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.UpdateMergedManga
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.UUID
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    initialFilterSnapshot: List<FilterStateNode> = emptyList(),
    private val sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    customPreferences: CustomPreferences = Injekt.get(),
    private val browseFeedService: BrowseFeedService = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getEnhancedDuplicateLibraryManga: GetEnhancedDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateMergedManga: UpdateMergedManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
) : StateScreenModel<BrowseSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode.asState(screenModelScope)
    var feedsEnabled by customPreferences.enableFeeds.asState(screenModelScope)
    val browseLongPressAction by customPreferences.browseLongPressAction.asState(screenModelScope)
    val isMangaPreviewEnabled by customPreferences.enableMangaPreview.asState(screenModelScope)
    val mangaPreviewSize by customPreferences.mangaPreviewSize.asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                it.initializeForSource(
                    sourceFilters = source.getFilterList(),
                    initialFilterSnapshot = initialFilterSnapshot,
                )
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()
    val mangaPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteManga(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map { manga ->
                    getManga.subscribe(manga.url, manga.source)
                        .map { it ?: manga }
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(ioCoroutineScope)
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
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        if (source !is CatalogueSource) return

        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

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

    fun searchGenre(genreName: String) {
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    fun onMangaLibraryAction(manga: Manga) {
        screenModelScope.launchIO {
            showLibraryActionChooserOrHandle(manga)
        }
    }

    fun confirmBrowseLibraryAction(manga: Manga) {
        screenModelScope.launchIO {
            handleMangaLibraryAction(manga)
        }
    }

    private suspend fun handleMangaLibraryAction(manga: Manga) {
        val duplicates = getDuplicateLibraryManga(manga)
        when {
            manga.favorite -> setDialog(Dialog.RemoveManga(manga))
            duplicates.isNotEmpty() -> setDialog(Dialog.AddDuplicateManga(manga, duplicates))
            else -> addFavorite(manga)
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<DuplicateMangaCandidate> {
        return getEnhancedDuplicateLibraryManga(manga)
    }

    suspend fun onMangaLongClick(manga: Manga): Boolean {
        return when (browseLongPressAction) {
            CustomPreferences.BrowseLongPressAction.LIBRARY_ACTION -> {
                showLibraryActionChooserOrHandle(manga)
                true
            }
            CustomPreferences.BrowseLongPressAction.MANGA_PREVIEW -> {
                if (!isMangaPreviewEnabled) {
                    false
                } else {
                    setDialog(Dialog.MangaPreview(manga.id))
                    true
                }
            }
        }
    }

    fun dismissDialog() {
        setDialog(null)
    }

    fun showMergeTargetPicker(manga: Manga) {
        screenModelScope.launchIO {
            val targets = buildMergeTargets(getLibraryManga.await(), sourceManager)
            if (targets.isEmpty()) return@launchIO
            setDialog(
                Dialog.SelectMergeTarget(
                    manga = manga,
                    targets = targets,
                    visibleTargets = targets,
                ),
            )
        }
    }

    fun updateMergeTargetQuery(query: String) {
        val dialog = state.value.dialog as? Dialog.SelectMergeTarget ?: return
        val trimmed = query.trim()
        val visibleTargets = if (trimmed.isBlank()) {
            dialog.targets
        } else {
            dialog.targets.filter { target ->
                target.entry.title.contains(trimmed, ignoreCase = true) ||
                    target.searchableTitle.contains(trimmed, ignoreCase = true)
            }.toImmutableList()
        }
        setDialog(dialog.copy(query = query, visibleTargets = visibleTargets))
    }

    fun openMergeEditor(targetId: Long) {
        val dialog = state.value.dialog as? Dialog.SelectMergeTarget ?: return
        screenModelScope.launchIO {
            val target = dialog.targets.firstOrNull { it.id == targetId } ?: return@launchIO
            setDialog(createBrowseMergeDialog(dialog.manga, target))
        }
    }

    fun moveMergeEntry(fromIndex: Int, toIndex: Int) {
        updateBrowseMergeDialog { dialog ->
            val entries = dialog.entries.toMutableList()
            if (fromIndex !in entries.indices || toIndex !in entries.indices) return@updateBrowseMergeDialog dialog
            val entry = entries.removeAt(fromIndex)
            entries.add(toIndex, entry)
            dialog.copy(entries = entries.toImmutableList())
        }
    }

    fun setMergeTarget(mangaId: Long) {
        updateBrowseMergeDialog { dialog ->
            if (dialog.targetLocked || dialog.entries.none { it.id == mangaId }) return@updateBrowseMergeDialog dialog
            dialog.copy(
                targetId = mangaId,
                removedIds = dialog.removedIds - mangaId,
                libraryRemovalIds = dialog.libraryRemovalIds - mangaId,
            )
        }
    }

    fun toggleMergeEntryRemoval(mangaId: Long) {
        updateBrowseMergeDialog { dialog ->
            val entry = dialog.entries.firstOrNull { it.id == mangaId } ?: return@updateBrowseMergeDialog dialog
            if (!entry.isRemovable || mangaId == dialog.targetId) return@updateBrowseMergeDialog dialog
            val removedIds = dialog.removedIds.toMutableSet().apply {
                if (!add(mangaId)) remove(mangaId)
            }
            dialog.copy(removedIds = removedIds)
        }
    }

    fun toggleMergeEntryLibraryRemoval(mangaId: Long) {
        updateBrowseMergeDialog { dialog ->
            val entry = dialog.entries.firstOrNull { it.id == mangaId } ?: return@updateBrowseMergeDialog dialog
            if (!entry.isRemovable || mangaId == dialog.targetId) return@updateBrowseMergeDialog dialog
            val libraryRemovalIds = dialog.libraryRemovalIds.toMutableSet().apply {
                if (!add(mangaId)) remove(mangaId)
            }
            dialog.copy(libraryRemovalIds = libraryRemovalIds)
        }
    }

    fun confirmBrowseMerge() {
        val dialog = state.value.dialog as? Dialog.EditMerge ?: return
        screenModelScope.launchIO {
            val remoteManga = networkToLocalManga(dialog.manga)
            ensureFavorite(remoteManga)
            setMangaCategories.await(remoteManga.id, dialog.categoryIds)

            val orderedIds = dialog.entries
                .filterNot { it.id in (dialog.removedIds + dialog.libraryRemovalIds) }
                .map(MergeEditorEntry::id)
                .distinct()

            if (orderedIds.size > 1) {
                updateMergedManga.awaitMerge(dialog.targetId, orderedIds)
            }
            removeMergedMembersFromLibrary(dialog.libraryRemovalIds)
            dismissDialog()
        }
    }

    fun mangaPreviewSizeUi(): MangaPreviewSizeUi {
        return when (mangaPreviewSize) {
            CustomPreferences.MangaPreviewSize.SMALL -> MangaPreviewSizeUi.SMALL
            CustomPreferences.MangaPreviewSize.MEDIUM -> MangaPreviewSizeUi.MEDIUM
            CustomPreferences.MangaPreviewSize.LARGE -> MangaPreviewSizeUi.LARGE
            CustomPreferences.MangaPreviewSize.EXTRA_LARGE -> MangaPreviewSizeUi.EXTRA_LARGE
        }
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun showSavePresetDialog() {
        if (!feedsEnabled) return
        setDialog(
            Dialog.SavePreset(
                mode = Dialog.SavePreset.Mode.Create,
                name = "",
                chronological = state.value.listing != Listing.Popular,
            ),
        )
    }

    fun showUpdateCurrentPresetDialog() {
        if (!feedsEnabled) return

        val preset = appliedCustomPreset() ?: return

        setDialog(
            Dialog.SavePreset(
                mode = Dialog.SavePreset.Mode.UpdateFromCurrentState,
                presetId = preset.id,
                name = preset.name,
                chronological = preset.chronological,
            ),
        )
    }

    fun showEditPresetDialog(presetId: String) {
        if (!feedsEnabled) return

        val preset = customPreset(presetId) ?: return

        setDialog(
            Dialog.SavePreset(
                mode = Dialog.SavePreset.Mode.EditMetadata,
                presetId = preset.id,
                name = preset.name,
                chronological = preset.chronological,
            ),
        )
    }

    fun appliedCustomPreset(): SourceFeedPreset? {
        if (!feedsEnabled) return null
        return customPreset(state.value.appliedCustomPresetId)
    }

    fun feedPresets(): List<SourceFeedPreset> {
        if (!feedsEnabled) return emptyList()

        val custom = browseFeedService.stateSnapshot().presets
            .filter { it.sourceId == sourceId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        if (source !is CatalogueSource) return custom

        return buildList {
            add(popularFeedPreset(sourceId, "Popular"))
            if (source.supportsLatest) {
                add(latestFeedPreset(sourceId, "Latest"))
            }
            addAll(custom)
        }
    }

    fun applyPreset(presetId: String) {
        if (!feedsEnabled) return
        if (source !is CatalogueSource) return

        val preset = feedPresets().firstOrNull { it.id == presetId } ?: return
        when (preset.listingMode) {
            FeedListingMode.Popular -> {
                resetFilters()
                setListing(Listing.Popular)
            }
            FeedListingMode.Latest -> {
                resetFilters()
                setListing(Listing.Latest)
            }
            FeedListingMode.Search -> {
                val filters = source.getFilterList().applySnapshot(preset.filters)
                setFilters(filters)
                search(
                    query = preset.query,
                    filters = filters,
                )
            }
        }

        mutableState.update {
            it.copy(
                appliedCustomPresetId = preset.id.takeIf(::canDeletePreset),
            )
        }
    }

    fun canDeletePreset(presetId: String): Boolean {
        return presetId != BUILTIN_POPULAR_PRESET_ID && presetId != BUILTIN_LATEST_PRESET_ID
    }

    fun removePreset(presetId: String) {
        if (!feedsEnabled) return
        if (!canDeletePreset(presetId)) return

        browseFeedService.removePreset(presetId)
        mutableState.update {
            if (it.appliedCustomPresetId == presetId) {
                it.copy(appliedCustomPresetId = null)
            } else {
                it
            }
        }
    }

    fun hasPresetName(name: String, excludingPresetId: String? = null): Boolean {
        if (!feedsEnabled) return false
        val trimmed = name.trim()
        return browseFeedService.stateSnapshot().presets.any {
            it.sourceId == sourceId && it.id != excludingPresetId && it.name.equals(trimmed, ignoreCase = true)
        }
    }

    fun savePreset(name: String, chronological: Boolean) {
        if (!feedsEnabled) return

        val trimmed = name.trim()
        if (trimmed.isBlank()) return

        val dialog = state.value.dialog as? Dialog.SavePreset ?: return
        when (dialog.mode) {
            Dialog.SavePreset.Mode.Create -> {
                if (source !is CatalogueSource) return

                val presetState = state.value.toSavedPresetState(defaultFilters = source.getFilterList())
                val preset = SourceFeedPreset(
                    id = UUID.randomUUID().toString(),
                    sourceId = sourceId,
                    name = trimmed,
                    listingMode = presetState.listingMode,
                    chronological = chronological,
                    query = presetState.query,
                    filters = presetState.filters,
                )
                browseFeedService.savePreset(preset)
                mutableState.update {
                    it.copy(
                        appliedCustomPresetId = preset.id,
                        dialog = null,
                    )
                }
            }
            Dialog.SavePreset.Mode.EditMetadata -> {
                val preset = customPreset(dialog.presetId) ?: return
                browseFeedService.savePreset(
                    preset.copy(
                        name = trimmed,
                        chronological = chronological,
                    ),
                )
                setDialog(null)
            }
            Dialog.SavePreset.Mode.UpdateFromCurrentState -> {
                if (source !is CatalogueSource) return

                val preset = customPreset(dialog.presetId) ?: return
                val presetState = state.value.toSavedPresetState(defaultFilters = source.getFilterList())
                browseFeedService.savePreset(
                    preset.copy(
                        name = trimmed,
                        chronological = chronological,
                        listingMode = presetState.listingMode,
                        query = presetState.query,
                        filters = presetState.filters,
                    ),
                )
                mutableState.update {
                    it.copy(
                        appliedCustomPresetId = preset.id,
                        dialog = null,
                    )
                }
            }
        }
    }

    private fun customPreset(presetId: String?): SourceFeedPreset? {
        val targetPresetId = presetId ?: return null
        return browseFeedService.stateSnapshot().presets.firstOrNull {
            it.id == targetPresetId && it.sourceId == sourceId
        }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    private suspend fun showLibraryActionChooserOrHandle(manga: Manga) {
        if (getLibraryManga.await().isEmpty()) {
            handleMangaLibraryAction(manga)
        } else {
            setDialog(Dialog.LibraryActionChooser(manga))
        }
    }

    private fun updateBrowseMergeDialog(transform: (Dialog.EditMerge) -> Dialog.EditMerge) {
        mutableState.update { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@update state
            state.copy(dialog = transform(dialog))
        }
    }

    private suspend fun createBrowseMergeDialog(
        manga: Manga,
        target: MergeTarget,
    ): Dialog.EditMerge {
        val remoteManga = networkToLocalManga(manga)
        val orderedMembers = if (target.isMerged) {
            val membersById = target.memberMangas.associateBy(Manga::id)
            getMergedManga.awaitGroupByTargetId(target.id)
                .sortedBy { it.position }
                .mapNotNull { merge -> membersById[merge.mangaId] }
                .ifEmpty { target.memberMangas }
        } else {
            target.memberMangas
        }

        val entries = buildList {
            orderedMembers.forEach { member ->
                add(
                    MergeEditorEntry(
                        id = member.id,
                        manga = member,
                        subtitle = getSourceSubtitle(member),
                        isRemovable = true,
                        isMember = true,
                    ),
                )
            }
            if (none { it.id == remoteManga.id }) {
                add(
                    MergeEditorEntry(
                        id = remoteManga.id,
                        manga = remoteManga,
                        subtitle = getSourceSubtitle(remoteManga) + " • New",
                        isRemovable = false,
                    ),
                )
            }
        }.toImmutableList()

        return Dialog.EditMerge(
            manga = remoteManga,
            targetId = target.id,
            targetLocked = false,
            entries = entries,
            removedIds = emptySet(),
            libraryRemovalIds = emptySet(),
            categoryIds = target.categoryIds,
        )
    }

    private suspend fun removeMergedMembersFromLibrary(mangaIds: Collection<Long>) {
        mangaIds.distinct().forEach { mangaId ->
            val manga = getManga.await(mangaId) ?: return@forEach
            if (updateManga.awaitUpdateFavorite(manga.id, false) && manga.removeCovers() != manga) {
                updateManga.awaitUpdateCoverLastModified(manga.id)
            }
            val source = sourceManager.get(manga.source) ?: return@forEach
            downloadManager.deleteManga(manga, source)
        }
    }

    private suspend fun ensureFavorite(manga: Manga) {
        if (manga.favorite) return
        setMangaDefaultChapterFlags.await(manga)
        addTracks.bindEnhancedTrackers(manga, source)
        updateManga.await(
            manga.copy(
                favorite = true,
                dateAdded = Instant.now().toEpochMilli(),
            ).toMangaUpdate(),
        )
    }

    private fun getSourceSubtitle(manga: Manga): String {
        val sourceName = sourceManager.getOrStub(manga.source).name
        val creator = manga.author?.takeIf { it.isNotBlank() }
            ?: manga.artist?.takeIf { it.isNotBlank() }
        return buildString {
            append(sourceName)
            if (creator != null && !creator.equals(sourceName, ignoreCase = true)) {
                append(" • ")
                append(creator)
            }
        }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class SavePreset(
            val mode: Mode,
            val presetId: String? = null,
            val name: String = "",
            val chronological: Boolean,
        ) : Dialog {
            enum class Mode {
                Create,
                EditMetadata,
                UpdateFromCurrentState,
            }
        }
        data class MangaPreview(val mangaId: Long) : Dialog
        data class LibraryActionChooser(val manga: Manga) : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<DuplicateMangaCandidate>) : Dialog
        data class SelectMergeTarget(
            val manga: Manga,
            val query: String = "",
            val targets: ImmutableList<MergeTarget>,
            val visibleTargets: ImmutableList<MergeTarget>,
        ) : Dialog
        data class EditMerge(
            val manga: Manga,
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
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val appliedCustomPresetId: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }

}

internal fun BrowseSourceScreenModel.State.initializeForSource(
    sourceFilters: FilterList,
    initialFilterSnapshot: List<FilterStateNode> = emptyList(),
): BrowseSourceScreenModel.State {
    val filters = sourceFilters.applySnapshot(initialFilterSnapshot)
    val query = (listing as? BrowseSourceScreenModel.Listing.Search)?.query
    val updatedListing = when (listing) {
        is BrowseSourceScreenModel.Listing.Search -> BrowseSourceScreenModel.Listing.Search(query, filters)
        else -> listing
    }

    return copy(
        listing = updatedListing,
        filters = filters,
        toolbarQuery = query,
    )
}

internal data class SavedPresetState(
    val listingMode: FeedListingMode,
    val query: String?,
    val filters: List<FilterStateNode>,
)

internal fun BrowseSourceScreenModel.State.toSavedPresetState(defaultFilters: FilterList): SavedPresetState {
    val filterSnapshot = filters.snapshot()
    val hasEditedFilters = filterSnapshot != defaultFilters.snapshot()
    val listingMode = when {
        listing is BrowseSourceScreenModel.Listing.Search || hasEditedFilters -> FeedListingMode.Search
        listing == BrowseSourceScreenModel.Listing.Popular -> FeedListingMode.Popular
        else -> FeedListingMode.Latest
    }
    val query = (listing as? BrowseSourceScreenModel.Listing.Search)
        ?.query
        ?.trim()
        ?.takeIf { listingMode == FeedListingMode.Search && it.isNotEmpty() }

    return SavedPresetState(
        listingMode = listingMode,
        query = query,
        filters = filterSnapshot,
    )
}
