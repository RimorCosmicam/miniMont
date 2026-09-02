package com.minimont.cover.theme

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.minimont.cover.model.HalftoneColorway
import com.minimont.cover.model.VisualFilter
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Minimal / Halftone, carried over from miniMate.
 *
 * A halftone is a picture drawn in dot *area*, so the wave has to be in the area: one slow swell
 * crosses the field and the dots grow and shrink with it. Nothing translates, nothing fades — a
 * flat pattern that eases between states stops being flat, because easing is depth by another
 * name.
 *
 * The dots are the only ornament the interface has. Everything above them is black, white and Mont.
 */
private const val PITCH = 22f
private const val SPEED = 0.5f
private const val CONTRAST = 0.8f
private const val ANGLE = 0.4f

private const val HALFTONE_SHADER = """
uniform float2 uResolution;
uniform float uTime;
uniform float uPitch;
uniform float uSpeed;
uniform float uContrast;
uniform float uAngle;
layout(color) uniform half4 uGround;
layout(color) uniform half4 uInk;

float2 rot(float2 p, float a){
    float c = cos(a), s = sin(a);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}

half4 main(float2 fragCoord){
    float2 res = uResolution;
    float2 p = (fragCoord - 0.5 * res) / min(res.x, res.y);
    float2 q = rot(p, uAngle);
    float2 f = fract(q * uPitch) - 0.5;

    float wave = sin(q.x * 3.0 + uTime * uSpeed) * 0.5 +
                 sin(q.y * 2.2 - uTime * uSpeed * 0.7) * 0.5;
    float radius = (0.16 + 0.30 * (wave * 0.5 + 0.5)) * uContrast;
    float dot = smoothstep(radius, radius - 0.06, length(f));
    return mix(uGround, uInk, half(dot));
}
"""

@Composable
fun HalftoneBackground(
    colorway: HalftoneColorway,
    filter: VisualFilter,
    modifier: Modifier = Modifier
) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                time = (now - start) / 1_000_000_000f
            }
        }
    }

    val ground = filter.applyTo(colorway.ground())
    val ink = filter.applyTo(colorway.ink())

    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(HALFTONE_SHADER) }.getOrNull()
        } else {
            null
        }
    }
    val paint = remember { Paint() }

    Canvas(modifier) {
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawShaderHalftone(shader, paint, time, ground.toArgb(), ink.toArgb())
        } else {
            drawCanvasHalftone(time, ground, ink)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawShaderHalftone(
    shader: RuntimeShader,
    paint: Paint,
    time: Float,
    ground: Int,
    ink: Int
) {
    shader.setFloatUniform("uResolution", size.width, size.height)
    shader.setFloatUniform("uTime", time)
    shader.setFloatUniform("uPitch", PITCH)
    shader.setFloatUniform("uSpeed", SPEED)
    shader.setFloatUniform("uContrast", CONTRAST)
    shader.setFloatUniform("uAngle", ANGLE)
    shader.setColorUniform("uGround", ground)
    shader.setColorUniform("uInk", ink)
    paint.shader = shader
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}

/**
 * The same field without AGSL, for anything below API 33. Each cell is walked in the rotated
 * frame — where the grid is plain rows and columns — and its centre rotated back to the screen,
 * which is the whole reason the shader rotates the point rather than the pattern.
 */
private fun DrawScope.drawCanvasHalftone(
    time: Float,
    ground: androidx.compose.ui.graphics.Color,
    ink: androidx.compose.ui.graphics.Color
) {
    drawRect(ground)

    val unit = minOf(size.width, size.height)
    if (unit <= 0f) return
    val centre = Offset(size.width / 2f, size.height / 2f)
    // The rotated frame has to cover the screen's corners, so step out to its half-diagonal.
    val reach = hypot(size.width, size.height) / (2f * unit)
    val cells = floor(reach * PITCH).toInt() + 1
    val cosA = cos(-ANGLE)
    val sinA = sin(-ANGLE)

    for (row in -cells..cells) {
        for (column in -cells..cells) {
            val qx = (column + 0.5f) / PITCH
            val qy = (row + 0.5f) / PITCH
            val wave = sin(qx * 3f + time * SPEED) * 0.5f +
                sin(qy * 2.2f - time * SPEED * 0.7f) * 0.5f
            val radius = (0.16f + 0.30f * (wave * 0.5f + 0.5f)) * CONTRAST
            if (radius <= 0f) continue
            val px = cosA * qx - sinA * qy
            val py = sinA * qx + cosA * qy
            drawCircle(
                color = ink,
                radius = radius / PITCH * unit,
                center = Offset(centre.x + px * unit, centre.y + py * unit)
            )
        }
    }
}
