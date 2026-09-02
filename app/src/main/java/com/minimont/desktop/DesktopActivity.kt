package com.minimont.desktop

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.minimont.ui.mont.DiagonalStripes
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopStore.load(this)
        setContent {
            val state by DesktopStore.state.collectAsState()
            Backdrop(state)
        }
    }
}

/**
 * The wallpaper.
 *
 * Black is the default and the only one the language really wants: a desktop is entirely a thing
 * you have to read, and Mont keeps its one piece of ornament away from anything you read. The
 * stripes are offered because somebody may choose them for their own screen, which is a different
 * matter from miniMont putting them there — and they are still, so they do not move behind a
 * window somebody is working in.
 */
@Composable
fun Backdrop(state: DesktopStore.State, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (state.backdrop) {
            DesktopStore.Backdrop.BLACK -> Unit

            DesktopStore.Backdrop.MUSTARD ->
                DiagonalStripes(0f, MontAccent.Mustard, Color.Black, modifier = Modifier.fillMaxSize())

            DesktopStore.Backdrop.LIVE ->
                DiagonalStripes(0f, MontAccent.Live, Color.Black, modifier = Modifier.fillMaxSize())

            DesktopStore.Backdrop.DANGER ->
                DiagonalStripes(0f, MontAccent.Danger, Color.Black, modifier = Modifier.fillMaxSize())

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
