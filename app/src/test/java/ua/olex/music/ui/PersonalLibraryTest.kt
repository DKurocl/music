package ua.olex.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalLibraryTest {
    @get:Rule val compose = createComposeRule()
    private val song = Track(TrackId("local:1"), "content://1", "Available song", "Artist", path = "Music/1.flac")
    private val missing = SavedSong.from(song.copy(id = TrackId("local:2"), title = "Missing song", path = "Music/2.flac"))
    private val playlist = PlaylistUi(7, "Evening", listOf(SavedSongRow(missing, null), SavedSongRow(SavedSong.from(song), song)))
    private fun actions(create: (String, List<Track>) -> Unit = { _, _ -> }, delete: (Long) -> Unit = {}) =
        PersonalLibraryActions(create, { _, _ -> }, delete, { _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

    @Test fun playlistCreateRequiresANameAndTrimsIt() {
        var created: String? = null
        compose.setContent { MaterialTheme { Surface { PlaylistBrowser(emptyList(), actions(create = { name, _ -> created = name }), currentId = null, onPlay = { _, _ -> }, onTrackActions = {}) } } }
        compose.onNodeWithText("New playlist").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Playlist name").performTextInput("  Travel  ")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("Travel", created) }
    }

    @Test fun unavailableSongsStayVisibleButNeverEnterPlayAllQueue() {
        var played = emptyList<Track>()
        compose.setContent { MaterialTheme { Surface { PlaylistBrowser(listOf(playlist), actions(), currentId = null, onPlay = { tracks, _ -> played = tracks }, onTrackActions = {}) } } }
        compose.onNodeWithText("Evening").performClick()
        compose.onNodeWithText("Missing song").assertIsDisplayed()
        compose.onNodeWithText("Play all").performClick()
        compose.runOnIdle { assertEquals(listOf(song), played) }
    }

    @Test fun playlistDeletionRequiresExplicitConfirmation() {
        var deleted: Long? = null
        compose.setContent { MaterialTheme { Surface { PlaylistBrowser(listOf(playlist), actions(delete = { deleted = it }), currentId = null, onPlay = { _, _ -> }, onTrackActions = {}) } } }
        compose.onNodeWithText("Evening").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(7L, deleted) }
    }
}
