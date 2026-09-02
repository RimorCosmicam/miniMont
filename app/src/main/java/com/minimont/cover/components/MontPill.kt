package com.minimont.cover.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimont.cover.theme.MONT_SURFACE_ALPHA
import com.minimont.cover.theme.Mont
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The pill's own accents, carried over from miniMate.
/** miniMate's clockScale default. The pill is drawn at this size, not at Compose's natural one. */
const val PILL_SCALE = 1.18f

private val AccentPink = Color(0xFFFF69B4)
private val AccentEmerald = Color(0xFF10B981)
private val AccentCyan = Color(0xFF4CC9F0)
private val LowBattery = Color(0xFFEF4444)

/**
 * miniMate's clock pill.
 *
 * Square, black, Mont Black, no border and no accent colours — the same surface as every panel, so
 * the pill stops being the one ornamented thing left on the screen. It sits at the lower left of
 * the cover display: clear of the camera, and under the thumb.
 *
 * It is also the only way in and out of everything else: one tap changes mode, two go AMOLED, a
 * hold opens settings.
 */
@Composable
fun MontPill(
    isAmoled: Boolean,
    isConnected: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pressed by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("") }
    var amPm by remember { mutableStateOf("") }
    var battery by remember { mutableIntStateOf(100) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            time = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
            amPm = SimpleDateFormat("a", Locale.getDefault()).format(now).uppercase()
            battery = readBatteryPercentage(context)
            delay(1000L)
        }
    }

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1.0f,
        label = "pillPress"
    )

    Row(
        modifier = modifier
            .scale(PILL_SCALE * pressScale)
            .background(Color.Black.copy(alpha = MONT_SURFACE_ALPHA))
            // In AMOLED the pill is black on black and would have no edge at all, so it gets the
            // thinnest outline that still reads. Square, like the pill.
            .then(
                if (isAmoled) {
                    Modifier.border(0.5.dp, Color.White.copy(alpha = if (pressed) 0.7f else 0.34f))
                } else {
                    Modifier
                }
            )
            .pointerInput(onTap, onDoubleTap, onLongPress) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time,
            color = Color.White.copy(alpha = if (pressed) 1f else 0.92f),
            fontFamily = Mont,
            fontWeight = FontWeight.Black,
            fontSize = 13.sp
        )
        if (amPm.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = amPm,
                // Colour everywhere except AMOLED, where the point is that nothing lights a pixel
                // it does not have to.
                color = if (isAmoled) Color.White.copy(alpha = 0.55f) else AccentPink,
                fontFamily = Mont,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = "$battery%",
            color = when {
                isAmoled -> Color.White.copy(alpha = if (battery < 20) 0.95f else 0.55f)
                battery < 20 -> LowBattery
                else -> AccentEmerald
            },
            fontFamily = Mont,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        if (isConnected) {
            Spacer(Modifier.width(8.dp))
            // A square, not a dot. Nothing here is round.
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (isAmoled) Color.White else AccentCyan)
            )
        }
    }
}

private fun readBatteryPercentage(context: Context): Int {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return 100
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else 100
}
