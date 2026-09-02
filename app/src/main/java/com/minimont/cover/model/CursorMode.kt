package com.minimont.cover.model



enum class CursorMode(val displayName: String, val description: String) {
    AUTO_NATIVE("Native UHID", "Direct virtual hardware mouse; best chance of a visible cursor"),
    ANDROID_HID("Android HID", "Uses Android's built-in HID command"),
    DISPLAY_ROLL("Display Roll", "Forces relative pointer events onto the selected display"),
    DISPLAY_ABSOLUTE("Absolute Mouse", "Compatibility mode with absolute display coordinates")
}
