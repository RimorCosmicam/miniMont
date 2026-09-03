package com.minimont.ui.mont

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * Interleaved diagonal bands, scrolling.
 *
 * Everything is drawn in a frame turned to the bands' own slope, so within it they are plain
 * horizontal rows and the whole thing is easy to reason about. [split] cuts the sheet down the
 * middle and pulls the two halves apart along the bands' own axis; each half is exactly as wide as
 * it travels, so pulling it clears the side it was covering.
 *
 * The same geometry the AirMate client and MiniMate use — 34dp apart at 26.565 degrees — so the
 * three of them cannot drift into being three different patterns.
 *
 * [travel] is left at zero everywhere in miniMont. Mont has them scrolling slowly, and on a cover
 * display three inches across that is several hundred rectangles redrawn every frame behind a
 * wordmark — which stuttered, and a stuttering ornament is worse than a still one. They still carry
 * their state in colour, which is the part that was ever doing any work.
 */
@Composable
fun DiagonalStripes(
    travel: Float,
    first: Color,
    second: Color,
    split: Float = 0f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val spacing = 34.dp.toPx()
        val band = spacing * 0.5f
        val drift = travel * spacing
        val middle = size.width / 2f
        val span = size.width + size.height
        val pull = split * span

        fun half(from: Float, shift: Float) {
            translate(left = shift) {
                var y = -span
                while (y < size.height + span) {
                    drawRect(first, Offset(from, y + drift), Size(span, band))
                    drawRect(second, Offset(from, y + drift + band), Size(span, band))
                    y += spacing
                }
            }
        }

        rotate(degrees = 26.565f) {
            // One sheet while it is one sheet. Cutting it down the middle and drawing two clipped
            // halves that happen to meet leaves a hairline where they touch — a seam down the
            // centre of the screen that is not a design, it is an artefact of a split of zero.
            if (pull == 0f) {
                half(middle - span, 0f)
                half(middle, 0f)
                return@rotate
            }
            clipRect(-span, -span, middle, size.height + span) { half(middle - span, -pull) }
            clipRect(middle, -span, size.width + span, size.height + span) { half(middle, pull) }
        }
    }
}
