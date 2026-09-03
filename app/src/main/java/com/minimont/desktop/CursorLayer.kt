package com.minimont.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The cursor, drawn by us because nobody else will.
 *
 * Injected mouse events move the *framework's* idea of the pointer — windows take focus under it,
 * clicks land on what it is over — but not the sprite. The visible pointer on an ordinary display
 * is drawn from the input reader's own state, fed by real hardware, and an injected event never
 * reaches it. Which is why the desktop answered every click and there was nothing on screen to aim
 * with.
 *
 * So this is a window over everything, touchable by nobody, drawing one arrow. It cannot drift:
 * the same deltas that move the real pointer move this one, clamped the same way, from the same
 * starting point.
 *
 * It is the size of the arrow and it *moves*, rather than being a full-screen sheet that redraws an
 * arrow somewhere new. That is not tidiness, it is the picture quality: a full-screen window
 * repainting on every motion event hands the compositor the whole display as damaged, and the
 * encoder then spends a frame's worth of bits re-describing 1600x900 of unchanged desktop instead
 * of the thirty pixels that actually moved. A cursor should cost what a cursor costs.
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
            setLayout(SIZE, SIZE)
            setGravity(Gravity.TOP or Gravity.START)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Without this the window is kept inside the display's insets and the pointer stops
            // short of the edges it is supposed to be able to reach.
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
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
                // Drawn once, at the origin. The arrow never moves inside this window; the window
                // moves, and nothing is repainted to do it.
                Canvas(Modifier.fillMaxSize()) {
                    val arrow = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(0f, 19f)
                        lineTo(4.6f, 14.6f)
                        lineTo(7.8f, 22f)
                        lineTo(11f, 20.6f)
                        lineTo(7.8f, 13.4f)
                        lineTo(13.6f, 13.4f)
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
        arrow = view
        setContentView(view)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.RESUMED
        // Followed here rather than inside the composition: moving the window is a layout change,
        // and routing it through Compose would mean recomposing a picture that never changes in
        // order to put it somewhere else.
        follow = scope.launch {
            controller.cursor.collect { (x, y) ->
                val current = window?.attributes ?: return@collect
                val left = x.toInt()
                val top = y.toInt()
                if (current.x == left && current.y == top) return@collect
                current.x = left
                current.y = top
                window?.attributes = current
            }
        }
        // Out of the way while a screenshot is taken, and only for that.
        duck = scope.launch {
            controller.cursorHidden.collect { hidden ->
                arrow?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    override fun onStop() {
        follow?.cancel()
        follow = null
        duck?.cancel()
        duck = null
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onStop()
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var follow: Job? = null
    private var duck: Job? = null
    private var arrow: View? = null

    private companion object {
        /** Big enough for the arrow and nothing else. */
        const val SIZE = 26
    }
}
