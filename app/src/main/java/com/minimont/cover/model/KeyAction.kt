package com.minimont.cover.model

import android.view.KeyEvent

/**
 * Key Action definitions for all MiniDex keys.
 */

sealed class KeyAction {
    data class StandardKey(
        val keyCode: Int,
        val label: String,
        val shiftLabel: String? = null,
        val subLabel: String? = null
    ) : KeyAction()

    data class ModifierKey(
        val modifier: ModifierType,
        val label: String
    ) : KeyAction()

    data class ShortcutKey(
        val label: String,
        val keyCodes: List<Int>,
        val requiredModifiers: List<ModifierType> = emptyList()
    ) : KeyAction()

    data class CustomText(
        val label: String,
        val text: String
    ) : KeyAction()

    data class PageSwitch(
        val targetPage: KeyboardPage,
        val label: String
    ) : KeyAction()

    data class MacroTrigger(
        val macroId: String,
        val label: String
    ) : KeyAction()

    data object SwitchToTouchpad : KeyAction()

    data object None : KeyAction()
}

/**
 * Quick builders for common keys.
 */
object KeyActions {
    fun char(char: Char, keyCode: Int, shiftChar: Char? = null, subLabel: String? = null): KeyAction =
        KeyAction.StandardKey(keyCode, char.toString(), shiftChar?.toString(), subLabel)

    fun key(keyCode: Int, label: String, subLabel: String? = null): KeyAction =
        KeyAction.StandardKey(keyCode, label, null, subLabel)

    fun mod(modifier: ModifierType, label: String): KeyAction =
        KeyAction.ModifierKey(modifier, label)

    fun shortcut(label: String, keyCode: Int, vararg modifiers: ModifierType): KeyAction =
        KeyAction.ShortcutKey(label, listOf(keyCode), modifiers.toList())

    val ESC = key(KeyEvent.KEYCODE_ESCAPE, "ESC")
    val TAB = key(KeyEvent.KEYCODE_TAB, "TAB")
    val ENTER = key(KeyEvent.KEYCODE_ENTER, "↵")
    val SPACE = key(KeyEvent.KEYCODE_SPACE, "SPACE")
    val BACKSPACE = key(KeyEvent.KEYCODE_DEL, "⌫")
    val DELETE = key(KeyEvent.KEYCODE_FORWARD_DEL, "DEL")

    val ARROW_UP = key(KeyEvent.KEYCODE_DPAD_UP, "↑")
    val ARROW_DOWN = key(KeyEvent.KEYCODE_DPAD_DOWN, "↓")
    val ARROW_LEFT = key(KeyEvent.KEYCODE_DPAD_LEFT, "←")
    val ARROW_RIGHT = key(KeyEvent.KEYCODE_DPAD_RIGHT, "→")

    val HOME = key(KeyEvent.KEYCODE_MOVE_HOME, "HOME")
    val END = key(KeyEvent.KEYCODE_MOVE_END, "END")
    val PAGE_UP = key(KeyEvent.KEYCODE_PAGE_UP, "PG UP")
    val PAGE_DOWN = key(KeyEvent.KEYCODE_PAGE_DOWN, "PG DN")
    val INSERT = key(KeyEvent.KEYCODE_INSERT, "INS")
    val PRINT_SCREEN = key(KeyEvent.KEYCODE_SYSRQ, "PRTSC")

    val SHIFT = mod(ModifierType.SHIFT, "⇧")
    val CTRL = mod(ModifierType.CTRL, "CTRL")
    val ALT = mod(ModifierType.ALT, "ALT")
    val META = mod(ModifierType.META, "⊞ WIN")
    val FN = mod(ModifierType.FN, "FN")
}
