package com.minimont.cover.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.minimont.cover.model.HapticStrength

class HapticFeedbackManager(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Feedback, and never anything more than that.
     *
     * Wrapped because a vibration is a courtesy and killing the app is not a proportionate answer to
     * being refused one. This is exactly how it went wrong once already: the permission was missing
     * from miniMont's manifest, and tapping the pill took the whole cover screen down with it.
     */
    fun performHaptic(strength: HapticStrength) = runCatching { vibrate(strength) }.let { }

    private fun vibrate(strength: HapticStrength) {
        if (strength == HapticStrength.OFF || vibrator == null || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (strength) {
                HapticStrength.SUBTLE -> {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
                HapticStrength.CRISP -> {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                }
                HapticStrength.STRONG -> {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                }
                HapticStrength.OFF -> {}
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(strength.durationMs)
        }
    }
}
