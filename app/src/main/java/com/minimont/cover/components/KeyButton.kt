package com.minimont.cover.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimont.cover.model.ModifierLockState
import com.minimont.cover.theme.LocalMiniDexColors
import com.minimont.cover.theme.Mont

/**
 * A key under Mont: 92% black, square, borderless.
 *
 * The held key is simply lightened by 30% white — no scale, no gradient, no glow. A latched or
 * locked modifier is the bright one rather than the boxed one, so the state lives in the type:
 * white at full strength when latched, the accent when locked, and a bare word above it saying
 * which. Every other keyboard theme uses gradients and rounded borders; this one does not, and
 * that is the point of it.
 */
@Composable
fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    shiftLabel: String? = null,
    lockState: ModifierLockState = ModifierLockState.INACTIVE,
    accentColor: Color? = null,
    customBackgroundColor: Color? = null,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null
) {
    val colors = LocalMiniDexColors.current
    var isPressed by remember { mutableStateOf(false) }

    val isLatched = lockState == ModifierLockState.LATCHED
    val isLocked = lockState == ModifierLockState.LOCKED

    val targetBg = when {
        isLocked -> colors.keyLocked
        isLatched -> colors.keyLatched
        customBackgroundColor != null -> customBackgroundColor
        else -> colors.keyBackground
    }

    val textColor = when {
        isLocked -> colors.accent
        isLatched -> Color.White
        accentColor != null -> accentColor
        else -> colors.textPrimary
    }

    val animatedBackground by animateColorAsState(
        targetValue = if (isPressed) colors.keyPressed else targetBg,
        animationSpec = tween(70),
        label = "key_background"
    )

    Box(
        modifier = modifier
            .background(animatedBackground)
            .pointerInput(onTap, onLongPress, onDoubleTap) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                        }
                    },
                    onTap = { onTap() },
                    onLongPress = onLongPress?.let { lp -> { lp() } },
                    onDoubleTap = onDoubleTap?.let { dt -> { dt() } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLatched || isLocked) {
                Text(
                    text = if (isLocked) "LOCK" else "ON",
                    color = if (isLocked) colors.accent else Color.White,
                    fontFamily = Mont,
                    fontWeight = FontWeight.Black,
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    letterSpacing = 0.5.sp
                )
            } else if (shiftLabel != null) {
                Text(
                    text = shiftLabel,
                    color = colors.textSecondary,
                    fontFamily = Mont,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    lineHeight = 9.sp
                )
            }

            Text(
                text = label,
                color = textColor,
                fontFamily = Mont,
                fontWeight = FontWeight.Black,
                fontSize = if (label.length > 2) 11.sp else 14.sp,
                lineHeight = 15.sp
            )

            if (subLabel != null) {
                Text(
                    text = subLabel,
                    color = colors.textSecondary,
                    fontFamily = Mont,
                    fontWeight = FontWeight.Black,
                    fontSize = 7.sp,
                    lineHeight = 8.sp
                )
            }
        }
    }
}
