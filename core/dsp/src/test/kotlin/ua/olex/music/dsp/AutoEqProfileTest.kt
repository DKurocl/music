package ua.olex.music.dsp

import org.junit.Assert.*
import org.junit.Test

class AutoEqProfileTest {
    private val profile = """
        Preamp: -4.8 dB
        Filter 1: ON LSC Fc 105 Hz Gain -1.0 dB Q 0.70
        Filter 2: ON PK Fc 540 Hz Gain 4.5 dB Q 1.53
        Filter 3: ON HSC Fc 10000 Hz Gain 4.8 dB Q 0.70
    """.trimIndent()
    @Test fun preservesMeasuredCoefficientsAndLeavesProcessingDisabled() {
        val imported = AutoEqProfile.parse(profile, "Reference")
        assertFalse(imported.enabled)
        assertEquals(-4.8, imported.preampDb, 0.0)
        assertEquals(listOf(FilterType.LOW_SHELF, FilterType.PEAK, FilterType.HIGH_SHELF), imported.bands.map { it.type })
        assertEquals(EqualizerBand(540.0, 4.5, 1.53), imported.bands[1])
        assertTrue(imported.effectivePreampDb(96_000) <= imported.preampDb)
    }
    @Test fun commentsBomCrLfAndDisabledFiltersAreHandled() {
        val imported = AutoEqProfile.parse("\uFEFF# headphone\r\n" + profile.replace("\n", "\r\n") +
            "\r\nFilter 4: OFF PK Fc 100 Hz Gain 10 dB Q 1 # unused", "  ")
        assertEquals(3, imported.bands.size)
        assertEquals("Imported AutoEq", imported.profile)
    }
    @Test fun unsupportedOrAmbiguousCommandsCannotPartiallyReplaceTheActiveCurve() {
        for (invalid in listOf(profile + "\nCopy: L=R", profile + "\nPreamp: -3 dB", profile.replace("LSC", "BP"),
            profile + "\nFilter 1: ON PK Fc 200 Hz Gain 1 dB Q 1", profile.replace("105 Hz", "99999 Hz"),
            profile.replace("0.70", "0.00"), profile.replace("-4.8 dB", "NaN dB"), "", "x".repeat(65_537))) {
            assertThrows(IllegalArgumentException::class.java) { AutoEqProfile.parse(invalid, "Bad") }
        }
    }
}
