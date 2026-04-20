package eu.kanade.tachiyomi.data.library

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import eu.kanade.domain.anime.model.toMangaCover
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.runBlocking
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetMergedAnime
import tachiyomi.domain.anime.model.AnimeEpisode
import tachiyomi.domain.anime.model.AnimeTitle
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.math.RoundingMode
import java.text.NumberFormat

class AnimeLibraryUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
    private val getMergedAnime: GetMergedAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
) {

    private val percentFormatter = NumberFormat.getPercentInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    private val cancelIntent by lazy {
        NotificationReceiver.cancelAnimeLibraryUpdatePendingBroadcast(context)
    }

    private val notificationBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(R.drawable.ic_close_24dp, context.stringResource(MR.strings.action_cancel), cancelIntent)
        }
    }

    fun showProgressNotification(anime: List<AnimeTitle>, current: Int, total: Int) {
        progressNotificationBuilder
            .setContentTitle(
                context.stringResource(
                    MR.strings.notification_updating_progress,
                    percentFormatter.format(current.toFloat() / total),
                ),
            )

        if (!securityPreferences.hideNotificationContent.get()) {
            val updatingText = anime.joinToString("\n") { it.displayTitle.chop(40) }
            progressNotificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(updatingText))
        }

        context.notify(
            Notifications.ID_ANIME_LIBRARY_PROGRESS,
            progressNotificationBuilder
                .setProgress(total, current, false)
                .build(),
        )
    }

    fun showUpdateErrorNotification(failed: Int, uri: Uri) {
        if (failed == 0) {
            return
        }

        context.notify(
            Notifications.ID_ANIME_LIBRARY_ERROR,
            Notifications.CHANNEL_LIBRARY_ERROR,
        ) {
            setContentTitle(context.pluralStringResource(MR.plurals.notification_update_error, failed, failed))
            setContentText(context.stringResource(MR.strings.action_show_errors))
            setSmallIcon(R.drawable.ic_mihon)

            setContentIntent(NotificationReceiver.openErrorLogPendingActivity(context, uri))
        }
    }

    fun showUpdateNotifications(updates: List<Pair<AnimeTitle, Array<AnimeEpisode>>>) {
        val childUpdates = runBlocking {
            updates.map { (anime, episodes) ->
                NotificationAnimeUpdate(
                    originAnime = anime,
                    visibleAnime = getVisibleAnime(anime),
                    episodes = episodes,
                )
            }
        }

        context.notify(
            Notifications.ID_NEW_EPISODES,
            Notifications.CHANNEL_NEW_EPISODES,
        ) {
            setContentTitle(context.stringResource(MR.strings.notification_new_episodes))
            if (childUpdates.size == 1 && !securityPreferences.hideNotificationContent.get()) {
                setContentText(childUpdates.first().originAnime.displayTitle.chop(NOTIF_TITLE_MAX_LEN))
            } else {
                setContentText(
                    context.pluralStringResource(
                        MR.plurals.notification_new_episodes_summary,
                        childUpdates.size,
                        childUpdates.size,
                    ),
                )

                if (!securityPreferences.hideNotificationContent.get()) {
                    setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            childUpdates.joinToString("\n") {
                                it.originAnime.displayTitle.chop(NOTIF_TITLE_MAX_LEN)
                            },
                        ),
                    )
                }
            }

            setSmallIcon(R.drawable.ic_mihon)
            setLargeIcon(notificationBitmap)

            setGroup(Notifications.GROUP_NEW_EPISODES)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            setGroupSummary(true)
            priority = NotificationCompat.PRIORITY_HIGH

            setContentIntent(getNotificationIntent())
            setAutoCancel(true)
        }

        if (!securityPreferences.hideNotificationContent.get()) {
            launchUI {
                context.notify(
                    childUpdates.map { update ->
                        NotificationManagerCompat.NotificationWithIdAndTag(
                            update.originAnime.id.hashCode(),
                            createNewEpisodesNotification(
                                anime = update.originAnime,
                                visibleAnime = update.visibleAnime,
                                episodes = update.episodes,
                            ),
                        )
                    },
                )
            }
        }
    }

    fun cancelProgressNotification() {
        context.cancelNotification(Notifications.ID_ANIME_LIBRARY_PROGRESS)
    }

    private suspend fun createNewEpisodesNotification(
        anime: AnimeTitle,
        visibleAnime: AnimeTitle,
        episodes: Array<AnimeEpisode>,
    ): Notification {
        val icon = getAnimeIcon(anime)
        return context.notificationBuilder(Notifications.CHANNEL_NEW_EPISODES) {
            setContentTitle(anime.displayTitle)

            val description = getNewEpisodesDescription(episodes)
            setContentText(description)
            setStyle(NotificationCompat.BigTextStyle().bigText(description))

            setSmallIcon(R.drawable.ic_mihon)

            if (icon != null) {
                setLargeIcon(icon)
            }

            setGroup(Notifications.GROUP_NEW_EPISODES)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            priority = NotificationCompat.PRIORITY_HIGH

            setContentIntent(
                NotificationReceiver.openEpisodePendingActivity(
                    context,
                    visibleAnimeId = visibleAnime.id,
                    ownerAnimeId = anime.id,
                    episodes.first().id,
                ),
            )
            setAutoCancel(true)

            addAction(
                R.drawable.ic_done_24dp,
                context.stringResource(MR.strings.action_mark_as_watched),
                NotificationReceiver.markAsWatchedPendingBroadcast(
                    context,
                    anime.id,
                    episodes,
                    Notifications.ID_NEW_EPISODES,
                ),
            )
            addAction(
                R.drawable.ic_book_24dp,
                context.stringResource(MR.strings.action_view_episodes),
                NotificationReceiver.openAnimeEntryPendingActivity(
                    context,
                    visibleAnime.id,
                    Notifications.ID_NEW_EPISODES,
                ),
            )
        }.build()
    }

    private suspend fun getAnimeIcon(anime: AnimeTitle): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(anime.toMangaCover())
            .transformations(CircleCropTransformation())
            .size(NOTIF_ICON_SIZE)
            .build()
        val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources)
        return drawable?.getBitmapOrNull()
    }

    private suspend fun getVisibleAnime(anime: AnimeTitle): AnimeTitle {
        val visibleAnimeId = getMergedAnime.awaitVisibleTargetId(anime.id)
        return getAnime.await(visibleAnimeId) ?: anime
    }

    private fun getNewEpisodesDescription(episodes: Array<AnimeEpisode>): String {
        val displayableEpisodeNumbers = episodes
            .mapNotNull { episode ->
                episode.episodeNumber.takeIf { number -> number >= 0.0 }?.let(::formatChapterNumber)
            }
            .toSet()

        return when (displayableEpisodeNumbers.size) {
            0 -> {
                context.pluralStringResource(
                    MR.plurals.notification_episodes_generic,
                    episodes.size,
                    episodes.size,
                )
            }
            1 -> {
                val remaining = episodes.size - displayableEpisodeNumbers.size
                if (remaining == 0) {
                    context.stringResource(
                        MR.strings.notification_episodes_single,
                        displayableEpisodeNumbers.first(),
                    )
                } else {
                    context.stringResource(
                        MR.strings.notification_episodes_single_and_more,
                        displayableEpisodeNumbers.first(),
                        remaining,
                    )
                }
            }
            else -> {
                val shouldTruncate = displayableEpisodeNumbers.size > NOTIF_MAX_EPISODES
                if (shouldTruncate) {
                    val remaining = displayableEpisodeNumbers.size - NOTIF_MAX_EPISODES
                    val joinedEpisodeNumbers = displayableEpisodeNumbers
                        .take(NOTIF_MAX_EPISODES)
                        .joinToString(", ")
                    context.pluralStringResource(
                        MR.plurals.notification_episodes_multiple_and_more,
                        remaining,
                        joinedEpisodeNumbers,
                        remaining,
                    )
                } else {
                    context.stringResource(
                        MR.strings.notification_episodes_multiple,
                        displayableEpisodeNumbers.joinToString(", "),
                    )
                }
            }
        }
    }

    private fun getNotificationIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Constants.SHORTCUT_UPDATES
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class NotificationAnimeUpdate(
        val originAnime: AnimeTitle,
        val visibleAnime: AnimeTitle,
        val episodes: Array<AnimeEpisode>,
    )
}

private const val NOTIF_MAX_EPISODES = 5
private const val NOTIF_TITLE_MAX_LEN = 45
private const val NOTIF_ICON_SIZE = 192
