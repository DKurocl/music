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
import ua.olex.music.lyrics.Lyrics
import ua.olex.music.playback.PlayerState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun timedLineSeeksWithTheSavedOffsetAndAdjustmentCanBeReset() {
        var seek = -1L
        var offset = -1L
        compose.setContent { AppTheme {
            LyricsSheet(Lyrics.parse("[00:01]First line\n[00:10]Second line"), PlayerState(mediaId = "local:1"), 200, { 0 }, { seek = it }, { offset = it }, {})
        } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Second line"))
        compose.onNodeWithText("Second line").performClick()
        compose.runOnIdle { assertEquals(9_800L, seek) }
        compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        compose.onNodeWithText("Earlier").performClick()
        compose.runOnIdle { assertEquals(400L, offset) }
        compose.onNodeWithText("Offset 200 ms").performClick()
        compose.runOnIdle { assertEquals(0L, offset) }
    }
    @Test fun plainTextDoesNotOfferTimingControls() {
        compose.setContent { AppTheme {
            LyricsSheet(Lyrics.parse("Words without timing"), PlayerState(), 0, { 0 }, {}, {}, {})
        } }
        compose.onNodeWithText("Words without timing").assertExists()
        compose.onNodeWithText("Earlier").assertDoesNotExist()
    }
    @Test fun emptyLyricsOfferExplicitFolderSelectionWithoutStartingPlayback() {
        var chosen = false
        compose.setContent { AppTheme {
            LyricsSheet(Lyrics.EMPTY, PlayerState(mediaId = "local:1"), 0, { 0 }, {}, {}, {},
                source = "No local lyrics", note = "Choose a music folder.", onChooseFolder = { chosen = true })
        } }
        compose.onNodeWithText("Choose music folder").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(chosen) }
    }

}
