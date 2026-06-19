package dev.phucngu.simpletype.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays short key-press vibrations whose intensity follows a user-chosen [strength] (0f..1f).
 *
 * Device haptic hardware varies wildly, so we pick the richest path each phone supports and degrade
 * gracefully:
 *  1. **Composition primitives** (API 30+, when `PRIMITIVE_CLICK` is supported) — the recommended
 *     modern API. The primitive's `scale` (0f..1f) maps directly onto our strength slider.
 *  2. **Amplitude one-shot** (when [Vibrator.hasAmplitudeControl]) — a short buzz with amplitude
 *     1..255 derived from strength.
 *  3. **Plain one-shot** — a fixed short buzz at the system default amplitude; strength can't be
 *     honoured here, so it only acts as on/off (the slider still gates via the caller).
 *
 * Construct once and reuse; resolving the vibrator and probing its capabilities is done up front.
 */
class HapticPlayer(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val supportsClickPrimitive: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true

    private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    /** A light tap for an ordinary key press. */
    fun tap(strength: Float) = play(strength, strong = false)

    /** A firmer double-pulse for long-press actions, so they feel distinct from a tap. */
    fun longPress(strength: Float) = play(strength, strong = true)

    private fun play(strength: Float, strong: Boolean) {
        val v = vibrator ?: return
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || !v.hasVibrator()) return

        val effect = when {
            supportsClickPrimitive -> {
                val c = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, s)
                if (strong) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, s, 60)
                c.compose()
            }
            hasAmplitudeControl -> {
                val amplitude = (s * 255f).toInt().coerceIn(1, 255)
                VibrationEffect.createOneShot(if (strong) 30L else 18L, amplitude)
            }
            else -> VibrationEffect.createOneShot(
                if (strong) 30L else 15L, VibrationEffect.DEFAULT_AMPLITUDE
            )
        }
        v.vibrate(effect)
    }
}
