package com.cardpersonality.honor.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Direct system-vibrator ticks.
 *
 * AOSP's `HapticFeedbackConstants.TEXT_HANDLE_MOVE` via `performHapticFeedback` is ignored by the
 * Honor vibrator service for third-party apps (dumpsys shows
 * `external | ignored_unsupported | reason: performHapticFeedback(constant=9)`), so custom slider
 * key-point ticks and snap ticks drive the Vibrator directly instead.
 */
object Haptics {
    private const val TAG = "CARD_PERSONALITY_HAPTICS"
    private const val MIN_INTERVAL_MS = 40L
    private var lastTickAt = 0L

    /** Short tick for slider key points / snap anchor activation (rate-limited). */
    fun tick(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTickAt < MIN_INTERVAL_MS) return
        lastTickAt = now
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(18L, 120))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "haptic tick failed", t)
        }
    }
}
