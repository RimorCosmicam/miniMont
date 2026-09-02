package com.minimont.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.minimont.DesktopController
import com.minimont.DesktopStage
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontChips
import com.minimont.ui.mont.MontDetail
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontWhite

/** The three things the cover screen is, once the desktop is up. */
private enum class Surface(val label: String) {
    PAD("Pad"),
    KEYS("Keys"),
    AIRMATE("AirMate")
}

/**
 * The cover display while the desktop is running.
 *
 * This is what miniMont is for. The desktop is on the other screen and nobody is looking at this
 * one — they are touching it — so it holds a pointer surface, a keyboard, and the one page that has
 * anything to say about the picture going out to the tablet. Nothing else, and no wordmark: the
 * app has already introduced itself by the time anybody gets here.
 *
 * The switcher is three words. Selected is the bright one, which is the same rule a row and a chip
 * already follow, so there is no bar, no tab and no indicator underneath them.
 */
@Composable
fun CoverScreen(controller: DesktopController, onStop: () -> Unit) {
    var surface by remember { mutableStateOf(Surface.PAD) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (surface) {
                Surface.PAD -> Touchpad(
                    onMove = controller::move,
                    onButton = controller::button,
                    onScroll = controller::scroll
                )

                Surface.KEYS -> Keyboard(
                    onType = controller::type,
                    onKey = { code -> controller.key(code) }
                )

                Surface.AIRMATE -> AirMatePage(controller, onStop)
            }
        }

        // At the bottom, under the thumb, and out of the way of everything above it.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .92f))
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface.entries.forEach { candidate ->
                MontLabel(
                    candidate.label.uppercase(),
                    Modifier.clickable { surface = candidate },
                    alpha = if (candidate == surface) MontWhite.ACTIVE else MontWhite.DIM,
                    size = 13
                )
            }
        }
    }
}

/**
 * Everything about the picture, and nothing about the desktop.
 *
 * The resolutions were on the first screen when the first screen was the only screen. They belong
 * here: they are a fact about the tablet at the other end, they are only meaningful while something
 * is being sent to it, and changing one rebuilds the display — which is not a thing to have one tap
 * away from a pointer surface.
 */
@Composable
private fun AirMatePage(controller: DesktopController, onStop: () -> Unit) {
    val state by controller.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .92f))
            .padding(start = 22.dp, top = 20.dp, end = 14.dp, bottom = 16.dp)
    ) {
        MontLabel("AIRMATE", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(4.dp))
        MontDetail(
            when {
                state.running && state.size != null ->
                    "Sending ${state.size?.first} × ${state.size?.second}."
                state.running -> "Running."
                else -> state.message.ifBlank { "Not sending." }
            }
        )

        Spacer(Modifier.height(16.dp))
        MontLabel("RESOLUTION", size = 11, alpha = MontWhite.DETAIL)
        Spacer(Modifier.height(6.dp))
        val choices = state.choices
        MontChips(
            options = choices.map { "${it.first} × ${it.second}" },
            selected = choices.indexOf(state.size)
        ) { index ->
            val (width, height) = choices[index]
            controller.setResolution(width, height)
        }
        // Said once, here, rather than discovered by watching the picture go away and come back.
        Spacer(Modifier.height(6.dp))
        MontDetail("Changing this rebuilds the display.")

        Spacer(Modifier.height(20.dp))
        MontRow(label = "Stop the desktop") { onStop() }

        if (state.stage == DesktopStage.FAILED) {
            Spacer(Modifier.height(10.dp))
            MontLabel(state.message, size = 11, colour = MontAccent.Danger, alpha = 1f)
        }
    }
}
