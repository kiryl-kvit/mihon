package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetEnhancedDuplicateLibraryManga
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.presentation.manga.components.MergeEditorEntry
import eu.kanade.presentation.manga.components.MergeTarget
import eu.kanade.presentation.manga.components.buildMergeTargets
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.chapter.isDownloaded
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.CustomPreferences
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.sortedForMergedDisplay
import tachiyomi.domain.chapter.service.sortedForReading
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMergedManga
import tachiyomi.domain.manga.model.DuplicateMangaCandidate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaMerge
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.math.floor

class MangaScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val mangaId: Long,
    private val isFromSource: Boolean,
    private val bypassMerge: Boolean = false,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val customPreferences: CustomPreferences = Injekt.get(),
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get(),
    private val getEnhancedDuplicateLibraryManga: GetEnhancedDuplicateLibraryManga = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateMergedManga: UpdateMergedManga = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<MangaScreenModel.State>(State.Loading) {

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(screenModelScope)
    val isMangaPreviewEnabled by customPreferences.enableMangaPreview.asState(screenModelScope)
    val mangaPreviewPageCount by customPreferences.mangaPreviewPageCount.asState(screenModelScope)
    val mangaPreviewSize by customPreferences.mangaPreviewSize.asState(screenModelScope)

    private val previewLoaderState = MutableStateFlow(MangaPreviewState(pageCount = mangaPreviewPageCount))
    val previewState = previewLoaderState.asStateFlow()

    private var previewReaderChapter: ReaderChapter? = null
    private var previewLoadJob: Job? = null
    private var previewPageJobs: Map<Int, Job> = emptyMap()
    private var duplicateObservationJob: Job? = null
    private var refreshFromSourceJob: Job? = null

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds: HashSet<Long> = HashSet()

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    private fun updatePreviewState(transform: (MangaPreviewState) -> MangaPreviewState) {
        previewLoaderState.update(transform)
    }

    init {
        screenModelScope.launchIO {
            customPreferences.mangaPreviewPageCount.changes().collectLatest { newCount ->
                updatePreviewState { preview ->
                    val updatedPages = if (preview.isExpanded && preview.pages.isNotEmpty()) {
                        preview.pages.take(newCount)
                    } else {
                        preview.pages
                    }
                    preview.copy(pageCount = newCount, pages = updatedPages)
                }
                if (previewState.value.isExpanded) {
                    loadPreview(force = true)
                }
            }
        }

        screenModelScope.launchIO {
            mergeAwareMangaAndChaptersFlow(
                mangaAndChaptersFlow = getMangaAndChapters.subscribe(
                    mangaId,
                    applyScanlatorFilter = true,
                    bypassMerge = bypassMerge,
                ),
                mergeGroupFlow = getMergedManga.subscribeGroupByMangaId(mangaId),
                downloadChangesFlow = downloadCache.changes,
                downloadQueueFlow = downloadManager.queueState,
            )
                .flowWithLifecycle(lifecycle)
                .collectLatest { (manga, chapters) ->
                    val mergePresentation = getMergePresentation(manga)
                    updateSuccessState {
                        it.copy(
                            manga = manga,
                            sourceName = mergePresentation.sourceName,
                            memberIds = mergePresentation.memberIds,
                            memberTitleById = mergePresentation.memberTitleById,
                            mergedMemberTitles = mergePresentation.mergedMemberTitles,
                            mergeTargetId = mergePresentation.mergeTargetId,
                            mergeGroupMemberIds = mergePresentation.mergeGroupMemberIds,
                            chapters = chapters.toChapterListItems(manga),
                            duplicateCandidates = if (it.isFromSource &&
                                !manga.favorite
                            ) {
                                it.duplicateCandidates
                            } else {
                                emptyList()
                            },
                        )
                    }
                }
        }

        if (!bypassMerge) {
            screenModelScope.launchIO {
                combine(
                    getMergedManga.subscribeGroupByMangaId(mangaId).distinctUntilChanged(),
                    getExcludedScanlators.subscribe(mangaId).distinctUntilChanged(),
                ) { _, _ -> Unit }
                    .flowWithLifecycle(lifecycle)
                    .collectLatest {
                        val mergedMemberIds = getDisplayedMemberIds()
                        updateSuccessState {
                            it.copy(
                                excludedScanlators = mergedMemberIds.flatMapTo(linkedSetOf()) { memberId ->
                                    getExcludedScanlators.await(memberId)
                                },
                            )
                        }
                    }
            }

            screenModelScope.launchIO {
                combine(
                    getMergedManga.subscribeGroupByMangaId(mangaId).distinctUntilChanged(),
                    getAvailableScanlators.subscribe(mangaId).distinctUntilChanged(),
                ) { _, _ -> Unit }
                    .flowWithLifecycle(lifecycle)
                    .collectLatest {
                        val mergedMemberIds = getDisplayedMemberIds()
                        updateSuccessState {
                            it.copy(
                                availableScanlators = mergedMemberIds.flatMapTo(linkedSetOf()) { memberId ->
                                    getAvailableScanlators.await(memberId)
                                },
                            )
                        }
                    }
            }
        }

        observeDownloads()

        screenModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)
            val mergePresentation = getMergePresentation(manga)
            val chapters = getMangaAndChapters.awaitChapters(
                mangaId,
                applyScanlatorFilter = true,
                bypassMerge = bypassMerge,
            )
                .toChapterListItems(manga)

            if (!manga.favorite) {
                setMangaDefaultChapterFlags.await(manga)
            }

            val membersNeedingRefresh = getMergeMembersNeedingRefresh()
            val needRefreshInfo = if (mergePresentation.memberIds.size > 1) {
                membersNeedingRefresh.any { !it.initialized }
            } else {
                !manga.initialized
            }
            val needRefreshChapter = if (mergePresentation.memberIds.size > 1) {
                membersNeedingRefresh.isNotEmpty()
            } else {
                chapters.isEmpty()
            }

            // Show what we have earlier
            mutableState.update {
                State.Success(
                    manga = manga,
                    source = Injekt.get<SourceManager>().getOrStub(manga.source),
                    sourceName = mergePresentation.sourceName,
                    memberIds = mergePresentation.memberIds,
                    memberTitleById = mergePresentation.memberTitleById,
                    mergedMemberTitles = mergePresentation.mergedMemberTitles,
                    mergeTargetId = mergePresentation.mergeTargetId,
                    mergeGroupMemberIds = mergePresentation.mergeGroupMemberIds,
                    isFromSource = isFromSource,
                    chapters = chapters,
                    availableScanlators = mergePresentation.memberIds.flatMapTo(linkedSetOf()) { memberId ->
                        getAvailableScanlators.await(memberId)
                    },
                    excludedScanlators = mergePresentation.memberIds.flatMapTo(linkedSetOf()) { memberId ->
                        getExcludedScanlators.await(memberId)
                    },
                    duplicateCandidates = emptyList(),
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                    hideMissingChapters = libraryPreferences.hideMissingChapters.get(),
                )
            }

            observeDuplicateCandidates()

            // Start observe tracking since it only needs mangaId
            observeTrackers()

            // Fetch info-chapters when needed
            if (screenModelScope.isActive && (needRefreshInfo || needRefreshChapter)) {
                launchRefreshFromSource(
                    manualFetch = false,
                    fetchInfo = needRefreshInfo,
                    fetchChapters = needRefreshChapter,
                ).join()
            }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        launchRefreshFromSource(
            manualFetch = manualFetch,
            fetchInfo = true,
            fetchChapters = true,
        )
    }

    fun setPreviewExpanded(expanded: Boolean) {
        if (!isMangaPreviewEnabled) {
            collapsePreview()
            return
        }

        if (expanded) {
            updatePreviewState { it.copy(isExpanded = true) }
            if (!previewState.value.hasLoadedContent) {
                loadPreview()
            }
        } else {
            collapsePreview()
        }
    }

    fun retryPreview() {
        if (!previewState.value.isExpanded) return
        loadPreview(force = true)
    }

    private fun loadPreview(force: Boolean = false) {
        if (!isMangaPreviewEnabled) return

        previewLoadJob?.cancel()
        previewLoadJob = screenModelScope.launchIO {
            clearPreviewResources(resetState = false, cancelLoadJob = false)
            updatePreviewState {
                it.copy(
                    isExpanded = true,
                    isLoading = true,
                    error = null,
                    chapterId = null,
                    pages = emptyList(),
                    pageCount = mangaPreviewPageCount,
                )
            }

            awaitRefreshFromSource()
            val state = successState ?: return@launchIO

            if (!force && previewState.value.hasLoadedContent) {
                updatePreviewState { it.copy(isLoading = false) }
                return@launchIO
            }

            runCatching {
                val latestManga = getMangaAndChapters.awaitManga(mangaId)
                val previewChapter = getFirstPreviewChapter()
                    ?: error(context.stringResource(MR.strings.no_chapters_error))
                val readerChapter = ReaderChapter(
                    chapter = previewChapter.chapter,
                    manga = previewChapter.manga,
                    source = previewChapter.source,
                )
                val chapterLoader = ChapterLoader(
                    context = context,
                    downloadManager = downloadManager,
                    downloadProvider = downloadProvider,
                    manga = latestManga,
                    sourceManager = Injekt.get(),
                )

                readerChapter.ref()
                previewReaderChapter = readerChapter
                chapterLoader.loadChapter(readerChapter)

                val loadedPages = readerChapter.pages
                    .orEmpty()
                    .take(mangaPreviewPageCount)
                    .map { page ->
                        PreviewPage(
                            page = page,
                        )
                    }

                updatePreviewState {
                    it.copy(
                        isExpanded = true,
                        isLoading = false,
                        error = null,
                        chapterId = previewChapter.chapter.id,
                        pages = loadedPages,
                        pageCount = mangaPreviewPageCount,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                clearPreviewResources(resetState = false, cancelLoadJob = false)
                updatePreviewState {
                    it.copy(
                        isExpanded = true,
                        isLoading = false,
                        error = error,
                        chapterId = null,
                        pages = emptyList(),
                        pageCount = mangaPreviewPageCount,
                    )
                }
            }
        }
    }

    fun loadPreviewPage(pageIndex: Int) {
        val chapter = previewReaderChapter ?: return
        val page = chapter.pages?.getOrNull(pageIndex) ?: return
        if (page.status == eu.kanade.tachiyomi.source.model.Page.State.Ready) return
        if (previewPageJobs[pageIndex]?.isActive == true) return

        previewPageJobs = previewPageJobs + (
            pageIndex to screenModelScope.launchIO {
                try {
                    chapter.pageLoader?.loadPage(page)
                } catch (_: Throwable) {
                    // Page state carries the failure.
                }
            }
            )
    }

    private suspend fun getFirstPreviewChapter(): ChapterList.Item? {
        val manga = getMangaAndChapters.awaitManga(mangaId)
        val mergePresentation = getMergePresentation(manga)
        val chapters = getMangaAndChapters.awaitChapters(
            id = mangaId,
            applyScanlatorFilter = true,
            bypassMerge = bypassMerge,
        )
        val chapterItems = chapters.toChapterListItems(manga)
        return chapterItems.previewReadingChapters(manga, mergePresentation.memberIds).firstOrNull()
    }

    private fun launchRefreshFromSource(
        manualFetch: Boolean,
        fetchInfo: Boolean,
        fetchChapters: Boolean,
    ): Job {
        refreshFromSourceJob?.cancel()
        return screenModelScope.launchIO {
            updateSuccessState { it.copy(isRefreshingData = true) }
            try {
                refreshFromSourceJob = coroutineContext[Job]
                val fetchFromSourceTasks = listOf(
                    async { if (fetchInfo) fetchMangaFromSource(manualFetch) },
                    async { if (fetchChapters) fetchChaptersFromSource(manualFetch) },
                )
                fetchFromSourceTasks.awaitAll()
            } finally {
                if (refreshFromSourceJob === coroutineContext[Job]) {
                    refreshFromSourceJob = null
                    updateSuccessState { it.copy(isRefreshingData = false) }
                }
            }
        }.also { refreshFromSourceJob = it }
    }

    private suspend fun awaitRefreshFromSource() {
        val refreshJob = refreshFromSourceJob
        if (refreshJob?.isActive == true) {
            refreshJob.join()
        }
    }

    private fun collapsePreview() {
        clearPreviewResources(resetState = true)
        updatePreviewState {
            MangaPreviewState(
                isExpanded = false,
                pageCount = mangaPreviewPageCount,
            )
        }
    }

    private fun clearPreviewResources(resetState: Boolean, cancelLoadJob: Boolean = true) {
        if (cancelLoadJob) {
            previewLoadJob?.cancel()
            previewLoadJob = null
        }
        previewPageJobs.values.forEach(Job::cancel)
        previewPageJobs = emptyMap()
        previewReaderChapter?.unref()
        previewReaderChapter = null
        if (resetState) {
            previewLoaderState.value = MangaPreviewState(pageCount = mangaPreviewPageCount)
        }
    }

    override fun onDispose() {
        super.onDispose()
        duplicateObservationJob?.cancel()
        clearPreviewResources(resetState = false)
    }

    // Manga info - start

    /**
     * Fetch manga information from source.
     */
    private suspend fun fetchMangaFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                getMembersToRefreshFromSource(manualFetch).forEach { memberManga ->
                    val source = Injekt.get<SourceManager>().getOrStub(memberManga.source)
                    val networkManga = source.getMangaDetails(memberManga.toSManga())
                    updateManga.awaitUpdateFromSource(memberManga, networkManga, manualFetch)
                }
            }
        } catch (e: Throwable) {
            // Ignore early hints "errors" that aren't handled by OkHttp
            if (e is HttpException && e.code == 103) return

            logcat(LogPriority.ERROR, e)
            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = with(context) { e.formattedMessage })
            }
        }
    }

    fun toggleFavorite() {
        val state = successState ?: return
        if (state.isMerged && isFavorited) {
            showRemoveMergedMangaDialog()
            return
        }
        toggleFavorite(
            onRemoved = {
                screenModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        screenModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getEnhancedDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateManga(manga, duplicates)) }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
            }
        }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getDisplayedMemberIds().flatMap { getMangaCategoryIds(it) }.distinct()
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        screenModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteManga(state.manga, state.source)
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    private suspend fun getMangaCategoryIds(mangaId: Long): List<Long> {
        return getCategories.await(mangaId)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        screenModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            getDisplayedMemberIds().forEach { memberId ->
                setMangaCategories.await(memberId, categoryIds)
            }
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        screenModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private suspend fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        return map { chapter ->
            val chapterManga = mangaRepository.getMangaById(chapter.mangaId)
            val isLocal = chapterManga.isLocal()
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    chapterManga.title,
                    chapterManga.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                manga = chapterManga,
                source = Injekt.get<SourceManager>().getOrStub(chapterManga.source),
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in selectedChapterIds,
            )
        }
    }

    /**
     * Requests an updated list of chapters from the source.
     */
    private suspend fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val newChapters = buildList {
                    getMembersToRefreshFromSource(manualFetch).forEach { memberManga ->
                        val source = Injekt.get<SourceManager>().getOrStub(memberManga.source)
                        val chapters = source.getChapterList(memberManga.toSManga())

                        addAll(
                            syncChaptersWithSource.await(
                                chapters,
                                memberManga,
                                source,
                                manualFetch,
                            ),
                        )
                    }
                }

                if (manualFetch && newChapters.isNotEmpty()) {
                    downloadNewChapters(newChapters)
                }
            }
        } catch (e: Throwable) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else if (e is SourceNotInstalledException) {
                context.stringResource(MR.strings.loader_not_implemented_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
            val newManga = mangaRepository.getMangaById(mangaId)
            updateSuccessState { it.copy(manga = newManga, isRefreshingData = false) }
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        screenModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga, downloadManager)
    }

    private fun getUnreadChapterItems(): List<ChapterList.Item> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { item -> !item.chapter.read && item.downloadState == Download.State.NOT_DOWNLOADED }
    }

    private fun getUnreadChapterItemsSorted(): List<ChapterList.Item> {
        val state = successState ?: return emptyList()
        val unreadItems = getUnreadChapterItems()
        val orderedIds = unreadItems
            .map(ChapterList.Item::chapter)
            .sortedForReading(state.manga, state.memberIds)
            .mapIndexed { index, chapter -> chapter.id to index }
            .toMap()
        return unreadItems.sortedBy { orderedIds.getValue(it.chapter.id) }
    }

    private fun getBookmarkedChapterItems(): List<ChapterList.Item> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { item -> item.chapter.bookmark && item.downloadState == Download.State.NOT_DOWNLOADED }
    }

    private fun startDownload(
        items: List<ChapterList.Item>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        screenModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = items.singleOrNull()?.id ?: return@launchNonCancellable
                downloadChapters(items)
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(items)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                startDownload(items, true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChapterItemsSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChapterItemsSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChapterItemsSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChapterItemsSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapterItems()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapterItems()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val state = successState ?: return
        val prevChapters = state.readingChapters.map { it.chapter }
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        screenModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers(
        refreshTracks: RefreshTracks = Injekt.get(),
    ) {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withUIContext {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(items: List<ChapterList.Item>) {
        items.groupBy { it.manga.id }
            .forEach { (_, chapterItems) ->
                val manga = chapterItems.first().manga
                downloadManager.downloadChapters(manga, chapterItems.map { it.chapter })
            }
        toggleAllSelection(false)
    }

    private suspend fun getChapterItems(chapters: List<Chapter>): List<ChapterList.Item> {
        return chapters.map { chapter ->
            val manga = mangaRepository.getMangaById(chapter.mangaId)
            ChapterList.Item(
                chapter = chapter,
                manga = manga,
                source = Injekt.get<SourceManager>().getOrStub(manga.source),
                downloadState = Download.State.NOT_DOWNLOADED,
                downloadProgress = 0,
            )
        }
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        screenModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
            try {
                chapters.groupBy { it.mangaId }
                    .forEach { (mangaId, mangaChapters) ->
                        val manga = mangaRepository.getMangaById(mangaId)
                        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
                        downloadManager.deleteChapters(mangaChapters, manga, source)
                    }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
            getChapterItems(chapters)
                .groupBy { it.manga.id }
                .forEach { (_, chapterItems) ->
                    val manga = chapterItems.first().manga
                    val chaptersToDownload = filterChaptersForDownload.await(manga, chapterItems.map { it.chapter })
                    if (chaptersToDownload.isNotEmpty()) {
                        downloadChapters(getChapterItems(chaptersToDownload))
                    }
                }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        screenModelScope.launchNonCancellable {
            updateMergedMemberManga { memberManga ->
                setMangaChapterFlags.awaitSetUnreadFilter(memberManga, flag)
            }
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        screenModelScope.launchNonCancellable {
            updateMergedMemberManga { memberManga ->
                setMangaChapterFlags.awaitSetDownloadedFilter(memberManga, flag)
            }
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        screenModelScope.launchNonCancellable {
            updateMergedMemberManga { memberManga ->
                setMangaChapterFlags.awaitSetBookmarkFilter(memberManga, flag)
            }
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            updateMergedMemberManga { memberManga ->
                setMangaChapterFlags.awaitSetDisplayMode(memberManga, mode)
            }
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            updateMergedMemberManga { memberManga ->
                setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(memberManga, sort)
            }
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                updateMergedMemberManga { memberManga ->
                    setMangaDefaultChapterFlags.await(memberManga)
                }
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        screenModelScope.launchNonCancellable {
            updateMergedMemberManga { memberManga ->
                setMangaDefaultChapterFlags.await(memberManga)
            }
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newChapters = successState.processedChapters.toMutableList().apply {
                val selectedIndex = successState.processedChapters.indexOfFirst { it.id == item.chapter.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.id, selected)

                if (selected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (!fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers() {
        val manga = successState?.manga ?: return

        screenModelScope.launchIO {
            combine(
                getTracks.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    private fun observeDuplicateCandidates() {
        val state = successState ?: return
        if (!state.isFromSource || state.manga.favorite) return

        duplicateObservationJob?.cancel()
        duplicateObservationJob = screenModelScope.launchIO {
            getEnhancedDuplicateLibraryManga.subscribe(
                manga = this@MangaScreenModel.state
                    .filter { it is State.Success }
                    .map { (it as State.Success).manga }
                    .distinctUntilChanged(),
                scope = screenModelScope,
            )
                .flowWithLifecycle(lifecycle)
                .collectLatest { duplicates ->
                    updateSuccessState {
                        if (!it.isFromSource || it.manga.favorite) {
                            it.copy(duplicateCandidates = emptyList())
                        } else {
                            it.copy(duplicateCandidates = duplicates)
                        }
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<DuplicateMangaCandidate>) : Dialog
        data class EditDisplayName(val manga: Manga, val initialValue: String) : Dialog
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
        data class ManageMerge(
            val targetId: Long,
            val savedTargetId: Long,
            val members: ImmutableList<MergeMember>,
            val removableIds: ImmutableList<Long> = persistentListOf(),
            val libraryRemovalIds: ImmutableList<Long> = persistentListOf(),
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class RemoveMergedManga(val members: ImmutableList<Manga>, val containsLocalManga: Boolean) : Dialog
        data class SelectMergeTarget(
            val manga: Manga,
            val query: String = "",
            val targets: ImmutableList<MergeTarget>,
            val visibleTargets: ImmutableList<MergeTarget>,
        ) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showDuplicateDialog() {
        val state = successState ?: return
        if (state.duplicateCandidates.isEmpty()) return
        updateSuccessState { it.copy(dialog = Dialog.DuplicateManga(state.manga, state.duplicateCandidates)) }
    }

    fun showMergeTargetPicker() {
        val state = successState ?: return
        screenModelScope.launchIO {
            val excludedIds = state.mergeGroupMemberIds.toSet()
            val targets = buildMergeTargets(
                libraryManga = getLibraryManga.await(),
                sourceManager = Injekt.get(),
                excludedMangaIds = excludedIds,
            )
            if (targets.isEmpty()) return@launchIO
            updateSuccessState {
                it.copy(
                    dialog = Dialog.SelectMergeTarget(
                        manga = state.manga,
                        targets = targets,
                        visibleTargets = targets,
                    ),
                )
            }
        }
    }

    fun updateMergeTargetQuery(query: String) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.SelectMergeTarget ?: return@updateSuccessState state
            val trimmed = query.trim()
            val visibleTargets = if (trimmed.isBlank()) {
                dialog.targets
            } else {
                dialog.targets.filter { target ->
                    target.entry.title.contains(trimmed, ignoreCase = true) ||
                        target.searchableTitle.contains(trimmed, ignoreCase = true)
                }.toImmutableList()
            }
            state.copy(dialog = dialog.copy(query = query, visibleTargets = visibleTargets))
        }
    }

    fun openMergeEditor(targetId: Long) {
        val dialog = successState?.dialog as? Dialog.SelectMergeTarget ?: return
        screenModelScope.launchIO {
            val target = dialog.targets.firstOrNull { it.id == targetId } ?: return@launchIO
            updateSuccessState {
                it.copy(dialog = createMergeEditorDialog(dialog.manga, target))
            }
        }
    }

    fun moveMergeEntry(fromIndex: Int, toIndex: Int) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            val entries = dialog.entries.toMutableList()
            if (fromIndex !in entries.indices || toIndex !in entries.indices) return@updateSuccessState state
            val entry = entries.removeAt(fromIndex)
            entries.add(toIndex, entry)
            state.copy(dialog = dialog.copy(entries = entries.toImmutableList()))
        }
    }

    fun setMergeTarget(mangaId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            if (dialog.targetLocked || dialog.entries.none { it.id == mangaId }) return@updateSuccessState state
            state.copy(
                dialog = dialog.copy(
                    targetId = mangaId,
                    removedIds = dialog.removedIds - mangaId,
                    libraryRemovalIds = dialog.libraryRemovalIds - mangaId,
                ),
            )
        }
    }

    fun toggleMergeEntryRemoval(mangaId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            val entry = dialog.entries.firstOrNull { it.id == mangaId } ?: return@updateSuccessState state
            if (!entry.isRemovable || mangaId == dialog.targetId) return@updateSuccessState state
            val removedIds = dialog.removedIds.toMutableSet().apply {
                if (!add(mangaId)) remove(mangaId)
            }
            state.copy(dialog = dialog.copy(removedIds = removedIds))
        }
    }

    fun toggleMergeEntryLibraryRemoval(mangaId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.EditMerge ?: return@updateSuccessState state
            val entry = dialog.entries.firstOrNull { it.id == mangaId } ?: return@updateSuccessState state
            if (!entry.isRemovable || mangaId == dialog.targetId) return@updateSuccessState state
            val libraryRemovalIds = dialog.libraryRemovalIds.toMutableSet().apply {
                if (!add(mangaId)) remove(mangaId)
            }
            state.copy(dialog = dialog.copy(libraryRemovalIds = libraryRemovalIds))
        }
    }

    fun confirmMerge() {
        val dialog = successState?.dialog as? Dialog.EditMerge ?: return
        screenModelScope.launchIO {
            val targetManga = getMangaOrNull(dialog.targetId) ?: return@launchIO
            val remoteManga = networkToLocalManga(dialog.manga)
            ensureFavorite(remoteManga, targetManga, dialog.categoryIds)

            val orderedIds = dialog.entries
                .filterNot { it.id in (dialog.removedIds + dialog.libraryRemovalIds) }
                .map(MergeEditorEntry::id)
                .distinct()

            if (orderedIds.size > 1) {
                updateMergedManga.awaitMerge(dialog.targetId, orderedIds)
            }
            removeMembersFromLibrary(dialog.libraryRemovalIds)
            dismissDialog()
        }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(target = manga, current = duplicate)) }
    }

    private fun showRemoveMergedMangaDialog() {
        val state = successState ?: return
        if (!state.isMerged) return
        screenModelScope.launchIO {
            val members = state.memberIds.mapNotNull { memberId -> getMangaOrNull(memberId) }
                .toImmutableList()
            updateSuccessState {
                it.copy(
                    dialog = Dialog.RemoveMergedManga(
                        members = members,
                        containsLocalManga = members.any(Manga::isLocal),
                    ),
                )
            }
        }
    }

    fun showEditDisplayNameDialog() {
        val state = successState ?: return
        updateSuccessState {
            it.copy(
                dialog = Dialog.EditDisplayName(
                    manga = state.manga,
                    initialValue = state.manga.displayName.orEmpty(),
                ),
            )
        }
    }

    fun showManageMergeDialog() {
        val state = successState ?: return
        if (!state.isPartOfMerge) return
        screenModelScope.launchIO {
            val members = state.mergeGroupMemberIds.mapNotNull { memberId ->
                getMangaOrNull(memberId)?.let { manga ->
                    MergeMember(
                        id = manga.id,
                        manga = manga,
                    )
                }
            }
                .toImmutableList()
            updateSuccessState {
                it.copy(dialog = Dialog.ManageMerge(targetId = state.mergeTargetId, savedTargetId = state.mergeTargetId, members = members))
            }
        }
    }

    fun updateDisplayName(displayName: String) {
        val manga = successState?.manga ?: return
        screenModelScope.launchIO {
            updateManga.awaitUpdateDisplayName(manga.id, displayName.trim().ifBlank { null })
            dismissDialog()
        }
    }

    fun removeMergedMembers(mangaIds: List<Long>) {
        val state = successState ?: return
        val dialog = state.dialog as? Dialog.ManageMerge
        screenModelScope.launchIO {
            if (mangaIds.isEmpty()) return@launchIO
            if (dialog != null) {
                saveManageMerge(dialog, mangaIds)
            } else {
                updateMergedManga.awaitRemoveMembers(state.mergeTargetId, mangaIds)
            }
            dismissDialog()
        }
    }

    fun reorderMergeMembers(fromIndex: Int, toIndex: Int) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.ManageMerge ?: return@updateSuccessState state
            if (fromIndex !in dialog.members.indices ||
                toIndex !in dialog.members.indices
            ) {
                return@updateSuccessState state
            }
            val reordered = dialog.members.toMutableList().apply {
                val item = removeAt(fromIndex)
                add(toIndex, item)
            }
            val reorderedRemovalIds = reordered.mapNotNull { member ->
                member.id.takeIf { it in dialog.removableIds }
            }.toImmutableList()
            val reorderedLibraryRemovalIds = reordered.mapNotNull { member ->
                member.id.takeIf { it in dialog.libraryRemovalIds }
            }.toImmutableList()
            state.copy(
                dialog = dialog.copy(
                    members = reordered.toImmutableList(),
                    removableIds = reorderedRemovalIds,
                    libraryRemovalIds = reorderedLibraryRemovalIds,
                ),
            )
        }
    }

    fun setManageMergeTarget(mangaId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.ManageMerge ?: return@updateSuccessState state
            if (dialog.members.none { it.id == mangaId }) return@updateSuccessState state

            val updatedRemovals = dialog.removableIds.filterNot { it == mangaId }.toImmutableList()
            val updatedLibraryRemovals = dialog.libraryRemovalIds.filterNot { it == mangaId }.toImmutableList()
            state.copy(
                dialog = dialog.copy(
                    targetId = mangaId,
                    removableIds = updatedRemovals,
                    libraryRemovalIds = updatedLibraryRemovals,
                ),
            )
        }
    }

    fun toggleMergedMemberRemoval(mangaId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.ManageMerge ?: return@updateSuccessState state
            if (mangaId == dialog.targetId || dialog.members.none { it.id == mangaId }) return@updateSuccessState state

            val updatedIds = dialog.removableIds.toMutableList().apply {
                if (mangaId in this) {
                    remove(mangaId)
                } else {
                    add(mangaId)
                }
            }.toImmutableList()

            state.copy(dialog = dialog.copy(removableIds = updatedIds))
        }
    }

    fun toggleMergedMemberLibraryRemoval(mangaId: Long) {
        updateSuccessState { state ->
            val dialog = state.dialog as? Dialog.ManageMerge ?: return@updateSuccessState state
            if (mangaId == dialog.targetId || dialog.members.none { it.id == mangaId }) return@updateSuccessState state

            val updatedIds = dialog.libraryRemovalIds.toMutableList().apply {
                if (mangaId in this) {
                    remove(mangaId)
                } else {
                    add(mangaId)
                }
            }.toImmutableList()

            state.copy(dialog = dialog.copy(libraryRemovalIds = updatedIds))
        }
    }

    fun saveMergeOrder() {
        val dialog = successState?.dialog as? Dialog.ManageMerge ?: return
        screenModelScope.launchIO {
            saveManageMerge(dialog, dialog.removableIds + dialog.libraryRemovalIds)
            removeMembersFromLibrary(dialog.libraryRemovalIds)
            dismissDialog()
        }
    }

    private suspend fun saveManageMerge(dialog: Dialog.ManageMerge, mangaIdsToRemove: Collection<Long>) {
        val remainingIds = dialogRemainingIds(dialog, mangaIdsToRemove)
        val targetId = resolveManageMergeTargetId(dialog.targetId, remainingIds)

        if (targetId != null && remainingIds.size > 1) {
            updateMergedManga.awaitMerge(targetId, remainingIds)
        } else {
            updateMergedManga.awaitDeleteGroup(dialog.targetId)
        }
    }

    private suspend fun removeMembersFromLibrary(mangaIds: Collection<Long>) {
        mangaIds.distinct().forEach { mangaId ->
            val manga = getMangaOrNull(mangaId) ?: return@forEach
            if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                if (manga.removeCovers() != manga) {
                    updateManga.awaitUpdateCoverLastModified(manga.id)
                }
            }
            val source = Injekt.get<SourceManager>().get(manga.source) ?: return@forEach
            downloadManager.deleteManga(manga, source)
        }
    }

    fun removeMergedManga(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        val state = successState ?: return
        screenModelScope.launchIO {
            if (deleteFromLibrary) {
                updateMergedManga.awaitDeleteGroup(state.mergeTargetId)
                mangas.forEach { manga ->
                    if (updateManga.awaitUpdateFavorite(manga.id, false) && manga.removeCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                }
            }

            if (deleteChapters) {
                mangas.forEach { manga ->
                    val source = Injekt.get<SourceManager>().get(manga.source) ?: return@forEach
                    downloadManager.deleteManga(manga, source)
                }
            }

            dismissDialog()
        }
    }

    fun unmergeAll() {
        val state = successState ?: return
        screenModelScope.launchIO {
            updateMergedManga.awaitDeleteGroup(state.mergeTargetId)
            dismissDialog()
        }
    }

    suspend fun getVisibleMangaId(mangaId: Long): Long {
        if (bypassMerge) return mangaId
        return getMergedManga.awaitVisibleTargetId(mangaId)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        screenModelScope.launchIO {
            getDisplayedMemberIds().forEach { memberId ->
                setExcludedScanlators.await(memberId, excludedScanlators)
            }
        }
    }

    private suspend fun getDisplayedMemberIds(): List<Long> {
        if (bypassMerge) return listOf(mangaId)
        return getMergeGroupMemberIds()
    }

    private suspend fun getMergeGroupMemberIds(): List<Long> {
        return getMergedManga.awaitGroupByMangaId(mangaId)
            .sortedBy { it.position }
            .map { it.mangaId }
            .ifEmpty { listOf(mangaId) }
    }

    private suspend fun getMergePresentation(manga: Manga): MergePresentation {
        val mergeGroupMemberIds = getMergeGroupMemberIds().toImmutableList()
        val memberIds = if (bypassMerge) {
            persistentListOf(manga.id)
        } else {
            mergeGroupMemberIds
        }
        val members = memberIds.mapNotNull { memberId -> getMangaOrNull(memberId) }
        return MergePresentation(
            sourceName = getSourceName(manga, memberIds),
            memberIds = memberIds,
            memberTitleById = members.associate { it.id to it.presentationTitle() },
            mergedMemberTitles = members.map { it.presentationTitle() }.filter { it.isNotBlank() }.toImmutableList(),
            mergeTargetId = getMergedManga.awaitVisibleTargetId(manga.id),
            mergeGroupMemberIds = mergeGroupMemberIds,
        )
    }

    private suspend fun updateMergedMemberManga(block: suspend (Manga) -> Unit) {
        getDisplayedMemberIds().forEach { memberId ->
            getMangaOrNull(memberId)?.let { memberManga ->
                block(memberManga)
            }
        }
    }

    private suspend fun getMergeMembers(): List<Manga> {
        return getDisplayedMemberIds().mapNotNull { memberId -> getMangaOrNull(memberId) }
    }

    private suspend fun getMergeMembersNeedingRefresh(): List<Manga> {
        val members = getMergeMembers()
        if (members.size <= 1) return members

        return members.filter { memberManga ->
            !memberManga.initialized ||
                getChaptersByMangaId.await(memberManga.id, applyScanlatorFilter = false).isEmpty()
        }
    }

    private suspend fun getMembersToRefreshFromSource(manualFetch: Boolean): List<Manga> {
        val mergedMembers = getMergeMembers()
        if (mergedMembers.size <= 1) {
            val fallbackManga = successState?.manga ?: getMangaOrNull(mangaId)
            return listOfNotNull(fallbackManga)
        }

        return if (manualFetch) {
            mergedMembers
        } else {
            getMergeMembersNeedingRefresh()
        }
    }

    private suspend fun getMangaOrNull(id: Long): Manga? {
        return runCatching { mangaRepository.getMangaById(id) }.getOrNull()
    }

    private suspend fun createMergeEditorDialog(
        manga: Manga,
        target: MergeTarget,
    ): Dialog.EditMerge {
        val localManga = networkToLocalManga(manga)
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
                        subtitle = getMergeSubtitle(member),
                        isRemovable = true,
                        isMember = true,
                    ),
                )
            }
            if (none { it.id == localManga.id }) {
                add(
                    MergeEditorEntry(
                        id = localManga.id,
                        manga = localManga,
                        subtitle = getMergeSubtitle(localManga) + " • New",
                        isRemovable = false,
                    ),
                )
            }
        }.toImmutableList()

        return Dialog.EditMerge(
            manga = localManga,
            targetId = target.id,
            targetLocked = false,
            entries = entries,
            removedIds = emptySet(),
            libraryRemovalIds = emptySet(),
            categoryIds = target.categoryIds,
        )
    }

    private suspend fun ensureFavorite(manga: Manga, targetManga: Manga, categoryIds: List<Long>) {
        if (!manga.favorite) {
            setMangaDefaultChapterFlags.await(manga)
            addTracks.bindEnhancedTrackers(manga, source ?: return)
            updateManga.await(
                manga.copy(
                    favorite = true,
                    dateAdded = Instant.now().toEpochMilli(),
                ).toMangaUpdate(),
            )
        }

        val appliedCategoryIds = if (categoryIds.isNotEmpty()) categoryIds else getMangaCategoryIds(targetManga.id)
        setMangaCategories.await(manga.id, appliedCategoryIds)
    }

    private fun getMergeSubtitle(manga: Manga): String {
        val sourceName = Injekt.get<SourceManager>().getOrStub(manga.source).name
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

    private fun getSourceName(manga: Manga, memberIds: List<Long>): String {
        return if (memberIds.size > 1) {
            context.stringResource(MR.strings.multi_lang)
        } else {
            Injekt.get<SourceManager>().getOrStub(manga.source).getNameForMangaInfo()
        }
    }

    private data class MergePresentation(
        val sourceName: String,
        val memberIds: ImmutableList<Long>,
        val memberTitleById: Map<Long, String>,
        val mergedMemberTitles: ImmutableList<String>,
        val mergeTargetId: Long,
        val mergeGroupMemberIds: ImmutableList<Long>,
    )

    @Immutable
    data class MergeMember(
        val id: Long,
        val manga: Manga,
    ) {
        val subtitle: String
            get() = buildString {
                val sourceName = Injekt.get<SourceManager>().getOrStub(manga.source).getNameForMangaInfo()
                val creator = manga.author?.takeIf { it.isNotBlank() }
                    ?: manga.artist?.takeIf { it.isNotBlank() }
                append(sourceName)
                if (creator != null && !creator.equals(sourceName, ignoreCase = true)) {
                    append(" • ")
                    append(creator)
                }
            }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val sourceName: String,
            val memberIds: ImmutableList<Long>,
            val memberTitleById: Map<Long, String>,
            val mergedMemberTitles: ImmutableList<String>,
            val mergeTargetId: Long,
            val mergeGroupMemberIds: ImmutableList<Long>,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val duplicateCandidates: List<DuplicateMangaCandidate> = emptyList(),
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
            val hideMissingChapters: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga, isMerged).toList()
            }

            val readingChapters by lazy {
                processedChapters.sortedForReading(manga, memberIds)
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                if (!isMerged) {
                    if (hideMissingChapters) {
                        processedChapters
                    } else {
                        processedChapters.withMissingChapterCounts(manga)
                    }
                } else {
                    buildList {
                        memberIds.forEach { memberId ->
                            val chapterItems = processedChapters.filter { it.chapter.mangaId == memberId }
                            if (chapterItems.isEmpty()) return@forEach

                            add(
                                ChapterList.MemberHeader(
                                    mangaId = memberId,
                                    title = memberTitleById[memberId].orEmpty().ifBlank { manga.presentationTitle() },
                                ),
                            )
                            addAll(
                                if (hideMissingChapters) {
                                    chapterItems
                                } else {
                                    chapterItems.withMissingChapterCounts(manga)
                                },
                            )
                        }
                    }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            val isPartOfMerge: Boolean
                get() = mergeGroupMemberIds.size > 1

            val isMerged: Boolean
                get() = memberIds.size > 1

            val showMergeNotice: Boolean
                get() = isPartOfMerge && !isMerged && mergeTargetId != manga.id

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga, isMerged: Boolean): List<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                val filtered = asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .toList()
                return filtered.sortedForMergedDisplay(manga, memberIds)
            }
        }
    }

    @Immutable
    data class MangaPreviewState(
        val isExpanded: Boolean = false,
        val isLoading: Boolean = false,
        val error: Throwable? = null,
        val chapterId: Long? = null,
        val pages: List<PreviewPage> = emptyList(),
        val pageCount: Int = 5,
    ) {
        val hasLoadedContent: Boolean
            get() = chapterId != null && !isLoading && error == null
    }

    @Immutable
    data class PreviewPage(
        val page: ReaderPage,
    )
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MemberHeader(
        val mangaId: Long,
        val title: String,
    ) : ChapterList()

    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val manga: Manga,
        val source: Source,
        val downloadState: Download.State,
        val downloadProgress: Int,
        val selected: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

private fun List<ChapterList.Item>.withMissingChapterCounts(manga: Manga): List<ChapterList> {
    return insertSeparators { before, after ->
        val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
            after to before
        } else {
            before to after
        }
        if (higherChapter == null) return@insertSeparators null

        if (lowerChapter == null) {
            floor(higherChapter.chapter.chapterNumber)
                .toInt()
                .minus(1)
                .coerceAtLeast(0)
        } else {
            calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
        }
            .takeIf { it > 0 }
            ?.let { missingCount ->
                ChapterList.MissingCount(
                    id = "${lowerChapter?.id}-${higherChapter.id}",
                    count = missingCount,
                )
            }
    }
}

private fun List<ChapterList.Item>.previewReadingChapters(
    manga: Manga,
    memberIds: List<Long>,
): List<ChapterList.Item> {
    return applyFiltersForPreview(manga)
        .sortedForReading(manga, memberIds)
}

private fun List<ChapterList.Item>.applyFiltersForPreview(
    manga: Manga,
): List<ChapterList.Item> {
    val isLocalManga = manga.isLocal()
    val unreadFilter = manga.unreadFilter
    val downloadedFilter = manga.downloadedFilter
    val bookmarkedFilter = manga.bookmarkedFilter
    return asSequence()
        .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
        .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
        .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
        .toList()
}

private fun List<ChapterList.Item>.sortedForMergedDisplay(
    manga: Manga,
    memberIds: List<Long>,
): List<ChapterList.Item> {
    val orderedIds = map(ChapterList.Item::chapter)
        .sortedForMergedDisplay(manga, memberIds)
        .mapIndexed { index, chapter -> chapter.id to index }
        .toMap()
    return sortedBy { item ->
        orderedIds[item.chapter.id] ?: Int.MAX_VALUE
    }
}

private fun List<ChapterList.Item>.sortedForReading(
    manga: Manga,
    memberIds: List<Long>,
): List<ChapterList.Item> {
    val orderedIds = map(ChapterList.Item::chapter)
        .sortedForReading(manga, memberIds)
        .mapIndexed { index, chapter -> chapter.id to index }
        .toMap()
    return sortedBy { item ->
        orderedIds[item.chapter.id] ?: Int.MAX_VALUE
    }
}

internal fun <DownloadChanges, DownloadQueue> mergeAwareMangaAndChaptersFlow(
    mangaAndChaptersFlow: Flow<Pair<Manga, List<Chapter>>>,
    mergeGroupFlow: Flow<List<MangaMerge>>,
    downloadChangesFlow: Flow<DownloadChanges>,
    downloadQueueFlow: Flow<DownloadQueue>,
): Flow<Pair<Manga, List<Chapter>>> {
    return combine(
        mangaAndChaptersFlow.distinctUntilChanged(),
        mergeGroupFlow.distinctUntilChanged(),
        downloadChangesFlow,
        downloadQueueFlow,
    ) { mangaAndChapters, _, _, _ -> mangaAndChapters }
}

internal fun dialogRemainingIds(
    dialog: MangaScreenModel.Dialog.ManageMerge,
    mangaIdsToRemove: Collection<Long>,
): List<Long> {
    val mangaIdsToRemoveSet = mangaIdsToRemove.toSet()
    return dialog.members.map { it.id }
        .filterNot(mangaIdsToRemoveSet::contains)
}

internal fun resolveManageMergeTargetId(targetId: Long, remainingIds: List<Long>): Long? {
    return remainingIds.firstOrNull { it == targetId } ?: remainingIds.firstOrNull()
}
