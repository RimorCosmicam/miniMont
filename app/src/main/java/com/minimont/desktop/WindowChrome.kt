@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.minimont.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import com.minimont.DesktopWindow
import com.minimont.ui.mont.LocalMontScale
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontSurface
import com.minimont.ui.mont.MontWhite
import androidx.compose.runtime.CompositionLocalProvider

/** How tall a caption is, and how thick the frame around a window is. */
const val CAPTION = 22
private const val FRAME = 1

/**
 * Mont's own chrome, on somebody else's window.
 *
 * Android attaches its own decor to a freeform task — a small handle, a resize region and an
 * outline — and none of it can be turned off from here; the Samsung setting that looks like it
 * should was measured and does nothing. So miniMont draws over it rather than instead of it: a
 * caption above the window's top edge and a thin frame around its bounds, both as windows of our
 * own on the same display, above the app.
 *
 * Two windows per app, and for a reason. The frame has to cover the window and must not take a
 * single touch, so it is marked untouchable. The caption has to take them all, so it is separate
 * and is only as big as itself. One window doing both would either swallow every click meant for
 * the app or accept none meant for the chrome.
 */
class WindowChrome(
    private val context: Context,
    private val display: Display,
    private val controller: DesktopController
) {
    private val captions = mutableMapOf<Int, CaptionWindow>()
    private val frames = mutableMapOf<Int, FrameWindow>()

    /** Redraw the chrome for whatever is open, adding, moving and removing as the desktop changes. */
    fun show(windows: List<DesktopWindow>, label: (String) -> String) {
        val open = windows.associateBy { it.taskId }

        (captions.keys - open.keys).toList().forEach { taskId ->
            runCatching { captions.remove(taskId)?.dismiss() }
            runCatching { frames.remove(taskId)?.dismiss() }
        }

        open.forEach { (taskId, window) ->
            val caption = captions.getOrPut(taskId) {
                CaptionWindow(context, display, controller, taskId).also { runCatching { it.show() } }
            }
            caption.place(window, label(window.packageName))

            val frame = frames.getOrPut(taskId) {
                FrameWindow(context, display).also { runCatching { it.show() } }
            }
            frame.place(window)
        }
    }

    fun clear() {
        captions.values.forEach { runCatching { it.dismiss() } }
        frames.values.forEach { runCatching { it.dismiss() } }
        captions.clear()
        frames.clear()
    }
}

/** The thin black frame around a window. Drawn over it, and untouchable. */
private class FrameWindow(context: Context, display: Display) :
    ChromeWindow(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        content {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(FRAME.dp)
                    .background(Color.Transparent)
            )
            // Drawn as four edges rather than a bordered box: a box would paint over the app.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().size(FRAME.dp).background(Color.Black))
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.size(FRAME.dp).fillMaxSize().background(Color.Black))
                    Box(Modifier.weight(1f))
                    Box(Modifier.size(FRAME.dp).fillMaxSize().background(Color.Black))
                }
                Box(Modifier.fillMaxWidth().size(FRAME.dp).background(Color.Black))
            }
        }
    }

    fun place(window: DesktopWindow) {
        move(
            window.left - FRAME,
            window.top - FRAME,
            (window.right - window.left) + FRAME * 2,
            (window.bottom - window.top) + FRAME * 2
        )
    }
}

/**
 * The caption above a window: its name, and the one square that opens everything else.
 *
 * Black, so it reads as part of the frame rather than as a Mont card that happens to be there, with
 * the name in white Mont Black at the left and the mustard square at the far end.
 */
private class CaptionWindow(
    context: Context,
    display: Display,
    private val controller: DesktopController,
    private val taskId: Int
) : ChromeWindow(context, display) {

    private var title by mutableStateOf("")
    private var menu by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        content {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MontLabel(
                        title.uppercase(),
                        Modifier.weight(1f),
                        size = 12,
                        alpha = MontWhite.PRIMARY
                    )
                    // The only control on the frame, and the only coloured thing on it. Everything
                    // a window can be told to do is behind it.
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(MontAccent.Mustard)
                            .combinedClickable { menu = !menu }
                    )
                }

                if (menu) {
                    Column(
                        Modifier
                            .background(MontSurface)
                            .padding(start = 14.dp, top = 8.dp, end = 10.dp, bottom = 8.dp)
                    ) {
                        listOf(
                            "Fill the screen" to "fill",
                            "Left half" to "left",
                            "Right half" to "right",
                            "Top left" to "tl",
                            "Top right" to "tr",
                            "Bottom left" to "bl",
                            "Bottom right" to "br"
                        ).forEach { (label, where) ->
                            MontRow(label = label) {
                                controller.arrange(taskId, where)
                                menu = false
                            }
                        }
                        MontRow(label = "Minimise") {
                            controller.minimise(taskId)
                            menu = false
                        }
                        MontRow(label = "Close") {
                            controller.closeTask(taskId)
                            menu = false
                        }
                    }
                }
            }
        }
    }

    fun place(window: DesktopWindow, name: String) {
        title = name
        // Above the window's own top edge, so the caption never covers the app's first line.
        move(window.left, window.top - CAPTION, window.right - window.left, CAPTION + MENU)
    }

    private companion object {
        /** Room for the arrangement menu to open into without moving the window. */
        const val MENU = 260
    }
}

/** The plumbing every piece of chrome needs: a presentation with the owners Compose looks for. */
private abstract class ChromeWindow(context: Context, display: Display) :
    Presentation(context, display), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

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
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setGravity(Gravity.TOP or Gravity.START)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        setCancelable(false)
    }

    protected fun content(body: @Composable () -> Unit) {
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ChromeWindow)
            setViewTreeViewModelStoreOwner(this@ChromeWindow)
            setViewTreeSavedStateRegistryOwner(this@ChromeWindow)
            setContent { CompositionLocalProvider(LocalMontScale provides 1f) { body() } }
        }
        setContentView(view)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Move and size this piece of chrome, in the display's own pixels. */
    fun move(x: Int, y: Int, width: Int, height: Int) {
        val attributes = window?.attributes ?: return
        attributes.x = x
        attributes.y = y
        attributes.width = width
        attributes.height = height
        window?.attributes = attributes
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
}
