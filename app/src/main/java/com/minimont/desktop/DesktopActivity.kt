package com.minimont.desktop

import android.appwidget.AppWidgetHost
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.minimont.R
import com.minimont.DesktopController
import com.minimont.ui.mont.MontAccent

/**
 * The bottom of the desktop.
 *
 * Launched by the host onto miniMont's own display, full screen, and never closed. It is the
 * wallpaper and nothing else: every window opened afterwards sits above it, and the dock and the
 * status card sit above those in a presentation of their own.
 *
 * It exists for a second reason as well. A trusted display with nothing on it is a black rectangle
 * that looks exactly like a display that was never created, and this is the first honest sign that
 * the screen is real and will hold a window.
 */
class DesktopActivity : ComponentActivity() {

    private var host: AppWidgetHost? = null

    override fun onDestroy() {
        runCatching { host?.stopListening() }
        host = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopStore.load(this)

        // Back does nothing here, because there is nowhere behind a desktop.
        //
        // It was finishing this activity, which is the wallpaper and everything standing on it — so
        // pressing back with no window focused took the desktop away and left a black display. The
        // key still reaches whatever app has focus; it just stops meaning "close the floor".
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        // The widget host lives with the backdrop, because the backdrop is what widgets are drawn
        // on and its lifetime is theirs: started when the desktop appears, stopped when it goes.
        host = AppWidgetHost(this, Widgets.HOST_ID)
        runCatching { host?.startListening() }

        setContent {
            val state by DesktopStore.state.collectAsState()
            Box(Modifier.fillMaxSize()) {
                Backdrop(state)
                DesktopItems(state.items, host) { component ->
                    DesktopController.of(this@DesktopActivity).launch(component)
                }
            }
        }
    }
}

/**
 * The wallpaper.
 *
 * A radial wash, mustard in the middle and black at the corners, with the mustard holding most of
 * the radius so the fall-off happens late and gently. It replaced the diagonal stripes here for a
 * reason worth writing down: the stripes were not broken, they were *legible*. A high-contrast
 * pattern shows every dropped fragment as a torn edge, and a desktop background's job is to be the
 * thing you are not looking at. A soft gradient hides the same loss because there are no edges in
 * it to tear.
 *
 * The stripes keep the job Mont actually gives them — a curtain over a moment, not a floor under
 * the work.
 */
@Composable
fun Backdrop(state: DesktopStore.State, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (state.backdrop) {
            // Bundled at 1920x1080, which is the largest display miniMont drives — scaled from the
            // original rather than cropped, since it was already the shape of the screen.
            DesktopStore.Backdrop.MONT -> Image(
                painterResource(R.drawable.wallpaper),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            DesktopStore.Backdrop.BLACK -> Unit

            DesktopStore.Backdrop.MUSTARD -> Glow(MontAccent.Mustard)
            DesktopStore.Backdrop.LIVE -> Glow(MontAccent.Live)
            DesktopStore.Backdrop.DANGER -> Glow(MontAccent.Danger)

            DesktopStore.Backdrop.IMAGE -> {
                val picture = remember(state.image) {
                    state.image?.let { source ->
                        runCatching {
                            context.contentResolver.openInputStream(Uri.parse(source)).use { stream ->
                                BitmapFactory.decodeStream(stream)?.asImageBitmap()
                            }
                        }.getOrNull()
                    }
                }
                picture?.let { image: ImageBitmap ->
                    Image(
                        image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/**
 * One colour, from the middle outwards.
 *
 * The stops are deliberately lopsided. An even black-to-colour ramp reads as a vignette on a dark
 * screen; holding the colour flat across the first half and letting it fall away after reads as a
 * light that is on, which is what a desktop wants behind it.
 */
@Composable
private fun Glow(colour: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                0.00f to colour,
                0.45f to colour,
                0.72f to colour.copy(alpha = .55f),
                1.00f to Color.Black,
                center = center,
                radius = size.maxDimension * .78f
            )
        )
    }
}
