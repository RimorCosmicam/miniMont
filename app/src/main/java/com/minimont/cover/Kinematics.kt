package com.minimont.cover

import kotlin.math.hypot

/**
 * How far the cursor goes for how far the finger went.
 *
 * Carried across from MiniDex, where it was arrived at by using it: a plain multiplier makes a
 * three-inch pad either too slow to cross a desktop or too twitchy to hit anything, and the
 * non-linear term is what lets one surface do both — slow movement stays precise, fast movement
 * covers ground.
 */
object Kinematics {

    fun pointer(rawDx: Float, rawDy: Float, sensitivity: Float, acceleration: Float): Pair<Float, Float> {
        val magnitude = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        if (magnitude <= 0.001f) return 0f to 0f
        val accelerated = 1f + acceleration * (magnitude / 8f).coerceAtMost(3.5f)
        val multiplier = sensitivity * accelerated
        return rawDx * multiplier to rawDy * multiplier
    }

    /**
     * Two fingers, into wheel notches.
     *
     * A wheel is not a distance, it is a count of clicks, so the finger's travel is divided down to
     * something an application reads as a few notches rather than as a flung page.
     */
    fun wheel(rawDx: Float, rawDy: Float, sensitivity: Float, natural: Boolean): Pair<Float, Float> {
        val direction = if (natural) 1f else -1f
        return (rawDx * sensitivity / 90f) * direction to (rawDy * sensitivity / 90f) * direction
    }
}
