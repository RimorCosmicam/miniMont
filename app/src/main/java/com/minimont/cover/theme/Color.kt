package com.minimont.cover.theme

import androidx.compose.ui.graphics.Color
import com.minimont.cover.model.HalftoneColorway

/**
 * Mont's palette: black, white, and one accent at a time.
 *
 * The surface is #000000 at 92% — not a dark grey and not a blur, black with whatever is behind it
 * faintly present through the last eight percent. One value, used by every panel, bar and key, so
 * they cannot drift apart from each other.
 *
 * White carries all the hierarchy through opacity alone. The 9% wash matters more than it looks:
 * on a black panel, an empty black control is a control you cannot find until you have already
 * found it.
 */
const val MONT_SURFACE_ALPHA = 0.92f

val MontSurface = Color.Black.copy(alpha = MONT_SURFACE_ALPHA)

val WhiteSelected = Color.White                    // selected, active, pressed
val WhitePrimary = Color.White.copy(alpha = 0.92f) // primary text at rest
val WhiteExplain = Color.White.copy(alpha = 0.62f) // explanatory line under a row
val WhiteDim = Color.White.copy(alpha = 0.58f)     // unselected, secondary
val WhiteDisabled = Color.White.copy(alpha = 0.35f)
val WhiteBorder = Color.White.copy(alpha = 0.34f)  // the rare object that needs one
val WhiteTrack = Color.White.copy(alpha = 0.09f)   // unfilled part of a slider or toggle

// Accents carry state, never decoration.
val MontMustard = Color(0xFFD8A628)
val MontLive = Color(0xFF2E9E5B)
val MontDanger = Color(0xFFC0392B)

fun HalftoneColorway.ink(): Color = Color(inkHex)
fun HalftoneColorway.ground(): Color = Color(groundHex)

data class MiniDexColorScheme(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val track: Color,
    val keyBackground: Color,
    val keyPressed: Color,
    val keyLatched: Color,
    val keyLocked: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textExplain: Color,
    val textDisabled: Color,
    val accent: Color,
    val live: Color,
    val danger: Color
)

/**
 * There is only one scheme. The colourway chooses the ink, and the ink is the accent — a second
 * accent would be the decoration the language exists to do without.
 */
fun getMiniDexColorScheme(colorway: HalftoneColorway): MiniDexColorScheme = MiniDexColorScheme(
    background = colorway.ground(),
    surface = MontSurface,
    surfaceElevated = MontSurface,
    border = WhiteBorder,
    track = WhiteTrack,
    keyBackground = MontSurface,
    // A held key is simply lightened, which is the only thing that happens to it.
    keyPressed = Color.White.copy(alpha = 0.30f),
    keyLatched = Color.White.copy(alpha = 0.09f),
    keyLocked = Color.White.copy(alpha = 0.18f),
    textPrimary = WhitePrimary,
    textSecondary = WhiteDim,
    textExplain = WhiteExplain,
    textDisabled = WhiteDisabled,
    accent = colorway.ink(),
    live = MontLive,
    danger = MontDanger
)

/**
 * AMOLED drops the halftone and the last eight percent with it: the ground goes to true black so
 * the display can switch those pixels off entirely.
 */
fun MiniDexColorScheme.asAmoled(): MiniDexColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceElevated = Color.Black,
    keyBackground = Color.Black
)
