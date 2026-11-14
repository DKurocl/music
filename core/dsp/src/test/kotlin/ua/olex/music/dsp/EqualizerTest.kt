package ua.olex.music.dsp

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class EqualizerTest {
    @Test fun disabledProcessingIsBitIdenticalIncludingSpecialFloatValues() {
        val engine = EqualizerEngine(EqualizerSettings(), 96_000, 2)
        for (value in listOf(0f, -0f, Float.MIN_VALUE, 0.25f, -0.8f, Float.NaN)) {
            assertEquals(value.toRawBits(), engine.sample(value, 0).toRawBits())
        }
    }
    @Test fun peakHasSpecifiedCenterGainAndUnityAtDcAndNyquist() {
        val filter = Biquad.of(EqualizerBand(1_000.0, 6.0, 1.0), 96_000)
        assertEquals(6.0, 20 * log10(filter.magnitudeAt(1_000.0, 96_000)), 0.0001)
        assertEquals(1.0, filter.magnitudeAt(0.0, 96_000), 0.0001)
        assertEquals(1.0, filter.magnitudeAt(48_000.0, 96_000), 0.0001)
    }
    @Test fun shelvesReachTheirSpecifiedGainAtTheCorrectEnd() {
        val low = Biquad.of(EqualizerBand(100.0, -6.0, 0.7, FilterType.LOW_SHELF), 48_000)
        val high = Biquad.of(EqualizerBand(8_000.0, 4.0, 0.7, FilterType.HIGH_SHELF), 48_000)
        assertEquals(-6.0, 20 * log10(low.magnitudeAt(0.0, 48_000)), 0.0001)
        assertEquals(0.0, 20 * log10(low.magnitudeAt(24_000.0, 48_000)), 0.0001)
        assertEquals(4.0, 20 * log10(high.magnitudeAt(24_000.0, 48_000)), 0.0001)
    }
    @Test fun filterStateIsIndependentPerChannelAndResettable() {
        val engine = EqualizerEngine(EqualizerSettings(true, bands = listOf(EqualizerBand(500.0, -6.0))), 48_000, 2)
        engine.sample(0.5f, 0)
        repeat(100) { assertEquals(0f, engine.sample(0f, 1)) }
        engine.reset()
        assertEquals(0f, engine.sample(0f, 0))
    }
    @Test fun measuredSineAttenuationMatchesAnalyticResponse() {
        val band = EqualizerBand(1_000.0, -9.0)
        val engine = EqualizerEngine(EqualizerSettings(true, bands = listOf(band)), 48_000, 1)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        for (i in 0 until 48_000) {
            val input = (0.1 * sin(2 * PI * 1_000 * i / 48_000)).toFloat()
            val output = engine.sample(input, 0)
            if (i >= 4_800) { inputEnergy += input * input; outputEnergy += output * output }
        }
        assertEquals(-9.0, 10 * log10(outputEnergy / inputEnergy), 0.001)
    }
    @Test fun allSupportedSampleRatesRemainFiniteAndBoundedUnderStress() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000)) {
            val engine = EqualizerEngine(EqualizerSettings(true, bands = listOf(EqualizerBand(31.0, 12.0), EqualizerBand(16_000.0, -12.0))), rate, 2)
            repeat(10_000) { i ->
                val output = engine.sample(if (i % 2 == 0) 1f else -1f, i % 2)
                assertTrue(output.isFinite() && output in -1f..1f)
            }
        }
    }
    @Test fun invalidOrNonfiniteConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EqualizerBand(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { EqualizerSettings(preampDb = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { EqualizerBand(1_000.0, 100.0) }
    }
}
