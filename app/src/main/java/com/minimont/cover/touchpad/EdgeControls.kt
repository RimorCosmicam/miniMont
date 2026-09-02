package com.minimont.cover.touchpad

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp

/**
 * miniMate's edge controls.
 *
 * Low-profile single-hand controls that reserve only two narrow hit regions. The rail and the
 * corner mirror as a pair, so the click target is always opposite the scrolling hand.
 *
 * The rail's hit region is wider than the thing it draws: 52dp of screen answers to a 28dp mark,
 * because a strip you have to look at to hit is a strip you will miss.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EdgeControls(
    railEnabled: Boolean,
    rightClickEnabled: Boolean,
    railScale: Float,
    cornerScale: Float,
    railOnRight: Boolean,
    /**
     * The colour the marks are drawn in. miniMate only ever has dark scenes behind these, so it
     * draws them in white at two to four percent; over a light colourway like Paper or Signal that
     * is white on white, which is why they vanished. The alphas are miniMate's — only the hue
     * follows the ground.
     */
    markLight: Boolean,
    scrollSensitivity: Float,
    naturalScrolling: Boolean,
    onScroll: (Float, Float) -> Unit,
    onRightClick: () -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rail = railScale.coerceIn(0.65f, 1.8f)
    val corner = cornerScale.coerceIn(0.65f, 1.8f)
    val mark = if (markLight) Color.White else Color.Black
    val sheen = if (markLight) Color(0xFFCBF7FF) else Color(0xFF0B3A45)

    Box(modifier.fillMaxSize()) {
        if (railEnabled) {
        var active by remember { mutableStateOf(false) }
        var lastY by remember { mutableFloatStateOf(0f) }

        fun consumeY(nextY: Float) {
            val rawDy = nextY - lastY
            lastY = nextY
            val (dx, dy) = TouchpadKinematics.calculateScrollDelta(
                rawDx = 0f,
                rawDy = rawDy,
                scrollSensitivity = scrollSensitivity,
                naturalScrolling = naturalScrolling
            )
            onScroll(dx, dy)
        }

        Box(
            Modifier
                .align(if (railOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .width(52.dp * rail)
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            active = true
                            lastY = event.y
                            onHaptic()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            for (index in 0 until event.historySize) {
                                consumeY(event.getHistoricalY(0, index))
                            }
                            consumeY(event.y)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            active = false
                            true
                        }
                        else -> true
                    }
                }
        ) {
            Canvas(
                Modifier
                    .align(if (railOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                    // 28dp, matching the lens width the refraction shader is given.
                    .width(28.dp * rail)
                    .fillMaxHeight()
            ) {
                val boost = if (active) 1.75f else 1f
                val leftToRight = listOf(
                    mark.copy(alpha = 0.018f * boost),
                    mark.copy(alpha = 0.038f * boost),
                    sheen.copy(alpha = 0.026f * boost),
                    Color.Transparent,
                    mark.copy(alpha = 0.014f * boost)
                )
                val horizontal = if (railOnRight) leftToRight.reversed() else leftToRight
                drawRect(brush = Brush.horizontalGradient(horizontal), size = size)
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            mark.copy(alpha = if (active) 0.10f else 0.045f),
                            Color.Transparent,
                            Color.Transparent,
                            mark.copy(alpha = if (active) 0.08f else 0.035f)
                        )
                    ),
                    size = size
                )
                val innerX = if (railOnRight) 0f else size.width
                val offset = if (railOnRight) 1.4.dp.toPx() else -1.4.dp.toPx()
                drawLine(
                    color = mark.copy(alpha = if (active) 0.24f else 0.12f),
                    start = Offset(innerX, 0f),
                    end = Offset(innerX, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = mark.copy(alpha = if (active) 0.20f else 0.09f),
                    start = Offset(innerX + offset, 0f),
                    end = Offset(innerX + offset, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = sheen.copy(alpha = if (active) 0.16f else 0.065f),
                    start = Offset(innerX - offset, 0f),
                    end = Offset(innerX - offset, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        }

        // The corner sits opposite the rail, so whichever hand is scrolling is not the hand
        // reaching across the pad to right-click.
        if (rightClickEnabled) {
        val clickOnLeft = railOnRight
        var pressed by remember { mutableStateOf(false) }
        Box(
            Modifier
                .align(if (clickOnLeft) Alignment.TopStart else Alignment.TopEnd)
                .width(104.dp * corner)
                .height(104.dp * corner)
                .pointerInput(onRightClick) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            val released = tryAwaitRelease()
                            pressed = false
                            if (released) {
                                onHaptic()
                                onRightClick()
                            }
                        }
                    )
                }
        ) {
            Canvas(
                Modifier
                    .align(if (clickOnLeft) Alignment.TopStart else Alignment.TopEnd)
                    .fillMaxSize()
            ) {
                val center = if (clickOnLeft) Offset.Zero else Offset(size.width, 0f)
                // 94dp, matching the lens radius the refraction shader is given.
                val radius = 94.dp.toPx() * corner
                val boost = if (pressed) 1.8f else 1f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            mark.copy(alpha = 0.045f * boost),
                            mark.copy(alpha = 0.022f * boost),
                            sheen.copy(alpha = 0.012f * boost),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = mark.copy(alpha = if (pressed) 0.28f else 0.14f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                val shift = if (clickOnLeft) 1.4.dp.toPx() else -1.4.dp.toPx()
                drawCircle(
                    color = mark.copy(alpha = if (pressed) 0.20f else 0.09f),
                    radius = radius - 0.4.dp.toPx(),
                    center = center + Offset(shift, 0f),
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = sheen.copy(alpha = if (pressed) 0.16f else 0.065f),
                    radius = radius - 0.4.dp.toPx(),
                    center = center + Offset(-shift, 0f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
        }
    }
}
