package com.minimont.desktop

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something the notification offered to do about itself. */
data class NoteAction(val title: String, val index: Int)

/** One notification, with enough of it to be worth showing rather than counting. */
data class Note(
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val openable: Boolean,
    /** False for the ones that are merely happening: there is nothing to dismiss. */
    val dismissable: Boolean,
    /** The app's own accent, already darkened far enough to read white type on. */
    val colour: Int,
    val picture: ImageBitmap?,
    val actions: List<NoteAction>
)

/**
 * What is standing, and everything that can be done to it.
 *
 * miniMont holds the list while it is on screen — the count on the taskbar has to be true, and the
 * centre behind it has to show what the notification actually says rather than that there is one.
 * Nothing is stored and nothing is read that is not shown.
 */
object Notifications {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes = _notes.asStateFlow()

    /**
     * The ones that are not asking anything of you.
     *
     * A media player, a download, a VPN, a step counter — things that are *happening* rather than
     * things to deal with. They cannot be dismissed and they never go to zero, so counting them
     * with the rest makes the number on the taskbar meaningless. Kept, because sometimes you do
     * want to see what is running, and kept apart, because most of the time you do not.
     */
    private val _ongoing = MutableStateFlow<List<Note>>(emptyList())
    val ongoing = _ongoing.asStateFlow()

    @Volatile
    internal var service: MontNotificationListener? = null

    internal fun set(notes: List<Note>, ongoing: List<Note>) {
        _notes.value = notes
        _ongoing.value = ongoing
    }

    /** Open the notification's own app, exactly as tapping it in the shade would. */
    fun open(key: String) {
        service?.open(key)
    }

    /** Do one of the things the notification offered — reply, mark read, whatever it named. */
    fun act(key: String, index: Int) {
        service?.act(key, index)
    }

    fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
    }

    /** All of them, which is the only thing anybody ever wants to do to all of them. */
    fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
    }

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
        val found = find(key) ?: return
        runCatching { found.notification.contentIntent?.send() }
        if (found.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            runCatching { cancelNotification(key) }
        }
    }

    /**
     * Fire one of the notification's own actions.
     *
     * An action that wants text typed into it carries a RemoteInput, and sending it bare does what
     * tapping it on the phone does when you have not typed anything — it opens the place you type.
     * miniMont does not put a text field in the card for it, because a reply box that silently
     * sends nothing would be worse than not offering one.
     */
    fun act(key: String, index: Int) {
        val found = find(key) ?: return
        val action = found.notification.actions?.getOrNull(index) ?: return
        runCatching { action.actionIntent?.send() }
    }

    private fun find(key: String): StatusBarNotification? =
        runCatching { activeNotifications?.firstOrNull { it.key == key } }.getOrNull()

    private fun refresh() {
        val all = runCatching { activeNotifications.orEmpty().map { read(it) to it } }
            .getOrDefault(emptyList())
        val (ongoing, standing) = all.partition { (_, raw) ->
            raw.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        }
        Notifications.set(standing.map { it.first }, ongoing.map { it.first })
    }

    private fun read(standing: StatusBarNotification): Note {
        val notification = standing.notification
        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        return Note(
            key = standing.key,
            packageName = standing.packageName,
            app = label(standing.packageName),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = text,
            openable = notification.contentIntent != null,
            dismissable = notification.flags and Notification.FLAG_ONGOING_EVENT == 0,
            colour = readable(notification.color),
            picture = picture(standing),
            actions = notification.actions.orEmpty().mapIndexedNotNull { index, action ->
                action.title?.toString()?.takeIf { it.isNotBlank() }?.let { NoteAction(it, index) }
            }
        )
    }

    /**
     * The app's accent, dark enough to read white Mont Black on.
     *
     * Apps choose their accent to sit behind their own dark type, or choose nothing at all. A title
     * bar is white type, so a bright yellow brand colour has to come down until it can carry one —
     * scaled rather than blended, so it stays recognisably the app's colour rather than becoming
     * grey with a memory of it.
     */
    private fun readable(colour: Int): Int {
        if (colour == 0) return DEFAULT_COLOUR
        var red = (colour shr 16) and 0xFF
        var green = (colour shr 8) and 0xFF
        var blue = colour and 0xFF
        var luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
        var guard = 0
        while (luminance > 0.42 && guard++ < 8) {
            red = (red * 0.82).toInt()
            green = (green * 0.82).toInt()
            blue = (blue * 0.82).toInt()
            luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
        }
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** The picture a notification carries, if it carries one worth the room. */
    private fun picture(standing: StatusBarNotification): ImageBitmap? = runCatching {
        val extras = standing.notification.extras
        val bitmap = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }
        bitmap?.asImageBitmap() ?: largeIcon(standing)
    }.getOrNull()

    private fun largeIcon(standing: StatusBarNotification): ImageBitmap? = runCatching {
        val icon: Icon = standing.notification.getLargeIcon() ?: return null
        val drawable = icon.loadDrawable(this) ?: return null
        val width = drawable.intrinsicWidth.coerceIn(1, 256)
        val height = drawable.intrinsicHeight.coerceIn(1, 256)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(android.graphics.Canvas(bitmap))
        bitmap.asImageBitmap()
    }.getOrNull()

    private fun label(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private companion object {
        /** For the apps that name no colour at all. Mont's surface, so they read as unbranded. */
        const val DEFAULT_COLOUR = 0xFF1A1A1A.toInt()
    }
}
