package com.minimont.cover.theme

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.minimont.cover.model.VisualFilter

fun VisualFilter.applyTo(color: Color): Color = when (this) {
    VisualFilter.NONE -> color
    VisualFilter.VIVID -> lerp(color, Color.White, 0.12f)
    VisualFilter.MONO -> {
        val luminance = color.red * 0.213f + color.green * 0.715f + color.blue * 0.072f
        Color(luminance, luminance, luminance, color.alpha)
    }
    VisualFilter.WARM -> lerp(color, Color(0xFFFF9A55), 0.28f)
    VisualFilter.COOL -> lerp(color, Color(0xFF6EC8FF), 0.28f)
    VisualFilter.CHROMATIC -> color
    VisualFilter.ACID -> Color(color.green, color.blue, color.red, color.alpha)
    VisualFilter.INVERT -> Color(1f - color.red, 1f - color.green, 1f - color.blue, color.alpha)
    VisualFilter.DREAM -> lerp(color, Color(0xFFFFB6F3), 0.22f)
}

fun VisualFilter.toAndroidColorFilter(): android.graphics.ColorFilter? {
    if (this == VisualFilter.NONE || this == VisualFilter.CHROMATIC) return null
    val matrix = when (this) {
        VisualFilter.NONE -> ColorMatrix()
        VisualFilter.VIVID -> ColorMatrix().apply { setSaturation(1.45f) }
        VisualFilter.MONO -> ColorMatrix().apply { setSaturation(0f) }
        VisualFilter.WARM -> ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 8f,
                0f, 1.02f, 0f, 0f, 2f,
                0f, 0f, 0.84f, 0f, -4f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        VisualFilter.COOL -> ColorMatrix(
            floatArrayOf(
                0.88f, 0f, 0f, 0f, -2f,
                0f, 1.02f, 0f, 0f, 2f,
                0f, 0f, 1.14f, 0f, 8f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        VisualFilter.CHROMATIC -> ColorMatrix()
        VisualFilter.ACID -> ColorMatrix(
            floatArrayOf(
                0.15f, 1.05f, -0.20f, 0f, 8f,
                -0.10f, 0.25f, 1.05f, 0f, 0f,
                1.10f, -0.15f, 0.15f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        VisualFilter.INVERT -> ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        VisualFilter.DREAM -> ColorMatrix(
            floatArrayOf(
                1.08f, 0.08f, 0.08f, 0f, 10f,
                0.02f, 0.92f, 0.06f, 0f, 4f,
                0.10f, 0.08f, 1.08f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
    return ColorMatrixColorFilter(matrix)
}

fun chromaticRedFilter(): android.graphics.ColorFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0.213f, 0.715f, 0.072f, 0f, 0f
        )
    )
)

fun chromaticCyanFilter(): android.graphics.ColorFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0.213f, 0.715f, 0.072f, 0f, 0f
        )
    )
)
