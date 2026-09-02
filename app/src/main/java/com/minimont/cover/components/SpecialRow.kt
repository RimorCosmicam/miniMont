package com.minimont.cover.components

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minimont.cover.model.ModifierState
import com.minimont.cover.model.ModifierType

@Composable
fun SpecialRow(
    modifier: Modifier = Modifier,
    modifierState: ModifierState,
    keyHeight: Dp = 32.dp,
    onModifierToggle: (ModifierType) -> Unit,
    onKeyPress: (Int) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(keyHeight)
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ESC Key
        KeyButton(
            label = "ESC",
            modifier = Modifier.width(38.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_ESCAPE) }
        )

        // TAB Key
        KeyButton(
            label = "TAB",
            modifier = Modifier.width(36.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_TAB) }
        )

        // CTRL Modifier
        KeyButton(
            label = "CTRL",
            modifier = Modifier.width(40.dp).height(keyHeight),
            lockState = modifierState.ctrl,
            onTap = { onModifierToggle(ModifierType.CTRL) }
        )

        // ALT Modifier
        KeyButton(
            label = "ALT",
            modifier = Modifier.width(36.dp).height(keyHeight),
            lockState = modifierState.alt,
            onTap = { onModifierToggle(ModifierType.ALT) }
        )

        // Meta modifier: Android's META key, presented with the Mac symbol.
        KeyButton(
            label = "⌘",
            subLabel = "META",
            modifier = Modifier.width(44.dp).height(keyHeight),
            lockState = modifierState.meta,
            onTap = { onModifierToggle(ModifierType.META) }
        )

        // DELETE Key
        KeyButton(
            label = "DEL",
            modifier = Modifier.width(36.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_FORWARD_DEL) }
        )

        // Arrow Keys
        KeyButton(
            label = "↑",
            modifier = Modifier.width(32.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_DPAD_UP) }
        )
        KeyButton(
            label = "↓",
            modifier = Modifier.width(32.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_DPAD_DOWN) }
        )
        KeyButton(
            label = "←",
            modifier = Modifier.width(32.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_DPAD_LEFT) }
        )
        KeyButton(
            label = "→",
            modifier = Modifier.width(32.dp).height(keyHeight),
            onTap = { onKeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) }
        )
    }
}
