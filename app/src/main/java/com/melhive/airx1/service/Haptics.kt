package com.melhive.airx1.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibrations so you can feel what the air gestures did. */
class Haptics(context: Context) {
    var enabled = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun click() = play(VibrationEffect.EFFECT_CLICK, 15)
    fun heavy() = play(VibrationEffect.EFFECT_HEAVY_CLICK, 35)
    fun double() = play(VibrationEffect.EFFECT_DOUBLE_CLICK, 0)

    private fun play(predefined: Int, fallbackMs: Long) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(predefined))
            } else if (fallbackMs > 0) {
                v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 20), -1))
            }
        }
    }
}
