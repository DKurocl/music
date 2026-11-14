package ua.olex.music.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.dsp.*
import ua.olex.music.playback.EqualizerState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun correctionTargetsTheDisplayedSonyOutputOnly() {
        var applied: Pair<String, EqualizerSettings>? = null
        compose.setContent { AppTheme { EqualizerSheet(EqualizerState("sony-wf1000xm5", "WF-1000XM5", true), true, null,
            { key, settings -> applied = key to settings }, {}) } }
        compose.onNodeWithText("Sony correction").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("sony-wf1000xm5", applied?.first)
            assertEquals(SonyCorrection.settings, applied?.second)
        }
    }
    @Test fun unrelatedOutputDoesNotOfferSonyCorrection() {
        compose.setContent { AppTheme { EqualizerSheet(EqualizerState("phone-speaker", "Phone speakers"), true, null, { _, _ -> }, {}) } }
        compose.onNodeWithText("Sony correction").assertDoesNotExist()
        compose.onNodeWithContentDescription("Enable equalizer").assertIsOff()
    }
    @Test fun normalizationCanSelectAlbumModeWithoutEnablingHeadphoneEq() {
        var normalized: ReplayGainSettings? = null
        compose.setContent { AppTheme { EqualizerSheet(EqualizerState("phone-speaker", "Phone speakers"), true, null,
            { _, _ -> error("ReplayGain changed the EQ") }, {}, onLoudness = { normalized = it }) } }
        compose.onNodeWithText("Album").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReplayGainMode.ALBUM, normalized?.mode) }
        compose.onNodeWithContentDescription("Enable equalizer").performScrollTo().assertIsOff()
    }

    @Test fun unavailableControllerCannotChangeProcessing() {
        compose.setContent { AppTheme { EqualizerSheet(EqualizerState(), false, null, { _, _ -> error("Disconnected output modified") }, {}) } }
        compose.onNodeWithContentDescription("Enable equalizer").assertIsNotEnabled()
    }
}
