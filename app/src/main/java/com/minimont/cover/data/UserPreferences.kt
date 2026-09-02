package com.minimont.cover.data

import com.minimont.cover.model.CursorMode
import com.minimont.cover.model.HapticStrength
import com.minimont.cover.model.KeyHeightLevel
import com.minimont.cover.model.HalftoneColorway
import com.minimont.cover.model.VisualFilter


data class UserPreferences(
    val colorway: HalftoneColorway = HalftoneColorway.MUSTARD,
    val amoledMode: Boolean = false,
    val visualFilter: VisualFilter = VisualFilter.NONE,
    val hapticStrength: HapticStrength = HapticStrength.CRISP,
    val keyHeightLevel: KeyHeightLevel = KeyHeightLevel.BALANCED,
    val keyGapDp: Int = 4,
    val doubleTapToLockModifier: Boolean = true,
    val modifierTimeoutMs: Long = 0L, // 0 = no timeout for latched
    val pointerSensitivity: Float = 1.2f,
    val pointerAcceleration: Float = 0.5f,
    val scrollSensitivity: Float = 1.0f,
    val naturalScrolling: Boolean = false,
    val edgeScrollOnRight: Boolean = false,
    // miniMate's own figures for the edge controls.
    val edgeScrollEnabled: Boolean = true,
    val edgeRightClickEnabled: Boolean = true,
    val edgeRailScale: Float = 1f,
    val edgeCornerScale: Float = 1f,
    val backgroundGifUri: String = "",
    val backgroundGifScale: Float = 1f,
    val backgroundGifOffsetX: Float = 0f,
    val backgroundGifOffsetY: Float = 0f,
    val backgroundGifOpacity: Float = 0.55f,
    val tapToClick: Boolean = true,
    val cursorMode: CursorMode = CursorMode.AUTO_NATIVE,
    val preferredBackend: String = "AUTO", // AUTO, SHIZUKU, VIRTUAL_DEVICE, FALLBACK
    val manualDisplayId: Int = -1, // -1 = auto detect
    val adbAutoConnect: Boolean = true,
    val adbPort: Int = 5555,
    val onboardingComplete: Boolean = false,
    val adbPairedBefore: Boolean = false
)
