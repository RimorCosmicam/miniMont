package com.minimont

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import com.minimont.desktop.AppCatalog
import com.minimont.desktop.CursorLayer
import com.minimont.desktop.DesktopChrome
import com.minimont.desktop.DesktopStore
import com.minimont.desktop.WindowChrome
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.minimont.desktop.WallpaperPickerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What keeps the desktop alive, and what puts the chrome on it.
 *
 * The session lasts as long as the ADB stream, and the ADB stream lasts as long as this process, so
 * without a foreground service the desktop ends whenever Android decides to reclaim the app behind
 * a folded phone. That is the first half of what this is for.
 *
 * The second half is the dock. It is a presentation on the miniMont display, and a presentation
 * needs something that outlives the cover-screen window to hold it — the desktop should not go dark
 * because somebody closed the app they started it from.
 */
class MontService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: DesktopController
    private var chrome: DesktopChrome? = null
    private var cursor: CursorLayer? = null
    private var chromeWindows: WindowChrome? = null
    private var follow: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        DesktopStore.load(this)
        controller = DesktopController.of(this)
        startForeground(NOTIFICATION, notification(false))

        // Read the installed applications before anything asks for them. Loading and rasterising a
        // few hundred icons is not something to do inside the first composition of the dock.
        scope.launch(Dispatchers.IO) { AppCatalog.apps(this@MontService) }

        displays.registerDisplayListener(listener, null)
        attach()
    }

    private val displays by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    /**
     * The display arriving is the cue to put the dock on it.
     *
     * Found by name rather than by the id the host printed. The host is a separate process that may
     * have been started before this one, or restarted underneath it when a client asked for a
     * different size — and in both cases an id parsed out of a log line we were not listening to is
     * an id we do not have. The display calls itself miniMont; that is a better question to ask.
     */
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attach()
        override fun onDisplayRemoved(displayId: Int) = attach()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private var attachedTo: Int? = null

    private fun attach() {
        val display = displays.displays.firstOrNull { it.name == DISPLAY_NAME }
        if (display?.displayId == attachedTo) return

        follow?.cancel()
        follow = null
        chromeWindows?.clear()
        chromeWindows = null
        cursor?.let { runCatching { it.dismiss() } }
        cursor = null
        chrome?.let { runCatching { it.dismiss() } }
        chrome = null
        attachedTo = display?.displayId
        // Said rather than assumed: a notification claiming the desktop is running while there is
        // no display is the kind of small lie that makes every other message untrustworthy.
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(display != null))
        if (display == null) return

        chrome = DesktopChrome(this, display, controller, ::pickWallpaper, ::grantNotifications)
            .also { runCatching { it.show() } }
        // After the dock, so it is above it. A cursor that slides under the thing it is pointing at
        // is a cursor you cannot use to press the thing it is pointing at.
        cursor = CursorLayer(this, display, controller)
            .also { runCatching { it.show() } }

        // Chrome for every window, following what the host reports. The frames go up after the
        // cursor so the cursor stays on top of them; a pointer that slides under a window's own
        // title bar is a pointer you cannot use on it.
        val windows = WindowChrome(this, display, controller)
        chromeWindows = windows
        follow = scope.launch {
            controller.state
                .map { it.windows }
                .distinctUntilChanged()
                .collect { open ->
                    windows.show(open) { packageName ->
                        AppCatalog.byPackage(this@MontService, packageName)?.label ?: packageName
                    }
                }
        }
    }

    /**
     * Choosing a picture happens on the phone, not on the desktop.
     *
     * The file picker is another app's activity and it belongs on the screen the person is holding,
     * which on a folded Flip is not the display Android would send it to by default.
     */
    private fun pickWallpaper() {
        onPhone(Intent(this, WallpaperPickerActivity::class.java))
    }

    private fun onPhone(intent: Intent) {
        val options = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(controller.phoneDisplayId)
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        }
    }

    /** Notification access is granted in Android's settings, on the phone, like everything else. */
    private fun grantNotifications() {
        onPhone(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun onDestroy() {
        runCatching { displays.unregisterDisplayListener(listener) }
        follow?.cancel()
        chromeWindows?.clear()
        cursor?.dismiss()
        cursor = null
        chrome?.dismiss()
        chrome = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Asked to start again while already running, which happens on every desktop start.
     *
     * Re-checks for a display rather than assuming the one it has: the host is a separate process
     * that may have been restarted underneath us, and a new session means a new display id even
     * though the name is the same.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attach()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(running: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Desktop", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("miniMont")
            .setContentText(
                if (running) "The desktop is running."
                else "Waiting for a display."
            )
            .setSmallIcon(R.drawable.app_icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MontService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MontService::class.java))
        }

        /** What the host calls the display it makes, and how the dock finds it again. */
        const val DISPLAY_NAME = "miniMont"

        private const val CHANNEL = "desktop"
        private const val NOTIFICATION = 1
    }
}
