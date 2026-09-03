package com.minimont.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A second-button press, and the long press that stands in for it.
 *
 * The desktop has a real mouse, so everything on it answers a right click the way a desktop does.
 * The whole gesture is swallowed, not just the press that started it: consuming only the press left
 * the release for the ordinary click handler, which then did what a left click does — so a menu
 * opened on the press and was closed again by its own release, one frame later. The Initial pass is
 * where to take it, before anything else has looked.
 *
 * @param onClick given the position of the press, for menus that open where the pointer is.
 */
fun Modifier.secondary(
    /**
     * Which pass to take the press on, and it decides who wins.
     *
     * Initial is delivered parent first. A desktop and a widget standing on it both watching for a
     * right click therefore both saw the same press, and both opened a menu — one behind the other.
     * Whoever is *underneath* has to listen later: the widget takes it on Initial and consumes it,
     * the desktop takes it on Final and finds it already spoken for.
     */
    pass: PointerEventPass = PointerEventPass.Initial,
    onClick: (Offset) -> Unit
): Modifier = this.pointerInput(onClick, pass) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(pass)
            if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
            // Somebody nearer the finger has already answered this one.
            if (event.changes.any { it.isConsumed }) continue

            val at = event.changes.firstOrNull()?.position ?: Offset.Zero
            event.changes.forEach { it.consume() }
            onClick(at)

            var pressed = true
            while (pressed) {
                val rest = awaitPointerEvent(pass)
                rest.changes.forEach { it.consume() }
                pressed = rest.changes.any { it.pressed }
            }
        }
    }
}

/**
 * What the backdrop asks the chrome to open.
 *
 * The desktop's own menu lives in the backdrop window, at the bottom of the stack, and the cards it
 * opens live in the chrome window above every application. They are two windows in one process, so
 * the backdrop does not draw the card — it says which one it wants and the chrome opens it where
 * cards belong.
 */
object DesktopRequests {
    enum class Panel { WIDGETS, WALLPAPER, SETTINGS }

    private val _asked = MutableStateFlow<Panel?>(null)
    val asked = _asked.asStateFlow()

    fun ask(panel: Panel) {
        _asked.value = panel
    }

    /** Cleared by whoever answered it, so the same request is not answered twice. */
    fun answered() {
        _asked.value = null
    }
}
