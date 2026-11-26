package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePolicyTest {
    private val ready = ResourceInputs(
        visible = true, interactive = true, saver = false, batteryPercent = 70, charging = true,
        thermal = ThermalPressure.NORMAL, validated = true, unmetered = true, chargingLearning = true,
    )

    @Test fun blackoutStopsEveryOptionalWorkClassAcrossTransitions() {
        for (work in OptionalWork.entries) {
            assertNull(ResourcePolicy.denial(ready, work))
            assertEquals(ResourceDenial.BLACKOUT, ResourcePolicy.denial(ready.copy(saver = true), work))
            assertEquals(ResourceDenial.BLACKOUT, ResourcePolicy.denial(ready.copy(blackout = true), work))
            assertEquals(ResourceDenial.BLACKOUT, ResourcePolicy.denial(
                ready.copy(charging = false, batteryPercent = 10, batteryBand = BatteryBand.CRITICAL), work))
            assertEquals(ResourceDenial.THERMAL, ResourcePolicy.denial(ready.copy(thermal = ThermalPressure.SEVERE), work))
            assertNull(ResourcePolicy.denial(ready, work))
        }
    }

    @Test fun aHiddenLocalSessionDoesNotAuthorizeAnAutomaticScanOrProviderCall() {
        for (visible in listOf(false, true)) for (interactive in listOf(null, false, true)) {
            val input = ready.copy(visible = visible, interactive = interactive, chargingLearning = false)
            if (visible && interactive == true) continue
            for (work in OptionalWork.entries.filter { it.foregroundOnly || it == OptionalWork.LEARNING }) {
                assertEquals(ResourceDenial.DORMANT, ResourcePolicy.denial(input, work))
            }
            assertEquals(if (interactive == true) null else ResourceDenial.DORMANT,
                ResourcePolicy.denial(input, OptionalWork.LOCK_SCREEN_LYRICS))
            assertNull(ResourcePolicy.denial(input, OptionalWork.REMOTE_QUEUE_REFILL))
        }
    }

    @Test fun chargingLearningStopsAfterUnplugOrThermalAndNetworkLoss() {
        val hidden = ready.copy(visible = false, interactive = false)
        assertNull(ResourcePolicy.denial(hidden, OptionalWork.LEARNING))
        assertEquals(ResourceDenial.NOT_CHARGING, ResourcePolicy.denial(hidden.copy(charging = false), OptionalWork.LEARNING))
        assertEquals(ResourceDenial.THERMAL, ResourcePolicy.denial(hidden.copy(thermal = ThermalPressure.MODERATE), OptionalWork.LEARNING))
        assertEquals(ResourceDenial.DORMANT, ResourcePolicy.denial(hidden.copy(chargingLearning = false), OptionalWork.LEARNING))
        for (work in OptionalWork.entries.filter { it.usesNetwork }) {
            assertEquals(ResourceDenial.OFFLINE, ResourcePolicy.denial(ready.copy(validated = false), work))
            assertEquals(ResourceDenial.OFFLINE, ResourcePolicy.denial(ready.copy(validated = null), work))
            val metered = if (work == OptionalWork.METERED_MOTION_COVER) null else ResourceDenial.METERED
            assertEquals(metered, ResourcePolicy.denial(ready.copy(unmetered = false), work))
            assertEquals(metered, ResourcePolicy.denial(ready.copy(unmetered = null), work))
        }
        assertNull(ResourcePolicy.denial(ready.copy(validated = false), OptionalWork.LOCAL_SCAN))
        assertNull(ResourcePolicy.denial(ready.copy(validated = false), OptionalWork.SESSION_TASTE))
    }

    @Test fun unknownPowerSignalsFailClosedAndPercentageHasHysteresis() {
        for (work in OptionalWork.entries) {
            assertNotNull(ResourcePolicy.denial(ready.copy(saver = null), work))
            assertNotNull(ResourcePolicy.denial(ready.copy(batteryPercent = null, batteryBand = null), work))
            assertNotNull(ResourcePolicy.denial(ready.copy(thermal = ThermalPressure.UNKNOWN), work))
        }
        val bands = listOf(25, 26, 27, 28, 10, 11, 12, 13, 10, 75).runningFold(BatteryBand.NORMAL, BatteryBand::after)
        assertEquals(listOf(BatteryBand.NORMAL, BatteryBand.LOW, BatteryBand.LOW, BatteryBand.LOW,
            BatteryBand.NORMAL, BatteryBand.CRITICAL, BatteryBand.CRITICAL, BatteryBand.CRITICAL, BatteryBand.LOW,
            BatteryBand.CRITICAL, BatteryBand.NORMAL), bands)
        val low = ready.copy(batteryPercent = 27, batteryBand = BatteryBand.LOW, charging = false)
        assertEquals(ResourceDenial.BLACKOUT, ResourcePolicy.denial(low, OptionalWork.PREFETCH))
        assertEquals(ResourceDenial.BLACKOUT, ResourcePolicy.denial(low, OptionalWork.METERED_MOTION_COVER))
        assertEquals(ResourceDenial.THERMAL, ResourcePolicy.denial(ready.copy(thermal = ThermalPressure.MODERATE), OptionalWork.PREFETCH))
        assertNull(ResourcePolicy.denial(low.copy(charging = true), OptionalWork.PREFETCH))
        assertNull(ResourcePolicy.denial(low, OptionalWork.PROVIDER))
    }

    @Test fun noSignalCombinationCanAdmitForbiddenWork() {
        val booleans = listOf(false, true)
        val signals = listOf(false, true, null)
        for (visible in booleans) for (interactive in signals) for (saver in signals)
            for (battery in listOf(null, *BatteryBand.entries.toTypedArray())) for (charging in signals)
                for (thermal in ThermalPressure.entries) for (validated in signals) for (unmetered in signals)
                    for (consent in booleans) for (blackout in booleans) {
                        val input = ready.copy(visible = visible, interactive = interactive, saver = saver,
                            batteryBand = battery, charging = charging, thermal = thermal, validated = validated,
                            unmetered = unmetered, chargingLearning = consent, blackout = blackout)
                        for (work in OptionalWork.entries) {
                            if (ResourcePolicy.denial(input, work) != null) continue
                            assertTrue(!blackout && saver == false && battery != null)
                            assertTrue(battery != BatteryBand.CRITICAL || charging == true)
                            assertTrue(thermal == ThermalPressure.NORMAL || thermal == ThermalPressure.MODERATE)
                            if (work.foregroundOnly) assertTrue(visible && interactive == true)
                            if (work == OptionalWork.LOCK_SCREEN_LYRICS) assertTrue(interactive == true)
                            if (work.usesNetwork) assertTrue(validated == true)
                            if (work.usesNetwork && work != OptionalWork.METERED_MOTION_COVER) assertTrue(unmetered == true)
                            if (work == OptionalWork.LEARNING) assertTrue(consent && charging == true && thermal == ThermalPressure.NORMAL)
                        }
                    }
    }
}
