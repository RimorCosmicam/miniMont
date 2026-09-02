package com.minimont.cover

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.minimont.DesktopController
import com.minimont.DesktopStage
import com.minimont.ui.mont.DiagonalStripes
import com.minimont.ui.mont.MONT_SURFACE_ALPHA
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontDetail
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow

/**
 * The second phase: somewhere to put the picture.
 *
 * By the time anybody reads this the host has usually been running for a while — it is started the
 * moment the shell connection is there, during the permissions phase, so that the answer to "is
 * there a tablet" is already known rather than asked for. Most of the time this screen is never
 * seen at all.
 *
 * When it is seen, it says the one thing that is actually missing, and it leaves on its own the
 * moment a client says hello. Nobody should have to press *continue* to acknowledge a fact the app
 * can see for itself.
 */
@Composable
fun AirMateSetup(controller: DesktopController) {
    val state by controller.state.collectAsState()
    val transition = rememberInfiniteTransition(label = "airmate")
    val travel by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "stripes"
    )
    val accent = when {
        state.stage == DesktopStage.FAILED -> MontAccent.Danger
        state.running -> MontAccent.Live
        else -> MontAccent.Mustard
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        DiagonalStripes(travel, accent, Color.Black, modifier = Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 18.dp)
                .background(Color.Black.copy(MONT_SURFACE_ALPHA))
                .padding(start = 22.dp, top = 22.dp, end = 18.dp, bottom = 16.dp)
        ) {
            MontLabel("AIRMATE", size = 20, alpha = 1f)
            Spacer(Modifier.height(6.dp))
            MontDetail(
                when {
                    state.running ->
                        "The desktop is running. Open AirMate on the tablet and it will find it."
                    state.busy -> "Starting the desktop…"
                    state.stage == DesktopStage.FAILED -> state.message
                    else -> "The desktop has to be running before the tablet can find it."
                }
            )
            Spacer(Modifier.height(16.dp))

            if (state.running) {
                MontRow(label = "Waiting for the tablet", enabled = false) { }
                MontRow(label = "Stop the desktop", active = false) { controller.stop() }
            } else {
                MontRow(label = "Start the desktop", enabled = !state.busy) { controller.start() }
            }
        }
    }
}
