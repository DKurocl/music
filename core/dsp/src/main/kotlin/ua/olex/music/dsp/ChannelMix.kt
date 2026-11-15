package ua.olex.music.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Stereo adjustments for hearing and odd recordings: [balance] from -1 (left only) to 1 (right
 * only) with a constant-power law, [mono] sums both sides, [swap] exchanges left and right.
 * Neutral settings do nothing at all (the sample path stays bit-exact).
 */
data class ChannelMix(val balance: Float = 0f, val mono: Boolean = false, val swap: Boolean = false) {
    init { require(balance.isFinite() && balance in -1f..1f) }
    val neutral: Boolean get() = balance == 0f && !mono && !swap

    // Constant power, normalised so the centre is unity on both sides (no level drop at 0).
    private val angle = (balance + 1) * PI / 4
    private val left = (cos(angle) / cos(PI / 4)).coerceAtMost(1.0).toFloat()
    private val right = (sin(angle) / sin(PI / 4)).coerceAtMost(1.0).toFloat()

    /** Applies the mix to one interleaved stereo frame in place. */
    fun apply(frame: FloatArray) {
        if (frame.size < 2) return
        var l = frame[0]; var r = frame[1]
        if (swap) { val t = l; l = r; r = t }
        if (mono) { val m = (l + r) * 0.5f; l = m; r = m }
        frame[0] = l * left; frame[1] = r * right
    }
}
