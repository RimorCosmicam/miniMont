package com.minimont.desktop

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many notifications are standing, for the one number on the status card. */
object NotificationCount {
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()

    internal fun set(value: Int) {
        _count.value = value
    }

    /** Whether Android has been told this app may see them. Without it the number stays at zero. */
    fun granted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }
}

/**
 * A count, and nothing else.
 *
 * miniMont does not show notifications on the desktop and does not read them. It needs one number,
 * so this service keeps one number: the moment it starts to keep anything more it becomes a thing
 * that has to be justified, and a status card does not justify reading somebody's messages.
 */
class MontNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        refresh()
    }

    override fun onNotificationPosted(notification: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(notification: StatusBarNotification?) = refresh()

    private fun refresh() {
        NotificationCount.set(runCatching { activeNotifications?.size ?: 0 }.getOrDefault(0))
    }
}
