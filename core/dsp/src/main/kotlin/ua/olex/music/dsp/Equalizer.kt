package ua.olex.music.dsp

import kotlin.math.*

/** RBJ biquad types used by measured parametric headphone corrections and the graphic equalizer. */
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF }

/** Frequency is Hz, gain is dB and Q is dimensionless. Validate before entering an audio thread. */
data class EqualizerBand(val frequencyHz: Double, val gainDb: Double, val q: Double = 1.4, val type: FilterType = FilterType.PEAK) {
    init {
        require(frequencyHz.isFinite() && frequencyHz in 10.0..24_000.0)
        require(gainDb.isFinite() && gainDb in -24.0..24.0)
        require(q.isFinite() && q in 0.1..12.0)
    }
}

/** Processing is opt-in. Headroom reserves the sum of positive gains, deliberately conservatively. */
data class EqualizerSettings(val enabled: Boolean = false, val preampDb: Double = 0.0,
    val bands: List<EqualizerBand> = flatBands(), val profile: String = "Custom") {
    init { require(preampDb.isFinite() && preampDb in -36.0..12.0); require(bands.size <= 20) }
    val effectivePreampDb: Double get() = min(preampDb, -bands.sumOf { max(0.0, it.gainDb) })
    companion object {
        val frequencies = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
        fun flatBands() = frequencies.map { EqualizerBand(it, 0.0) }
    }
}

/** Normalized RBJ coefficients. Double precision avoids low-frequency cancellation at 96/192 kHz. */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun magnitudeAt(frequencyHz: Double, sampleRateHz: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRateHz
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val ni = -b1 * sin(w) - b2 * sin(2 * w)
        val dr = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val di = -a1 * sin(w) - a2 * sin(2 * w)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        fun of(band: EqualizerBand, sampleRateHz: Int): Biquad {
            require(sampleRateHz in 8_000..768_000)
            val w = 2 * PI * min(band.frequencyHz, sampleRateHz * 0.45) / sampleRateHz
            val a = 10.0.pow(band.gainDb / 40)
            val c = cos(w)
            val alpha = sin(w) / (2 * band.q)
            val k = 2 * sqrt(a) * alpha
            val v = when (band.type) {
                FilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
                FilterType.LOW_SHELF -> doubleArrayOf(a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                    (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k)
                FilterType.HIGH_SHELF -> doubleArrayOf(a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                    (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k)
            }
            return Biquad(v[0] / v[3], v[1] / v[3], v[2] / v[3], v[4] / v[3], v[5] / v[3])
        }
    }
}

/** One filter bank per output. State is independent per channel; no sample-rate conversion or allocations per sample. */
class EqualizerEngine(val settings: EqualizerSettings, sampleRateHz: Int, private val channels: Int) {
    private val filters = settings.bands.filter { it.gainDb != 0.0 }.map { Biquad.of(it, sampleRateHz) }
    private val z1 = Array(channels) { DoubleArray(filters.size) }
    private val z2 = Array(channels) { DoubleArray(filters.size) }
    private val gain = if (settings.enabled) 10.0.pow(settings.effectivePreampDb / 20) else 1.0
    init { require(channels in 1..32) }

    fun sample(input: Float, channel: Int): Float {
        if (!settings.enabled) return input
        var value = if (input.isFinite()) input.toDouble() * gain else 0.0
        for (i in filters.indices) {
            val f = filters[i]
            val output = f.b0 * value + z1[channel][i]
            z1[channel][i] = f.b1 * value - f.a1 * output + z2[channel][i]
            z2[channel][i] = f.b2 * value - f.a2 * output
            value = output
        }
        // This final guard contains malformed inputs or unexpected filter transients at full scale.
        return value.coerceIn(-1.0, 1.0).toFloat()
    }

    fun reset() { z1.forEach { it.fill(0.0) }; z2.forEach { it.fill(0.0) } }
}
