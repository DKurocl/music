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

/** Processing is opt-in; preamp is bounded by the combined response at the actual output sample rate. */
data class EqualizerSettings(val enabled: Boolean = false, val preampDb: Double = 0.0,
    val bands: List<EqualizerBand> = flatBands(), val profile: String = "Custom", val channels: ChannelMix = ChannelMix()) {
    init { require(preampDb.isFinite() && preampDb in -36.0..12.0); require(bands.size <= 20) }
    /** Whether any processing runs: the EQ itself or a channel change (balance, mono, swap). */
    val active: Boolean get() = enabled || !channels.neutral
    fun effectivePreampDb(sampleRateHz: Int) = effectivePreampDb(sampleRateHz, coefficients(sampleRateHz))

    internal fun coefficients(sampleRateHz: Int) = bands.mapNotNull { band ->
        band.takeIf { it.gainDb != 0.0 }?.let { Biquad.of(it, sampleRateHz) }
    }

    internal fun effectivePreampDb(sampleRateHz: Int, filters: List<Biquad>): Double {
        if (filters.isEmpty()) return min(preampDb, 0.0)
        val top = sampleRateHz * 0.499
        fun response(frequencyHz: Double): Double {
            val w = 2 * PI * frequencyHz / sampleRateHz
            val c = cos(w)
            val s = sin(w)
            val c2 = cos(2 * w)
            val s2 = sin(2 * w)
            return filters.sumOf { filter -> 20 * log10(filter.magnitude(c, s, c2, s2).coerceAtLeast(1e-20)) }
        }
        var boost = maxOf(response(0.0), response(top))
        for (i in 0..2048) boost = maxOf(boost, response(10.0 * (top / 10.0).pow(i / 2048.0)))
        for (band in bands) boost = maxOf(boost, response(min(band.frequencyHz, sampleRateHz * 0.45)))
        return min(preampDb, if (boost > 0.001) -boost - 0.5 else 0.0)
    }
    companion object {
        val frequencies = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
        fun flatBands() = frequencies.map { EqualizerBand(it, 0.0) }
    }
}

/** Normalized RBJ coefficients. Double precision avoids low-frequency cancellation at 96/192 kHz. */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun magnitudeAt(frequencyHz: Double, sampleRateHz: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRateHz
        return magnitude(cos(w), sin(w), cos(2 * w), sin(2 * w))
    }

    internal fun magnitude(cosW: Double, sinW: Double, cos2W: Double, sin2W: Double): Double {
        val nr = b0 + b1 * cosW + b2 * cos2W
        val ni = -b1 * sinW - b2 * sin2W
        val dr = 1 + a1 * cosW + a2 * cos2W
        val di = -a1 * sinW - a2 * sin2W
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
    private val filters = if (settings.enabled) settings.coefficients(sampleRateHz) else emptyList()
    private val z1 = Array(channels) { DoubleArray(filters.size) }
    private val z2 = Array(channels) { DoubleArray(filters.size) }
    private val gain = if (settings.enabled) 10.0.pow(settings.effectivePreampDb(sampleRateHz, filters) / 20) else 1.0
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
