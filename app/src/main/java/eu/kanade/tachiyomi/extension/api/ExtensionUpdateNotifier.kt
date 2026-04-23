package eu.kanade.tachiyomi.extension.api

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.model.ExtensionType
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.domain.profile.model.ProfileType
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
) {
    fun autoUpdated(type: ExtensionType, names: List<String>) {
        if (names.isEmpty()) {
            context.cancelNotification(type.autoUpdatedNotificationId())
            return
        }

        context.notify(
            type.autoUpdatedNotificationId(),
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(
                context.pluralStringResource(
                    MR.plurals.extension_auto_update_notification_updated,
                    names.size,
                    names.size,
                ),
            )
            if (!securityPreferences.hideNotificationContent.get()) {
                val extNames = names.joinToString(", ")
                setContentText(extNames)
                setStyle(NotificationCompat.BigTextStyle().bigText(extNames))
            }
            setSmallIcon(R.drawable.ic_extension_24dp)
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context, type.toProfileType()))
            setAutoCancel(true)
        }
    }

    fun promptUpdates(type: ExtensionType, names: List<String>) {
        if (names.isEmpty()) {
            context.cancelNotification(type.pendingUpdatesNotificationId())
            return
        }

        context.notify(
            type.pendingUpdatesNotificationId(),
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(
                context.pluralStringResource(
                    MR.plurals.update_check_notification_ext_updates,
                    names.size,
                    names.size,
                ),
            )
            if (!securityPreferences.hideNotificationContent.get()) {
                val extNames = names.joinToString(", ")
                setContentText(extNames)
                setStyle(NotificationCompat.BigTextStyle().bigText(extNames))
            }
            setSmallIcon(R.drawable.ic_extension_24dp)
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context, type.toProfileType()))
            setAutoCancel(true)
        }
    }

    fun dismiss(type: ExtensionType) {
        context.cancelNotification(type.pendingUpdatesNotificationId())
        context.cancelNotification(type.autoUpdatedNotificationId())
    }

    private fun ExtensionType.pendingUpdatesNotificationId(): Int {
        return when (this) {
            ExtensionType.MANGA -> Notifications.ID_UPDATES_TO_MANGA_EXTS
            ExtensionType.ANIME -> Notifications.ID_UPDATES_TO_ANIME_EXTS
        }
    }

    private fun ExtensionType.autoUpdatedNotificationId(): Int {
        return when (this) {
            ExtensionType.MANGA -> Notifications.ID_MANGA_EXTENSIONS_AUTO_UPDATED
            ExtensionType.ANIME -> Notifications.ID_ANIME_EXTENSIONS_AUTO_UPDATED
        }
    }

    private fun ExtensionType.toProfileType(): ProfileType {
        return when (this) {
            ExtensionType.MANGA -> ProfileType.MANGA
            ExtensionType.ANIME -> ProfileType.ANIME
        }
    }
}
