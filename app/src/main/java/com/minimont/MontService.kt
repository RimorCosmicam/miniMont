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
import com.minimont.desktop.DesktopChrome
import com.minimont.desktop.DesktopStore
import com.minimont.desktop.WallpaperPickerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    override fun onCreate() {
        super.onCreate()
        DesktopStore.load(this)
        controller = DesktopController.of(this)
        startForeground(NOTIFICATION, notification())

        // Read the installed applications before anything asks for them. Loading and rasterising a
        // few hundred icons is not something to do inside the first composition of the dock.
        scope.launch(Dispatchers.IO) { AppCatalog.apps(this@MontService) }

        scope.launch {
            controller.state
                .map { it.displayId }
                .distinctUntilChanged()
                .collect { displayId -> onDisplay(displayId) }
        }
    }

    /**
     * The display arriving is the cue to put the dock on it.
     *
     * A display created by another process takes a moment to become visible here, so this waits for
     * it rather than deciding on the first look that there is nothing to draw on.
     */
    private suspend fun onDisplay(displayId: Int?) {
        chrome?.dismiss()
        chrome = null
        if (displayId == null) return

        val displays = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        repeat(20) {
            val display = displays.getDisplay(displayId)
            if (display != null) {
                chrome = DesktopChrome(
                    this, display, controller, ::pickWallpaper, ::grantNotifications
                ).also { runCatching { it.show() } }
                return
            }
            delay(100)
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
        chrome?.dismiss()
        chrome = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Desktop", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("miniMont")
            .setContentText("The desktop is running.")
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

        private const val CHANNEL = "desktop"
        private const val NOTIFICATION = 1
    }
}
