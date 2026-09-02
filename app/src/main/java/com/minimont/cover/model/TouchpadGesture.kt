package com.minimont.cover.model

/**
 * Gestures detected on the relative touchpad surface.
 */
sealed class TouchpadGesture {
    data class Move(val dx: Float, val dy: Float) : TouchpadGesture()
    data object SingleTap : TouchpadGesture()
    data object DoubleTap : TouchpadGesture()
    data object TwoFingerTap : TouchpadGesture()
    data class DragStart(val x: Float, val y: Float) : TouchpadGesture()
    data class DragMove(val dx: Float, val dy: Float) : TouchpadGesture()
    data object DragEnd : TouchpadGesture()
    data class Scroll(val dx: Float, val dy: Float) : TouchpadGesture()
}
