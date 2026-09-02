package com.minimont.cover.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.minimont.cover.model.HalftoneColorway

val LocalMiniDexColors = staticCompositionLocalOf {
    getMiniDexColorScheme(HalftoneColorway.MUSTARD)
}

@Composable
fun MiniDexTheme(
    colorway: HalftoneColorway = HalftoneColorway.MUSTARD,
    amoledMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val customColors = getMiniDexColorScheme(colorway).let {
        if (amoledMode) it.asAmoled() else it
    }

    val materialColors = darkColorScheme(
        primary = customColors.accent,
        background = customColors.background,
        surface = customColors.surface,
        onPrimary = customColors.background,
        onBackground = customColors.textPrimary,
        onSurface = customColors.textPrimary
    )

    CompositionLocalProvider(LocalMiniDexColors provides customColors) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = Typography,
            content = content
        )
    }
}
