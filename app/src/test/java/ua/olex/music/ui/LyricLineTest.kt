package ua.olex.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.lyrics.Lyrics
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricLineTest {
    @get:Rule val compose = createComposeRule()

    private val lyrics = Lyrics.parse(
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
        <p begin="1.000" end="4.000" ttm:agent="v1"><span begin="1.000" end="1.500">Is </span><span begin="1.500" end="2.000">this </span><span begin="2.000" end="2.400">the </span><span begin="2.400" end="3.200">real </span><span begin="3.200" end="4.000">life</span></p>
        <p begin="4.000" end="7.000" ttm:agent="v1"><span begin="4.000" end="5.000">Is </span><span begin="5.000" end="5.500">this </span><span begin="5.500" end="7.000">fantasy</span></p>
        <p begin="7.000" end="9.000" ttm:agent="v2"><span begin="7.000" end="8.000">Caught in a </span><span begin="8.000" end="9.000">landslide</span></p>
        </div></body></tt>""",
    )

    /** Mid-word in the first line: "Is this" sung, "the" filling, "real life" still dim. */
    @Test fun wordsFillAsTheyAreSung() {
        var seeked = -1
        compose.setContent {
            AppTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    lyrics.lines.forEachIndexed { i, line -> LyricLine(line, i, current = 0, now = { 2_200L }, timed = true) { seeked = i } }
                }
            } }
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/reports/ui-previews/testDebugUnitTest").apply { mkdirs() }.resolve("lyrics-words.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Is this fantasy").performClick()
        compose.runOnIdle { assertEquals(1, seeked) }
    }

    @Test fun changingWordTimeRedrawsWithoutRecomposingTheParent() {
        val clock = androidx.compose.runtime.mutableLongStateOf(1_200L)
        var compositions = 0
        compose.setContent {
            AppTheme { Surface(Modifier.fillMaxSize()) {
                androidx.compose.runtime.SideEffect { compositions++ }
                LyricLine(lyrics.lines.first(), 0, 0, { clock.longValue }, true) { }
            } }
        }
        val before = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        var settled = 0
        compose.runOnIdle { settled = compositions; clock.longValue = 3_800 }
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        org.junit.Assert.assertFalse(before.sameAs(after))
        compose.runOnIdle { assertEquals(settled, compositions) }
    }

    @Test fun aPlayingWordClockFollowsFramesAndChangesTheSelectedLine() {
        compose.mainClock.autoAdvance = false
        var position = 1_200L
        compose.setContent { AppTheme {
            InlineLyrics(lyrics, ua.olex.music.playback.PlayerState(mediaId = "yt:clock", isPlaying = true), 0,
                { position }, {}, {}, null, null, Modifier.fillMaxSize())
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Is this the real life").assertIsSelected()
        compose.runOnUiThread { position = 4_500 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Is this fantasy").assertIsSelected()
    }
}
