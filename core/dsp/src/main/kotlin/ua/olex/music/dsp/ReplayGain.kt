package ua.olex.music.dsp

import kotlin.math.min
import kotlin.math.pow

/** Album mode falls back to the complete track gain/peak pair, never a mixture of the two. */
enum class ReplayGainMode { OFF, TRACK, ALBUM }

/** Global opt-in loudness settings. Untagged files retain their original level. */
data class ReplayGainSettings(val mode: ReplayGainMode = ReplayGainMode.OFF,
    val preampDb: Double = 0.0, val preventClipping: Boolean = true) {
    init { require(preampDb.isFinite() && preampDb in -24.0..12.0) }
}

/** Scanner-normalized gains in dB (including R128 reference conversion), peaks in linear full scale. */
data class ReplayGainTags(val trackGainDb: Double? = null, val trackPeak: Double? = null,
    val albumGainDb: Double? = null, val albumPeak: Double? = null) {
    fun multiplier(settings: ReplayGainSettings): Double {
        if (settings.mode == ReplayGainMode.OFF) return 1.0
        fun Double?.gain() = this?.takeIf { it.isFinite() && it in -120.0..120.0 }
        val album = settings.mode == ReplayGainMode.ALBUM && albumGainDb.gain() != null
        val db = (if (album) albumGainDb.gain() else trackGainDb.gain()) ?: return 1.0
        val desired = 10.0.pow((db + settings.preampDb) / 20)
        if (!settings.preventClipping) return desired
        val peak = (if (album) albumPeak else trackPeak)?.takeIf { it.isFinite() && it > 0 }
        // Unknown peaks cannot justify a positive boost; keep album-relative levels consistent.
        return min(desired, peak?.let { 1.0 / it } ?: 1.0)
    }
}
