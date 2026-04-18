package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.SyncAnimeWithSource
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.AnimeSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class AnimeLibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val animeRepository: AnimeRepository = Injekt.get()
    private val categoryRepository: CategoryRepository = Injekt.get()
    private val animeSourceManager: AnimeSourceManager = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val syncAnimeWithSource: SyncAnimeWithSource = Injekt.get()

    private val notifier = AnimeLibraryUpdateNotifier(context)

    private var animeToUpdate: List<AnimeTitle> = emptyList()

    override suspend fun doWork(): Result {
        val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
        val sourceId = inputData.getLong(KEY_SOURCE, -1L)

        logcat(LogPriority.INFO) {
            "Starting anime library update (category=$categoryId, source=$sourceId)"
        }

        setForegroundSafely()
        libraryPreferences.lastUpdatedTimestamp.set(Instant.now().toEpochMilli())
        addAnimeToQueue(categoryId, sourceId)

        return withIOContext {
            try {
                updateAnimeList()
                logcat(LogPriority.INFO) { "Anime library update completed" }
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    logcat(LogPriority.INFO) { "Anime library update cancelled" }
                    Result.success()
                } else {
                    logcat(LogPriority.ERROR, e) { "Anime library update failed" }
                    Result.failure()
                }
            } finally {
                notifier.cancelProgressNotification()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifier = AnimeLibraryUpdateNotifier(context)
        return ForegroundInfo(
            Notifications.ID_ANIME_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun addAnimeToQueue(categoryId: Long, sourceId: Long) {
        val favorites = animeRepository.getFavorites()
        val categoryIdsByAnimeId = if (favorites.isEmpty()) {
            emptyMap()
        } else {
            categoryRepository.getAnimeCategoryIds(favorites.map(AnimeTitle::id))
        }

        animeToUpdate = filterAnimeToUpdate(
            favorites = favorites,
            categoryIdsByAnimeId = categoryIdsByAnimeId,
            categoryId = categoryId,
            sourceId = sourceId,
            includedCategories = libraryPreferences.updateCategories.get().map(String::toLong).toSet(),
            excludedCategories = libraryPreferences.updateCategoriesExclude.get().map(String::toLong).toSet(),
        ).sortedBy { it.title.lowercase() }

        logcat(LogPriority.INFO) { "Queued ${animeToUpdate.size} anime library entries for update" }
    }

    private suspend fun updateAnimeList() {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInt(0)
        val currentlyUpdatingAnime = CopyOnWriteArrayList<AnimeTitle>()
        val newUpdates = CopyOnWriteArrayList<Pair<AnimeTitle, Array<AnimeEpisode>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<AnimeTitle, String?>>()

        logcat(LogPriority.INFO) { "Processing ${animeToUpdate.size} queued anime library entries" }

        coroutineScope {
            animeToUpdate.groupBy { it.source }.values
                .map { animeInSource ->
                    async {
                        semaphore.withPermit {
                            animeInSource.forEach { anime ->
                                ensureActive()

                                val currentAnime = runCatching { animeRepository.getAnimeById(anime.id) }
                                    .getOrNull()
                                    ?.takeIf { it.favorite }
                                    ?: return@forEach

                                withUpdateNotification(
                                    updatingAnime = currentlyUpdatingAnime,
                                    completed = progressCount,
                                    anime = currentAnime,
                                ) {
                                    try {
                                        val result = syncAnimeWithSource(currentAnime)
                                        if (result.hasChanges) {
                                            if (result.insertedEpisodes.isNotEmpty()) {
                                                libraryPreferences.newUpdatesCount.getAndSet {
                                                    it + result.insertedEpisodesCount
                                                }
                                                newUpdates.add(currentAnime to result.insertedEpisodes.toTypedArray())
                                            }
                                            logcat(LogPriority.INFO) {
                                                "Anime library update found ${result.insertedEpisodesCount} new episode(s) for ${currentAnime.title}"
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        if (e is CancellationException) {
                                            throw e
                                        }

                                        val errorMessage = with(context) { e.formattedMessage }
                                        failedUpdates.add(currentAnime to errorMessage)

                                        val sourceName = animeSourceManager.get(currentAnime.source)?.name
                                            ?: context.stringResource(
                                                MR.strings.source_not_installed,
                                                currentAnime.source.toString(),
                                            )
                                        logcat(LogPriority.ERROR, e) {
                                            buildString {
                                                append(
                                                    "Anime library update failed for ${currentAnime.title} ($sourceName)",
                                                )
                                                append(": $errorMessage")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            notifier.showUpdateNotifications(newUpdates)
        }

        logcat(LogPriority.INFO) {
            "Anime library update finished with ${newUpdates.size} updated entr${if (newUpdates.size == 1) "y" else "ies"} and ${failedUpdates.size} failure${if (failedUpdates.size == 1) "" else "s"}"
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
            )
        }
    }

    private suspend fun withUpdateNotification(
        updatingAnime: CopyOnWriteArrayList<AnimeTitle>,
        completed: AtomicInt,
        anime: AnimeTitle,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingAnime.add(anime)
        notifier.showProgressNotification(
            updatingAnime,
            completed.load(),
            animeToUpdate.size,
        )

        block()

        ensureActive()

        updatingAnime.remove(anime)
        completed.incrementAndFetch()
        notifier.showProgressNotification(
            updatingAnime,
            completed.load(),
            animeToUpdate.size,
        )
    }

    private fun writeErrorFile(errors: List<Pair<AnimeTitle, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_anime_update_errors.txt")
                file.bufferedWriter().use { out ->
                    out.write(context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n")
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, animes) ->
                        out.write("\n! ${error}\n")
                        animes.groupBy { it.source }.forEach { (srcId, sourceAnimes) ->
                            val sourceName = animeSourceManager.get(srcId)?.name
                                ?: context.stringResource(MR.strings.source_not_installed, srcId.toString())
                            out.write("  # $sourceName\n")
                            sourceAnimes.forEach {
                                out.write("    - ${it.displayTitle}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    companion object {
        private const val TAG = "AnimeLibraryUpdate"
        private const val WORK_NAME_MANUAL = "AnimeLibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://mihon.app/docs/guides/troubleshooting/"

        private const val KEY_CATEGORY = "category"
        private const val KEY_SOURCE = "source"

        fun startNow(
            context: Context,
            category: Category? = null,
            sourceId: Long? = null,
        ): Boolean {
            val wm = context.workManager
            if (wm.isRunning(TAG)) {
                return false
            }

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
                KEY_SOURCE to sourceId,
            )
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        fun stop(context: Context) {
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                .forEach {
                    wm.cancelWorkById(it.id)
                }
        }
    }
}

internal fun filterAnimeToUpdate(
    favorites: List<AnimeTitle>,
    categoryIdsByAnimeId: Map<Long, List<Long>>,
    categoryId: Long,
    sourceId: Long,
    includedCategories: Set<Long>,
    excludedCategories: Set<Long>,
): List<AnimeTitle> {
    return favorites.filter { anime ->
        val effectiveCategoryIds = categoryIdsByAnimeId[anime.id].orEmpty()
            .ifEmpty { listOf(Category.UNCATEGORIZED_ID) }

        if (categoryId != -1L || sourceId != -1L) {
            val matchesCategory = categoryId == -1L || categoryId in effectiveCategoryIds
            val matchesSource = sourceId == -1L || anime.source == sourceId
            matchesCategory && matchesSource
        } else {
            val included =
                includedCategories.isEmpty() || effectiveCategoryIds.intersect(includedCategories).isNotEmpty()
            val excluded = effectiveCategoryIds.intersect(excludedCategories).isNotEmpty()
            included && !excluded
        }
    }
}
