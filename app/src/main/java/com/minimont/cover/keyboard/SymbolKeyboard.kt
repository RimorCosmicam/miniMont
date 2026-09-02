package com.minimont.cover.keyboard

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minimont.cover.components.KeyButton
import com.minimont.cover.theme.LocalMiniDexColors

@Composable
fun SymbolKeyboard(
    keyHeight: Dp = 44.dp,
    keyGap: Dp = 3.dp,
    onCharPress: (Char, Int) -> Unit,
    onKeyPress: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMiniDexColors.current

    val numRow = listOf(
        Pair('1', KeyEvent.KEYCODE_1),
        Pair('2', KeyEvent.KEYCODE_2),
        Pair('3', KeyEvent.KEYCODE_3),
        Pair('4', KeyEvent.KEYCODE_4),
        Pair('5', KeyEvent.KEYCODE_5),
        Pair('6', KeyEvent.KEYCODE_6),
        Pair('7', KeyEvent.KEYCODE_7),
        Pair('8', KeyEvent.KEYCODE_8),
        Pair('9', KeyEvent.KEYCODE_9),
        Pair('0', KeyEvent.KEYCODE_0)
    )

    val symRow1 = listOf(
        Pair('+', KeyEvent.KEYCODE_PLUS),
        Pair('-', KeyEvent.KEYCODE_MINUS),
        Pair('*', KeyEvent.KEYCODE_STAR),
        Pair('/', KeyEvent.KEYCODE_SLASH),
        Pair('=', KeyEvent.KEYCODE_EQUALS),
        Pair('%', KeyEvent.KEYCODE_UNKNOWN),
        Pair('$', KeyEvent.KEYCODE_UNKNOWN),
        Pair('#', KeyEvent.KEYCODE_POUND),
        Pair('@', KeyEvent.KEYCODE_AT),
        Pair('~', KeyEvent.KEYCODE_UNKNOWN)
    )

    val symRow2 = listOf(
        Pair('(', KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN),
        Pair(')', KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN),
        Pair('[', KeyEvent.KEYCODE_LEFT_BRACKET),
        Pair(']', KeyEvent.KEYCODE_RIGHT_BRACKET),
        Pair('{', KeyEvent.KEYCODE_UNKNOWN),
        Pair('}', KeyEvent.KEYCODE_UNKNOWN),
        Pair('<', KeyEvent.KEYCODE_UNKNOWN),
        Pair('>', KeyEvent.KEYCODE_UNKNOWN),
        Pair('\\', KeyEvent.KEYCODE_BACKSLASH),
        Pair('|', KeyEvent.KEYCODE_UNKNOWN)
    )

    val symRow3 = listOf(
        Pair('`', KeyEvent.KEYCODE_GRAVE),
        Pair('!', KeyEvent.KEYCODE_UNKNOWN),
        Pair('?', KeyEvent.KEYCODE_UNKNOWN),
        Pair('&', KeyEvent.KEYCODE_UNKNOWN),
        Pair('^', KeyEvent.KEYCODE_UNKNOWN),
        Pair(':', KeyEvent.KEYCODE_UNKNOWN),
        Pair(';', KeyEvent.KEYCODE_SEMICOLON),
        Pair('"', KeyEvent.KEYCODE_UNKNOWN),
        Pair('\'', KeyEvent.KEYCODE_APOSTROPHE),
        Pair('_', KeyEvent.KEYCODE_UNKNOWN)
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(keyGap)
    ) {
        // Numbers: 1 2 3 4 5 6 7 8 9 0
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keyGap)
        ) {
            numRow.forEach { (char, keyCode) ->
                KeyButton(
                    label = char.toString(),
                    modifier = Modifier.weight(1f).height(keyHeight),
                    onTap = { onCharPress(char, keyCode) }
                )
            }
        }

        // Symbols 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keyGap)
        ) {
            symRow1.forEach { (char, keyCode) ->
                KeyButton(
                    label = char.toString(),
                    modifier = Modifier.weight(1f).height(keyHeight),
                    onTap = { onCharPress(char, keyCode) }
                )
            }
        }

        // Symbols 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keyGap)
        ) {
            symRow2.forEach { (char, keyCode) ->
                KeyButton(
                    label = char.toString(),
                    modifier = Modifier.weight(1f).height(keyHeight),
                    onTap = { onCharPress(char, keyCode) }
                )
            }
        }

        // Symbols 3 + Space & Enter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keyGap)
        ) {
            symRow3.take(5).forEach { (char, keyCode) ->
                KeyButton(
                    label = char.toString(),
                    modifier = Modifier.weight(1f).height(keyHeight),
                    onTap = { onCharPress(char, keyCode) }
                )
            }

            KeyButton(
                label = "␣",
                modifier = Modifier.weight(2f).height(keyHeight),
                onTap = { onKeyPress(KeyEvent.KEYCODE_SPACE) }
            )

            KeyButton(
                label = "↵",
                modifier = Modifier.weight(1.5f).height(keyHeight),
                onTap = { onKeyPress(KeyEvent.KEYCODE_ENTER) }
            )
        }
    }
}
