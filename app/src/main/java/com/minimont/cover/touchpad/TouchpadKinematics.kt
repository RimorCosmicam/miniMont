package com.minimont.cover.touchpad

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Kinematics & Physics calculation for relative touchpad mouse movement and smooth scrolling.
 */
object TouchpadKinematics {

    /**
     * Calculates accelerated pointer delta based on instantaneous raw delta, sensitivity, and acceleration coefficient.
     */
    fun calculatePointerDelta(
        rawDx: Float,
        rawDy: Float,
        sensitivity: Float,
        acceleration: Float
    ): Pair<Float, Float> {
        val magnitude = kotlin.math.hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        if (magnitude <= 0.001f) return Pair(0f, 0f)

        // Non-linear acceleration multiplier
        val accelFactor = 1.0f + (acceleration * (magnitude / 8.0f).coerceAtMost(3.5f))
        val totalMultiplier = sensitivity * accelFactor

        val dx = rawDx * totalMultiplier
        val dy = rawDy * totalMultiplier

        return Pair(dx, dy)
    }

    /**
     * Calculates two-finger scroll delta with optional natural scrolling inversion.
     */
    fun calculateScrollDelta(
        rawDx: Float,
        rawDy: Float,
        scrollSensitivity: Float,
        naturalScrolling: Boolean
    ): Pair<Float, Float> {
        val directionMultiplier = if (naturalScrolling) 1.0f else -1.0f

        val scrollX = (rawDx * scrollSensitivity * 0.25f) * directionMultiplier
        val scrollY = (rawDy * scrollSensitivity * 0.25f) * directionMultiplier

        return Pair(scrollX, scrollY)
    }
}
