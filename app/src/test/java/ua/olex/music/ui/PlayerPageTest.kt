package ua.olex.music.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.playback.PlayerState
import ua.olex.music.playback.RepeatMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerPageTest {
    @get:Rule val compose = createComposeRule()
    private val state = PlayerState(connected = true, mediaId = "local:1", title = "Everything in Its Right Place", artist = "Radiohead", durationMs = 251_000,
        playWhenReady = true, hasNext = true, shuffle = true, repeat = RepeatMode.ALL)

    @Test fun transportAndPrimaryActionsAreReachable() {
        var action = ""
        compose.setContent { AppTheme {
            PlayerPage(state, "FLAC 24/96", null, false, { 42_000 }, {}, { action = "pause" }, {}, {}, {}, {}, {}, {}, {},
                { action = "output" }, { action = "queue" }, { action = "lyrics" }, {}, {})
        } }
        capture("player-light")
        compose.onNodeWithContentDescription("Pause").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("pause", action) }
        compose.onNodeWithContentDescription("Change output").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("output", action) }
        compose.onNodeWithContentDescription("Lyrics").performClick()
        compose.runOnIdle { assertEquals("lyrics", action) }
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun largeFontKeepsControlsAccessibleInDarkTheme() {
        var favourite = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { AppTheme {
                PlayerPage(state.copy(title = "A long title that needs room to remain readable"), "FLAC 24/96", null, false, { 0 }, {}, {}, {}, {}, {}, {}, {}, { favourite = true }, {}, {}, {}, {}, {}, {})
            } }
        }
        capture("player-dark-font2")
        compose.onNodeWithContentDescription("Add to favourites").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, favourite) }
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/reports/ui-previews/${System.getProperty("music.testTask", "testDebugUnitTest")}/$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
