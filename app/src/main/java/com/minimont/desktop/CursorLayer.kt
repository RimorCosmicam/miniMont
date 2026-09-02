package com.minimont.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

/**
 * The cursor, drawn by us because nobody else will.
 *
 * Injected mouse events move the *framework's* idea of the pointer — windows take focus under it,
 * clicks land on what it is over — but not the sprite. The visible pointer on an ordinary display
 * is drawn from the input reader's own state, fed by real hardware, and an injected event never
 * reaches it. Which is why the desktop answered every click and there was nothing on screen to aim
 * with.
 *
 * So this is a full-screen window over everything, touchable by nobody, drawing one arrow at the
 * position the touchpad has been sending. It cannot drift: the same deltas that move the real
 * pointer move this one, clamped the same way, from the same starting point.
 */
class CursorLayer(
    context: Context,
    display: Display,
    private val controller: DesktopController
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
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Covers the whole display and must be invisible to every touch and every key, or it
            // would be a sheet of glass over the desktop that swallows the pointer it is drawing.
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
        }
        setCancelable(false)

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@CursorLayer)
            setViewTreeViewModelStoreOwner(this@CursorLayer)
            setViewTreeSavedStateRegistryOwner(this@CursorLayer)
            setContent {
                val position by controller.cursor.collectAsState()
                Canvas(Modifier.fillMaxSize()) {
                    val (x, y) = position
                    val arrow = Path().apply {
                        moveTo(x, y)
                        lineTo(x, y + 19f)
                        lineTo(x + 4.6f, y + 14.6f)
                        lineTo(x + 7.8f, y + 22f)
                        lineTo(x + 11f, y + 20.6f)
                        lineTo(x + 7.8f, y + 13.4f)
                        lineTo(x + 13.6f, y + 13.4f)
                        close()
                    }
                    // White, with the thinnest possible black keyline. Mont would rather have the
                    // arrow alone, but a white arrow over a white document is an arrow you have
                    // lost, and losing the pointer is the one failure a desktop cannot absorb.
                    drawPath(arrow, Color.White)
                    drawPath(arrow, Color.Black.copy(alpha = .92f), style = Stroke(width = 1.2f))
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
}
