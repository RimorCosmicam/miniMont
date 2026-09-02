package com.minimont.cover

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.minimont.ui.mont.MontWhite
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The cover display, being a trackpad.
 *
 * MiniDex's surface is a living piece of art — a halftone field that bends and refracts under the
 * edge controls, with themes and gradients over it. Mont will not have any of that: this is a black
 * rectangle, and the only thing drawn on it is the one wash that keeps the scroll rail findable.
 * The *behaviour* is MiniDex's, because it was arrived at by using it on this exact screen.
 *
 * Everything here is relative. What is sent is how far the finger went, never where it is, because
 * the cursor lives on the other display and this pad is the same size regardless of what it drives.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Touchpad(
    onMove: (Float, Float) -> Unit,
    onButton: (Int, Boolean) -> Unit,
    onScroll: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = remember { PadState() }
    val density = LocalDensity.current
    val railPixels = with(density) { RAIL.dp.toPx() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // The rail, along the edge the thumb already reaches. 9% white, because an empty black
        // control on a black surface is a control you cannot find until you have already found it.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(RAIL.dp)
                .background(Color.White.copy(MontWhite.TRACK))
        )

        Box(
            Modifier
                .fillMaxSize()
                // The pad has no geometry of its own on the event, and the rail is defined by the
                // edge, so its width has to be known here rather than guessed from raw coordinates.
                .onSizeChanged { state.width = it.width.toFloat() }
                .pointerInteropFilter { event ->
                    handle(event, state, railPixels, onMove, onButton, onScroll)
                    true
                }
        )
    }
}

/** How wide the scroll rail is, along the edge the thumb already reaches. */
private const val RAIL = 26

private const val SENSITIVITY = 1.7f
private const val ACCELERATION = 0.9f
private const val SCROLL_SENSITIVITY = 1.4f
private const val NATURAL_SCROLL = false

/** Under this many milliseconds and pixels, a touch was a tap and not the start of a movement. */
private const val TAP_MILLIS = 220L
private const val TAP_SLOP = 14f
/** A second touch this soon after a tap is a drag, and the button stays down until it lifts. */
private const val DRAG_MILLIS = 260L

private class PadState {
    var width = 0f
    var lastX = 0f
    var lastY = 0f
    var startX = 0f
    var startY = 0f
    var downAt = 0L
    var travelled = 0f
    var fingers = 0
    var scrolling = false
    var onRail = false
    var dragging = false
    var lastTapAt = 0L
}

private fun handle(
    event: MotionEvent,
    state: PadState,
    railPixels: Float,
    onMove: (Float, Float) -> Unit,
    onButton: (Int, Boolean) -> Unit,
    onScroll: (Float, Float) -> Unit
) {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            state.lastX = event.x
            state.lastY = event.y
            state.startX = event.x
            state.startY = event.y
            state.downAt = event.eventTime
            state.travelled = 0f
            state.fingers = 1
            state.scrolling = false
            state.onRail = state.width > 0f && event.x > state.width - railPixels
            // A touch that lands soon after a tap continues it: the button goes down now and stays
            // down until the finger lifts, which is how a drag is expressed on a surface with no
            // button to hold.
            if (event.eventTime - state.lastTapAt < DRAG_MILLIS) {
                state.dragging = true
                onButton(1, true)
            }
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            state.fingers = event.pointerCount
            // Two fingers is a scroll from the moment the second one lands, and whatever the first
            // one was doing is abandoned rather than blended into it.
            if (event.pointerCount >= 2) {
                state.scrolling = true
                state.lastX = event.getX(0)
                state.lastY = event.getY(0)
            }
        }

        MotionEvent.ACTION_MOVE -> {
            val x = event.getX(0)
            val y = event.getY(0)
            val dx = x - state.lastX
            val dy = y - state.lastY
            state.lastX = x
            state.lastY = y
            state.travelled += hypot(dx.toDouble(), dy.toDouble()).toFloat()

            when {
                state.scrolling -> {
                    val (horizontal, vertical) =
                        Kinematics.wheel(dx, dy, SCROLL_SENSITIVITY, NATURAL_SCROLL)
                    if (horizontal != 0f || vertical != 0f) onScroll(horizontal, vertical)
                }

                state.onRail -> {
                    val (_, vertical) = Kinematics.wheel(0f, dy, SCROLL_SENSITIVITY, NATURAL_SCROLL)
                    if (vertical != 0f) onScroll(0f, vertical)
                }

                else -> {
                    val (moveX, moveY) = Kinematics.pointer(dx, dy, SENSITIVITY, ACCELERATION)
                    if (moveX != 0f || moveY != 0f) onMove(moveX, moveY)
                }
            }
        }

        MotionEvent.ACTION_POINTER_UP -> {
            // Two fingers up together, having gone nowhere, is the right button.
            if (event.pointerCount == 2 && state.travelled < TAP_SLOP &&
                event.eventTime - state.downAt < TAP_MILLIS
            ) {
                onButton(2, true)
                onButton(2, false)
                state.scrolling = false
            }
        }

        MotionEvent.ACTION_UP -> {
            if (state.dragging) {
                onButton(1, false)
                state.dragging = false
            } else if (!state.scrolling && !state.onRail &&
                event.eventTime - state.downAt < TAP_MILLIS &&
                abs(event.x - state.startX) < TAP_SLOP && abs(event.y - state.startY) < TAP_SLOP
            ) {
                onButton(1, true)
                onButton(1, false)
                state.lastTapAt = event.eventTime
            }
            state.fingers = 0
            state.scrolling = false
            state.onRail = false
        }

        MotionEvent.ACTION_CANCEL -> {
            if (state.dragging) onButton(1, false)
            state.dragging = false
            state.fingers = 0
            state.scrolling = false
            state.onRail = false
        }
    }
}
