package ua.olex.music.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.playback.AudioDiagnostics
import ua.olex.music.playback.OutputRoute

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioPathSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun observedAudioTrackIsLabelledSeparatelyFromBluetooth() {
        var outputRequests = 0
        compose.setContent { AppTheme {
            AudioPathSheet("FLAC 24/96", OutputRoute(OutputRoute.Kind.BLUETOOTH, "WF-1000XM5", listOf(96_000)),
                AudioDiagnostics("c2.android.flac.decoder", "32-bit float PCM", 96_000, 2), {}, { outputRequests++ })
        } }
        compose.onNodeWithText("32-bit float PCM · 96000 Hz · 2 channels").assertExists()
        compose.onNodeWithText("WF-1000XM5").assertExists()
        compose.onNodeWithText("LDAC").assertDoesNotExist()
        compose.onNodeWithText("Change output").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, outputRequests) }
    }
}
