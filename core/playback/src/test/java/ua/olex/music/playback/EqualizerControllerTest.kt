package ua.olex.music.playback

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.dsp.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EqualizerControllerTest {
    private fun controller(): EqualizerController {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("equalizer-v1", 0).edit().clear().commit()
        return EqualizerController(context) {}
    }
    private val sony = OutputRoute(OutputRoute.Kind.BLUETOOTH, "WF-1000XM5", emptyList())
    private val phone = OutputRoute(OutputRoute.Kind.SPEAKER, "Pixel speakers", emptyList())

    @Test fun sonyCorrectionDoesNotCarryOverToSpeakersAndReturnsOnReconnection() {
        val controller = controller()
        controller.routeChanged(sony)
        assertTrue(controller.update(controller.state.routeKey, SonyCorrection.settings))
        controller.routeChanged(phone)
        assertFalse(controller.active.enabled)
        assertFalse(controller.state.sony)
        controller.routeChanged(sony)
        assertEquals(SonyCorrection.settings, controller.active)
    }
    @Test fun staleRouteUpdateAndSonyProfileOnNonSonyOutputAreRejected() {
        val controller = controller()
        controller.routeChanged(sony)
        val previousRoute = controller.state.routeKey
        controller.routeChanged(phone)
        assertFalse(controller.update(previousRoute, EqualizerSettings(true)))
        assertFalse(controller.update(controller.state.routeKey, SonyCorrection.settings))
        assertFalse(controller.active.enabled)
    }
    @Test fun settingsSurviveServiceRecreationAndUnknownRouteIsNeutral() {
        val controller = controller()
        controller.routeChanged(sony)
        controller.update(controller.state.routeKey, SonyCorrection.settings)
        val restored = EqualizerController(RuntimeEnvironment.getApplication()) {}
        restored.routeChanged(sony)
        assertEquals(SonyCorrection.settings, restored.active)
        restored.routeChanged(null)
        assertFalse(restored.active.enabled)
    }
    @Test fun invalidAndNonfiniteSessionPayloadsAreRejected() {
        val settings = SonyCorrection.settings
        assertEquals(settings, EqualizerCodec.decode(EqualizerCodec.encode(settings)))
        assertThrows(Exception::class.java) { EqualizerCodec.decode("{\"version\":999}") }
        assertThrows(Exception::class.java) { EqualizerCodec.decode("x".repeat(17_000)) }
    }
}
