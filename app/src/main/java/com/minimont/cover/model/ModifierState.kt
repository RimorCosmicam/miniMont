package com.minimont.cover.model

import android.view.KeyEvent

/**
 * Tap-to-hold / latched modifier state model for mobile touchscreen typing:
 * - INACTIVE: Not active
 * - LATCHED: One-shot; active for the next key press, then resets to INACTIVE
 * - LOCKED: Double-tapped; permanently active until explicitly tapped again (e.g. Caps Lock, Ctrl lock)
 */
enum class ModifierLockState {
    INACTIVE,
    LATCHED,
    LOCKED;

    val isActive: Boolean get() = this != INACTIVE
}

data class ModifierState(
    val shift: ModifierLockState = ModifierLockState.INACTIVE,
    val ctrl: ModifierLockState = ModifierLockState.INACTIVE,
    val alt: ModifierLockState = ModifierLockState.INACTIVE,
    val meta: ModifierLockState = ModifierLockState.INACTIVE, // Win/Cmd/Super
    val fn: ModifierLockState = ModifierLockState.INACTIVE
) {
    /**
     * Converts current active modifiers to Android KeyEvent metaState bitmask.
     */
    fun toMetaState(): Int {
        var state = 0
        if (shift.isActive) {
            state = state or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (ctrl.isActive) {
            state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt.isActive) {
            state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (meta.isActive) {
            state = state or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        if (fn.isActive) {
            state = state or KeyEvent.META_FUNCTION_ON
        }
        return state
    }

    /**
     * Cycles a modifier on tap:
     * - INACTIVE -> LATCHED
     * - LATCHED -> LOCKED (or INACTIVE if allowLock is false)
     * - LOCKED -> INACTIVE
     */
    fun toggleModifier(modifier: ModifierType, allowLock: Boolean = true): ModifierState {
        val current = when (modifier) {
            ModifierType.SHIFT -> shift
            ModifierType.CTRL -> ctrl
            ModifierType.ALT -> alt
            ModifierType.META -> meta
            ModifierType.FN -> fn
        }

        val next = when (current) {
            ModifierLockState.INACTIVE -> ModifierLockState.LATCHED
            ModifierLockState.LATCHED -> if (allowLock) ModifierLockState.LOCKED else ModifierLockState.INACTIVE
            ModifierLockState.LOCKED -> ModifierLockState.INACTIVE
        }

        return withModifier(modifier, next)
    }

    /**
     * Consumes any one-shot (LATCHED) modifiers after a key action is dispatched.
     * Locked modifiers remain intact.
     */
    fun consumeLatched(): ModifierState {
        return copy(
            shift = if (shift == ModifierLockState.LATCHED) ModifierLockState.INACTIVE else shift,
            ctrl = if (ctrl == ModifierLockState.LATCHED) ModifierLockState.INACTIVE else ctrl,
            alt = if (alt == ModifierLockState.LATCHED) ModifierLockState.INACTIVE else alt,
            meta = if (meta == ModifierLockState.LATCHED) ModifierLockState.INACTIVE else meta,
            fn = if (fn == ModifierLockState.LATCHED) ModifierLockState.INACTIVE else fn
        )
    }

    fun withModifier(modifier: ModifierType, state: ModifierLockState): ModifierState {
        return when (modifier) {
            ModifierType.SHIFT -> copy(shift = state)
            ModifierType.CTRL -> copy(ctrl = state)
            ModifierType.ALT -> copy(alt = state)
            ModifierType.META -> copy(meta = state)
            ModifierType.FN -> copy(fn = state)
        }
    }

    fun clearAll(): ModifierState = ModifierState()
}

enum class ModifierType {
    SHIFT,
    CTRL,
    ALT,
    META,
    FN
}
