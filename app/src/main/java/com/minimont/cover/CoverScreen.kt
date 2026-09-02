package com.minimont.cover

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.minimont.DesktopController
import com.minimont.DesktopStage
import com.minimont.cover.components.HapticFeedbackManager
import com.minimont.cover.components.MontPill
import com.minimont.cover.components.PageBar
import com.minimont.cover.components.SpecialRow
import com.minimont.cover.data.UserPreferences
import com.minimont.cover.keyboard.MontKeyboard
import com.minimont.cover.keyboard.NavKeyboard
import com.minimont.cover.keyboard.SymbolKeyboard
import com.minimont.cover.model.KeyboardPage
import com.minimont.cover.model.ModifierState
import com.minimont.cover.model.ModifierType
import com.minimont.cover.theme.HalftoneBackground
import com.minimont.cover.theme.LocalMiniDexColors
import com.minimont.cover.theme.MiniDexTheme
import com.minimont.cover.touchpad.EdgeControls
import com.minimont.cover.touchpad.EdgeRefractionSurface
import com.minimont.cover.touchpad.TouchpadView
import com.minimont.ui.mont.MontAccent
import com.minimont.ui.mont.MontChips
import com.minimont.ui.mont.MontDetail
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontRow
import com.minimont.ui.mont.MontWhite

/** A Mont surface holds off the top edge by 44: cases lip over it and the OS reserves pixels there. */
private val MONT_TOP_INSET = 44.dp

/** The three things the cover display is, once the desktop is running. */
private enum class Surface { PAD, KEYS, AIRMATE }

/**
 * The cover display while the desktop is running.
 *
 * This is MiniDex's cover screen, carried across whole rather than rebuilt: the halftone field, the
 * glass that refracts it under the rail and the corner, the scroll rail, the right-click corner, the
 * keyboard and its pages, the special row, the page bar and the pill. All of it was arrived at by
 * using it on this exact screen, and none of it needed to be discovered twice.
 *
 * What changed is where it points. MiniDex aims at a display Samsung made, through an accessibility
 * service and an IME; miniMont aims at the display it made itself, through the shell process that
 * made it — so every gesture below ends in a line on the control stream rather than in a backend.
 */
@Composable
fun CoverScreen(controller: DesktopController, onStop: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { UserPreferences() }
    val haptics = remember { HapticFeedbackManager(context) }

    var surface by remember { mutableStateOf(Surface.PAD) }
    var page by remember { mutableStateOf(KeyboardPage.ABC) }
    var modifiers by remember { mutableStateOf(ModifierState()) }

    fun haptic() = haptics.performHaptic(preferences.hapticStrength)

    fun press(code: Int) {
        controller.key(code, modifiers.toMetaState())
        modifiers = modifiers.consumeLatched()
    }

    /**
     * A character, or a shortcut.
     *
     * Plain typing goes over as text and the far end's character map turns it into the events a
     * physical keyboard would have produced, which is what an application is waiting for. But with
     * ctrl, alt or meta held it is not a letter any more, it is a chord — and a chord has to travel
     * as a key code with its modifiers on it or it arrives as the letter c.
     */
    fun type(character: Char, code: Int) {
        if (modifiers.ctrl.isActive || modifiers.alt.isActive || modifiers.meta.isActive) {
            controller.key(code, modifiers.toMetaState())
        } else {
            controller.type(character.toString())
        }
        modifiers = modifiers.consumeLatched()
    }

    MiniDexTheme(colorway = preferences.colorway, amoledMode = preferences.amoledMode) {
        val colors = LocalMiniDexColors.current

        Box(Modifier.fillMaxSize().background(colors.background)) {
            // The field, and the glass over it. The refraction is what makes the rail and the corner
            // read as objects on the surface rather than as regions drawn over it.
            if (!preferences.amoledMode) {
                EdgeRefractionSurface(
                    railEnabled = surface == Surface.PAD && preferences.edgeScrollEnabled,
                    cornerEnabled = surface == Surface.PAD && preferences.edgeRightClickEnabled,
                    railOnRight = preferences.edgeScrollOnRight,
                    railScale = preferences.edgeRailScale,
                    cornerScale = preferences.edgeCornerScale,
                    modifier = Modifier.fillMaxSize()
                ) {
                    HalftoneBackground(
                        colorway = preferences.colorway,
                        filter = preferences.visualFilter,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, end = 4.dp, top = MONT_TOP_INSET, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Crossfade(targetState = surface, label = "surface") { current ->
                        when (current) {
                            Surface.PAD -> TouchpadView(
                                userPreferences = preferences,
                                onPointerMove = controller::move,
                                onPointerDown = { button -> controller.button(button, true) },
                                onPointerUp = { button -> controller.button(button, false) },
                                onPointerClick = { button ->
                                    controller.button(button, true)
                                    controller.button(button, false)
                                },
                                onScroll = controller::scroll,
                                onHapticClick = { haptic() }
                            )

                            Surface.KEYS -> Crossfade(
                                targetState = page,
                                label = "page",
                                modifier = Modifier.fillMaxSize()
                            ) { current ->
                                Keys(
                                    page = current,
                                    modifiers = modifiers,
                                    preferences = preferences,
                                    onPageSelected = { page = it },
                                    onModifierToggle = { type ->
                                        modifiers = modifiers.toggleModifier(
                                            type,
                                            preferences.doubleTapToLockModifier
                                        )
                                        haptic()
                                    },
                                    onKeyPress = { press(it) },
                                    onCharPress = { character, code -> type(character, code) },
                                    onSwipeWord = { word -> controller.type(word) },
                                    onHaptic = { haptic() }
                                )
                            }

                            Surface.AIRMATE -> AirMatePage(controller, onStop)
                        }
                    }
                }
            }

            // Over everything, and only while there is a pointer surface underneath to control.
            if (surface == Surface.PAD) {
                EdgeControls(
                    railEnabled = preferences.edgeScrollEnabled,
                    rightClickEnabled = preferences.edgeRightClickEnabled,
                    railScale = preferences.edgeRailScale,
                    cornerScale = preferences.edgeCornerScale,
                    railOnRight = preferences.edgeScrollOnRight,
                    markLight = colors.background.luminance() < 0.5f,
                    scrollSensitivity = preferences.scrollSensitivity,
                    naturalScrolling = preferences.naturalScrolling,
                    onScroll = controller::scroll,
                    onRightClick = {
                        controller.button(2, true)
                        controller.button(2, false)
                    },
                    onHaptic = { haptic() }
                )
            }

            // The pill sits at the lower left of the cover display: clear of the camera, and under
            // the thumb. It is the only way in and out of everything else, so it carries all three
            // surfaces rather than two and a secret: a mode you can only reach by holding is a mode
            // that does not exist. Tap goes round; double tap comes straight back to the pointer,
            // which is where you are ninety percent of the time.
            MontPill(
                isAmoled = preferences.amoledMode,
                isConnected = controller.state.collectAsState().value.running,
                onTap = {
                    surface = when (surface) {
                        Surface.PAD -> Surface.KEYS
                        Surface.KEYS -> Surface.AIRMATE
                        Surface.AIRMATE -> Surface.PAD
                    }
                    haptic()
                },
                onDoubleTap = {
                    surface = Surface.PAD
                    haptic()
                },
                onLongPress = {
                    surface = Surface.AIRMATE
                    haptic()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 50.dp, bottom = 30.dp)
            )
        }
    }
}

/**
 * A keyboard page, with the bar and the special row above it.
 *
 * Held off the bottom by 92: the cover display's cutout is the lower right corner, and keys that
 * run into it are keys that cannot be pressed.
 */
@Composable
private fun Keys(
    page: KeyboardPage,
    modifiers: ModifierState,
    preferences: UserPreferences,
    onPageSelected: (KeyboardPage) -> Unit,
    onModifierToggle: (ModifierType) -> Unit,
    onKeyPress: (Int) -> Unit,
    onCharPress: (Char, Int) -> Unit,
    onSwipeWord: (String) -> Unit,
    onHaptic: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().padding(bottom = 92.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PageBar(currentPage = page, isAdbConnected = true, onPageSelected = onPageSelected)
            SpecialRow(
                modifierState = modifiers,
                keyHeight = (preferences.keyHeightLevel.heightDp - 12).dp,
                onModifierToggle = onModifierToggle,
                onKeyPress = onKeyPress
            )
            when (page) {
                KeyboardPage.ABC -> MontKeyboard(
                    modifierState = modifiers,
                    keyHeight = preferences.keyHeightLevel.heightDp / 46f,
                    onCharPress = onCharPress,
                    onSwipeWord = onSwipeWord,
                    onModifierToggle = onModifierToggle,
                    onKeyPress = onKeyPress,
                    onHaptic = onHaptic
                )

                KeyboardPage.SYMBOLS -> SymbolKeyboard(
                    keyHeight = preferences.keyHeightLevel.heightDp.dp,
                    keyGap = preferences.keyGapDp.dp,
                    onCharPress = onCharPress,
                    onKeyPress = onKeyPress
                )

                KeyboardPage.NAV -> NavKeyboard(
                    keyHeight = preferences.keyHeightLevel.heightDp.dp,
                    keyGap = preferences.keyGapDp.dp,
                    onKeyPress = onKeyPress
                )

                // Reached only if the bar ever offers them; miniMont has neither yet.
                else -> Unit
            }
        }
    }
}

/**
 * Everything about the picture, and nothing about the desktop.
 *
 * The resolutions are a fact about the tablet at the other end, they are only meaningful while
 * something is being sent to it, and changing one rebuilds the display — which is not a thing to
 * have one tap away from a pointer surface.
 */
@Composable
private fun AirMatePage(controller: DesktopController, onStop: () -> Unit) {
    val state by controller.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .92f))
            .padding(start = 22.dp, top = 20.dp, end = 14.dp, bottom = 16.dp)
    ) {
        MontLabel("AIRMATE", size = 16, alpha = MontWhite.PRIMARY)
        Spacer(Modifier.height(4.dp))
        MontDetail(
            when {
                state.client != null && state.size != null ->
                    "Sending ${state.size?.first} × ${state.size?.second} to ${state.client}."
                state.running -> "Running. Open AirMate on the tablet and it will find this."
                state.busy -> "Starting…"
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
