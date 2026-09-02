package com.minimont.cover.model

/**
 * Available pages within Keyboard Mode.
 */
enum class KeyboardPage(val title: String, val iconName: String) {
    ABC("ABC", "text_format"),
    SYMBOLS("123", "numbers"),
    NAV("NAV", "navigation"),
    MACROS("MACROS", "grid_view"),
    SETTINGS("SETTINGS", "settings")
}
