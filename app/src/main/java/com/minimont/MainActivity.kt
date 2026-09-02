package com.minimont

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.minimont.cover.AirMateSetup
import com.minimont.cover.CoverScreen
import com.minimont.ui.PairingOverlay
import com.minimont.ui.PairingStrip
import com.minimont.ui.Requirement
import com.minimont.ui.Welcome
import com.minimont.ui.mont.DiagonalStripes
import com.minimont.ui.mont.MONT_SURFACE_ALPHA
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontDetail
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontWhite
import com.minimont.ui.mont.MontWordmark

/**
 * The host's own window.
 *
 * Two screens and nothing else: the things that have to be granted before any of this can work, and
 * then a single row that starts and stops the desktop. Everything the host does after that happens
 * on the tablet, which is where the user is looking.
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: DesktopController

    /** What went wrong the last time the pairing strip was asked for, if anything. */
    private var overlayFailure by mutableStateOf("")

    private var code by mutableStateOf("")
    private var port by mutableStateOf("")


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        controller = DesktopController.of(this)
        controller.phoneDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        // Started with the app rather than with the desktop. It is what puts the dock on the
        // display, and the display can already be there — the host is a separate process that
        // outlives this window and may have been running before it opened.
        MontService.start(this)
        setContent { Root(controller) }
    }

    /**
     * Take the whole screen, and keep it.
     *
     * The cover display is three inches across and the navigation bar eats a strip of it that
     * happens to be exactly where the keyboard's bottom row and the touchpad's click corner are.
     * There is nothing on this screen the system bars are needed for — the pill is the only way in
     * and out of anything.
     *
     * Hidden with the transient behaviour so a swipe from the edge still brings them back for as
     * long as they are wanted, and re-hidden on focus because Android puts them back whenever
     * anything else has been in front.
     */
    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() {
        PairingOverlay.hide()
        // The controller is deliberately not released. The desktop should survive this window being
        // closed, the same way it survives the phone going into a pocket; it ends when asked to.
        super.onDestroy()
    }

    @Composable
    private fun Root(controller: DesktopController) {
        var accessibility by remember { mutableStateOf(MontAccessibilityService.granted(this)) }
        var paired by remember { mutableStateOf(controller.paired) }
        var welcomed by remember { mutableStateOf(welcomeSeen) }

        // Both of these are switched on in Settings, which means the user is always in another app
        // when they change. A value sampled once at composition is a value that never changes.
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accessibility = MontAccessibilityService.granted(this@MainActivity)
                    paired = controller.paired
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        val state by controller.state.collectAsState()

        // The three phases, in the order the onboarding actually has: what miniMont needs, then
        // somewhere to put the picture, then the thing itself. The second phase is usually skipped
        // — the host is started as soon as the shell connection exists, during the first phase, so
        // by the time the permissions are done the tablet has normally already said hello.
        LaunchedEffect(paired, accessibility) {
            if (paired && accessibility && !state.running && !state.busy) controller.start()
        }

        if (!welcomed || !accessibility || !paired) {
            Welcome(
                requirements = listOf(
                    Requirement(
                        label = "Accessibility",
                        detail = "The pairing code lives in Android's own settings screen. " +
                            "miniMont floats its code field over that screen, and only an " +
                            "accessibility service may open a window there.",
                        granted = accessibility,
                        action = "Allow"
                    ),
                    Requirement(
                        label = "Wireless debugging",
                        detail = "Only the shell user may create a display that will hold a window. " +
                            "Pairing once is how miniMont becomes it, on this phone alone.",
                        granted = paired,
                        action = "Pair"
                    )
                ),
                status = overlayFailure.ifBlank { state.message },
                onGrant = { index ->
                    when (index) {
                        0 -> openAccessibilitySettings()
                        else -> openPairing()
                    }
                },
                onFinished = {
                    welcomeSeen = true
                    welcomed = true
                }
            )
        } else if (state.client == null) {
            AirMateSetup(controller)
        } else if (state.running) {
            // Once the desktop is up, this screen stops being about the desktop and becomes the
            // thing you drive it with. Nobody looks at the cover display from here on; they touch
            // it while looking at the other one.
            // Only the desktop stops. The service stays: it is what draws the taskbar, it costs
            // nothing while there is no display to draw on, and stopping it here is what made the
            // taskbar never come back after a restart.
            CoverScreen(controller) { controller.stop() }
        } else {
            DesktopCard(controller)
        }
    }

    /**
     * Before there is a desktop: one card over stripes, and one row on it.
     *
     * Nothing else. The display id, the frame counters and the host's own log are all things the
     * machine knows and nobody asked to see, and the resolutions have gone where they belong —
     * onto the AirMate page, which only means anything once something is being sent.
     *
     * The stripes behind it are not decoration and are not here because the screen looked empty.
     * They are the one place in Mont where colour carries a state across a whole surface, and this
     * screen has exactly three states worth telling apart at arm's length: mustard while there is
     * no desktop yet, green while one is running, red when something refused.
     */
    @Composable
    private fun DesktopCard(controller: DesktopController) {
        val state by controller.state.collectAsState()
        val transition = rememberInfiniteTransition(label = "desktop")
        val travel by transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "stripes"
        )
        val accent = when (state.stage) {
            DesktopStage.RUNNING -> MontAccent.Live
            DesktopStage.FAILED -> MontAccent.Danger
            else -> MontAccent.Mustard
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            DiagonalStripes(
                travel = travel,
                first = accent,
                second = Color.Black,
                modifier = Modifier.fillMaxSize()
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 18.dp)
                    .background(Color.Black.copy(MONT_SURFACE_ALPHA))
                    .padding(start = 22.dp, top = 22.dp, end = 18.dp, bottom = 16.dp)
            ) {
                MontWordmark()
                Spacer(Modifier.height(18.dp))
                MontRow(label = "Start the desktop", enabled = !state.busy) { controller.start() }

                // One line, and only when it is carrying something the user has to act on.
                if (state.stage == DesktopStage.FAILED) {
                    Spacer(Modifier.height(10.dp))
                    MontDetail(state.message)
                }
            }
        }
    }

    /**
     * Send the user to wireless debugging with the code field going along.
     *
     * The strip is floated *before* the intent, so it is already there when Settings arrives rather
     * than appearing over it a moment later. It is floated on this window's own display, because a
     * folded Flip is showing its cover screen and the default display is the one that is switched
     * off.
     */
    private fun openPairing() {
        overlayFailure = ""
        code = ""
        port = ""
        val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val failure = PairingOverlay.show(displayId) {
            val live by controller.state.collectAsState()
            PairingStrip(
                code = code,
                port = port,
                discoveredPort = live.pairingPort,
                status = when {
                    live.stage == DesktopStage.PAIRING -> "Pairing…"
                    live.stage == DesktopStage.FAILED -> live.message
                    live.pairingPort != null -> "Found the pairing port. Type the six digits."
                    else -> "Tap “Pair device with pairing code”."
                },
                busy = live.busy,
                onCode = { entered -> code = entered.filter(Char::isDigit).take(6) },
                onPort = { entered -> port = entered.filter(Char::isDigit).take(5) },
                onPair = {
                    val chosen = port.toIntOrNull() ?: live.pairingPort ?: return@PairingStrip
                    controller.pair(chosen, code) { runOnUiThread { returnFromPairing(displayId) } }
                },
                onClose = { PairingOverlay.hide() }
            )
        }
        if (failure != null) {
            // Said out loud rather than silently redirected. Sending the user to a settings screen
            // they did not ask for, to fix something they were not told about, is how this looked
            // like the pair button simply opening the wrong page.
            overlayFailure = "The pairing field could not open: $failure"
            return
        }
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    /**
     * Put the app back in front, on the screen the user was using.
     *
     * `REORDER_TO_FRONT` rather than a fresh launch: this activity is still alive behind Settings,
     * and starting another copy of it would throw away everything on screen — including the state
     * that has just changed and is the reason for coming back at all.
     */
    private fun returnFromPairing(displayId: Int) {
        PairingOverlay.hide()
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                ),
                options.toBundle()
            )
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private var welcomeSeen: Boolean
        get() = preferences.getBoolean(WELCOME, false)
        set(value) { preferences.edit().putBoolean(WELCOME, value).apply() }

    private val preferences
        get() = getSharedPreferences("minimont", Context.MODE_PRIVATE)

    private companion object {
        const val WELCOME = "welcome_seen"
    }
}
