package com.minimont.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.minimont.DesktopController
import com.minimont.desktop.DesktopStore
import com.minimont.ui.mont.LocalMontScale

/** Android's own back key, sent to the display rather than to the phone. */
private const val KEYCODE_BACK = 4

/**
 * The dock, the status card and whatever is open above them, floating over the desktop.
 *
 * A presentation rather than part of the backdrop activity, because a dock belongs above the
 * windows and the backdrop belongs below them. The window wraps its content, so it is a band along
 * the bottom of the display and not an invisible sheet over the whole of it — everything outside
 * that band is touched straight through to whatever app is there.
 */
class DesktopChrome(
    context: Context,
    display: Display,
    private val controller: DesktopController,
    private val onPickImage: () -> Unit,
    private val onGrantNotifications: () -> Unit
) : Presentation(context, display), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        saved.performRestore(null)
        super.onCreate(savedInstanceState)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Not modal and not focusable: the dock takes the touches that land on it and leaves
            // every other touch, and every key, to the window that should have had it.
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@DesktopChrome)
            setViewTreeViewModelStoreOwner(this@DesktopChrome)
            setViewTreeSavedStateRegistryOwner(this@DesktopChrome)
            setContent {
                val state by controller.state.collectAsState()
                // Mont was drawn for a screen three inches across. Everything keeps its proportions
                // and grows together rather than being re-specified for a monitor.
                CompositionLocalProvider(LocalMontScale provides scaleFor(display)) {
                    MontDesktop(
                        running = state.openApps,
                        onLaunch = controller::launch,
                        onClose = controller::close,
                        onPickImage = onPickImage,
                        onGrantNotifications = onGrantNotifications,
                        onWifi = controller::wifi,
                        onBatterySaver = controller::batterySaver,
                        onFit = controller::fit,
                        onArea = controller::setArea,
                        onOpenPhone = controller::open,
                        onArrangeWindow = controller::arrange,
                        onSpawnWindow = controller::spawn,
                        // Back goes back in whatever has focus on this display.
                        onBack = { controller.key(KEYCODE_BACK) },
                        // Home shows the desktop rather than leaving for the phone's launcher.
                        // Leaving would end the thing you are using.
                        onHome = controller::showBackdrop,
                        armed = state.armed,
                        onArm = controller::armRightClick,
                        onScreenshot = controller::screenshot,
                        shotAt = state.shotAt,
                        shotSaved = state.shot != null,
                        desktops = state.desktops,
                        desktop = state.desktop,
                        onShowDesktop = controller::showDesktop,
                        onAddDesktop = controller::addDesktop,
                        onRemoveDesktop = controller::removeDesktop
                    )
                }
            }
        }
        setContentView(view)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onStop()
    }

    private companion object {
        /**
         * How much bigger the desktop is than the cover display Mont was drawn for.
         *
         * Measured against the short edge, and capped, so a very large display does not turn the
         * language into signage.
         */
        fun scaleFor(display: Display): Float {
            val metrics = android.util.DisplayMetrics().also { display.getMetrics(it) }
            val shortEdgeDp =
                minOf(metrics.widthPixels, metrics.heightPixels) / (metrics.densityDpi / 160f)
            return (shortEdgeDp / 560f).coerceIn(1f, 1.6f)
        }
    }
}
