package com.minimont.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.minimont.ui.mont.MontLabel
import com.minimont.ui.mont.MontWhite

/** Android's own key codes, for the keys that are not a character. */
private object Code {
    const val DELETE = 67
    const val ENTER = 66
    const val TAB = 61
    const val ESCAPE = 111
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val HOME = 122
    const val END = 123
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
}

private enum class Page { LETTERS, SYMBOLS, NAVIGATION }

/**
 * The cover display, being a keyboard.
 *
 * Keys are 92% black, square, borderless, and the held key is simply lightened by 30% white. Every
 * other keyboard anyone ships has gradients and rounded borders; this one does not, and that is the
 * point of it.
 *
 * Characters are typed rather than coded: a letter goes over as text and the far end's character
 * map turns it into the key events a physical keyboard would have produced. Only the keys that are
 * not characters — return, delete, the arrows — are sent as key codes.
 */
@Composable
fun Keyboard(
    onType: (String) -> Unit,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var page by remember { mutableStateOf(Page.LETTERS) }
    var shifted by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (page) {
            Page.LETTERS -> {
                KeyRow("qwertyuiop", shifted, onType)
                KeyRow("asdfghjkl", shifted, onType)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The word names what the control currently is, not what pressing it would do.
                    Key(if (shifted) "ABC" else "abc", weight = 1.6f) { shifted = !shifted }
                    "zxcvbnm".forEach { character ->
                        Key(character.shift(shifted)) { onType(character.shift(shifted)) }
                    }
                    Key("DEL", weight = 1.6f) { onKey(Code.DELETE) }
                }
                BottomRow(
                    onPage = { page = Page.SYMBOLS },
                    pageLabel = "?123",
                    onType = onType,
                    onKey = onKey,
                    onNavigation = { page = Page.NAVIGATION }
                )
            }

            Page.SYMBOLS -> {
                KeyRow("1234567890", false, onType)
                KeyRow("-/:;()$&@\"", false, onType)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Key("#+=", weight = 1.6f) { }
                    ".,?!'%*".forEach { character ->
                        Key(character.toString()) { onType(character.toString()) }
                    }
                    Key("DEL", weight = 1.6f) { onKey(Code.DELETE) }
                }
                BottomRow(
                    onPage = { page = Page.LETTERS },
                    pageLabel = "abc",
                    onType = onType,
                    onKey = onKey,
                    onNavigation = { page = Page.NAVIGATION }
                )
            }

            Page.NAVIGATION -> {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Key("ESC") { onKey(Code.ESCAPE) }
                    Key("TAB") { onKey(Code.TAB) }
                    Key("HOME") { onKey(Code.HOME) }
                    Key("END") { onKey(Code.END) }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Key("PG UP") { onKey(Code.PAGE_UP) }
                    Key("PG DN") { onKey(Code.PAGE_DOWN) }
                    Key("DEL") { onKey(Code.DELETE) }
                    Key("RETURN") { onKey(Code.ENTER) }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Key("←") { onKey(Code.LEFT) }
                    Key("↓") { onKey(Code.DOWN) }
                    Key("↑") { onKey(Code.UP) }
                    Key("→") { onKey(Code.RIGHT) }
                }
                BottomRow(
                    onPage = { page = Page.LETTERS },
                    pageLabel = "abc",
                    onType = onType,
                    onKey = onKey,
                    onNavigation = { page = Page.NAVIGATION }
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.KeyRow(
    characters: String,
    shifted: Boolean,
    onType: (String) -> Unit
) {
    Row(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        characters.forEach { character ->
            Key(character.shift(shifted)) { onType(character.shift(shifted)) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BottomRow(
    onPage: () -> Unit,
    pageLabel: String,
    onType: (String) -> Unit,
    onKey: (Int) -> Unit,
    onNavigation: () -> Unit
) {
    Row(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Key(pageLabel, weight = 1.4f, onClick = onPage)
        Key("NAV", weight = 1.2f, onClick = onNavigation)
        Key(" ", weight = 4f) { onType(" ") }
        Key(".", weight = 1f) { onType(".") }
        Key("RETURN", weight = 2f) { onKey(Code.ENTER) }
    }
}

/**
 * One key.
 *
 * Held is the whole feedback: 92% black at rest, lightened by 30% white while a finger is on it,
 * and nothing else changes — no shadow, no lift, no rounding.
 */
@Composable
private fun RowScope.Key(
    label: String,
    weight: Float = 1f,
    onClick: () -> Unit
) {
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(weight)
            .fillMaxSize()
            .background(
                if (held) Color.White.copy(alpha = .30f)
                else Color.Black.copy(alpha = .92f)
            )
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        held = true
                        onClick()
                        tryAwaitRelease()
                        held = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        MontLabel(label, size = 14, alpha = if (held) MontWhite.ACTIVE else MontWhite.PRIMARY)
    }
}

private fun Char.shift(shifted: Boolean): String =
    if (shifted) uppercaseChar().toString() else toString()
