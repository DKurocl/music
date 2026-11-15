package ua.olex.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.model.SmartPlaylist
import ua.olex.music.model.SongStats
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmartPlaylistBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val songs = listOf(
        Track(TrackId("local:1"), "", "Creep", "Radiohead"),
        Track(TrackId("local:2"), "", "Karma Police", "Radiohead"),
    )
    private val lists = SmartPlaylist.BUILT_IN.map { p ->
        SmartPlaylistUi(p, p.evaluate(songs, { if (it.title == "Creep") SongStats(plays = 3) else SongStats.NONE }, { false }, now = 0))
    }

    @Test fun openPlayAndCreate() {
        var played: List<Track>? = null
        var saved: SmartPlaylist? = null
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            SmartPlaylistBrowser(lists, null, onPlay = { list, _ -> played = list }, onShuffle = {}, onTrackActions = {}, onSave = { saved = it }, onDelete = {})
        } } }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/reports/ui-previews/testDebugUnitTest").apply { mkdirs() }.resolve("smart.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Most played").performClick()
        compose.onNodeWithText("Play all").performClick()
        compose.runOnIdle { assertEquals(listOf("Creep"), played?.map { it.title }) }
        compose.onNodeWithText("Creep").assertExists()
    }

}

/** Text input in a dialog under custom window qualifiers never idles in Robolectric (issue 8460): default metrics here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartPlaylistEditorTest {
    @get:Rule val compose = createComposeRule()
    private val lists = SmartPlaylist.BUILT_IN.map { SmartPlaylistUi(it, emptyList()) }

    @Test fun editorSavesANamedRule() {
        var saved: SmartPlaylist? = null
        compose.setContent { AppTheme { Surface(Modifier.fillMaxSize()) {
            SmartPlaylistBrowser(lists, null, onPlay = { _, _ -> }, onShuffle = {}, onTrackActions = {}, onSave = { saved = it }, onDelete = {})
        } } }
        compose.onNodeWithText("New smart playlist").performClick()
        compose.onNodeWithText("Name").performTextInput("Rock")
        compose.onNodeWithText("Text").performTextInput("rock")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("Rock", saved?.name)
            assertEquals("rock", saved?.rules?.single()?.value)
        }
    }
}
