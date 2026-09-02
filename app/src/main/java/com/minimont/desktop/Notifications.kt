package com.minimont.desktop

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One notification, reduced to what a desktop can show and act on. */
data class Note(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val openable: Boolean
)

/**
 * What is standing, and the two things that can be done to it.
 *
 * miniMont does not show notifications on the desktop and does not keep them. It holds the list
 * while it is on screen so that the count on the status card is true and the card behind it can
 * open one or throw it away. Nothing is stored, nothing is read that is not shown.
 */
object Notifications {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes = _notes.asStateFlow()

    val count get() = _notes

    @Volatile
    internal var service: MontNotificationListener? = null

    internal fun set(notes: List<Note>) {
        _notes.value = notes
    }

    /** Open the notification's own app, exactly as tapping it in the shade would. */
    fun open(key: String) {
        service?.open(key)
    }

    /** Throw it away, on the phone as well as here — there is only one of it. */
    fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
    }

    /** Whether Android has been told this app may see them. Without it the list stays empty. */
    fun granted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }
}

class MontNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Notifications.service = this
        refresh()
    }

    override fun onListenerDisconnected() {
        Notifications.service = null
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(notification: StatusBarNotification?) = refresh()

    fun open(key: String) {
        val found = runCatching {
            activeNotifications?.firstOrNull { it.key == key }
        }.getOrNull() ?: return
        runCatching { found.notification.contentIntent?.send() }
        // Tapping a notification in the shade dismisses it when the app said it should, and a
        // desktop that leaves it standing after opening it is a desktop with a count that lies.
        if (found.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            runCatching { cancelNotification(key) }
        }
    }

    private fun refresh() {
        val notes = runCatching {
            activeNotifications.orEmpty()
                // Ongoing notifications are not things you deal with, they are things that are
                // happening — a download, a call, a player. Counting them makes the number never
                // reach zero, which makes it worth nothing.
                .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
                .map { standing ->
                    val extras = standing.notification.extras
                    Note(
                        key = standing.key,
                        app = label(standing.packageName),
                        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                        openable = standing.notification.contentIntent != null
                    )
                }
        }.getOrDefault(emptyList())
        Notifications.set(notes)
    }

    private fun label(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
