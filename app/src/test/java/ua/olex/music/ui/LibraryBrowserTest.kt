package ua.olex.music.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val opening = Track(TrackId("local:1"), "", "Z Opening", "Singer", album = "Record", trackNumber = 1)
    private val finale = Track(TrackId("local:2"), "", "A Finale", "Singer", album = "Record", trackNumber = 2)
    private val library = LibraryIndex.build(listOf(finale, opening))

    @Test fun albumPlayAllUsesTrackOrderRatherThanSongTitleOrder() {
        var played = emptyList<Track>()
        var start = -1
        compose.setContent {
            AppTheme { LibraryBrowser(library, PaddingValues(), "", null, {}, null, { tracks, index -> played = tracks; start = index }, null, {}) }
        }
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Play all").performClick()
        compose.runOnIdle {
            assertEquals(listOf(opening, finale), played)
            assertEquals(0, start)
        }
    }

    @Test fun songTapStartsAtItsIndexInTheVisibleQueue() {
        var played = emptyList<Track>()
        var start = -1
        compose.setContent {
            AppTheme { LibraryBrowser(library, PaddingValues(), "", null, {}, null, { tracks, index -> played = tracks; start = index }, null, {}) }
        }
        compose.onNodeWithText("Z Opening").performClick()
        compose.runOnIdle {
            assertEquals(listOf(finale, opening), played)
            assertEquals(1, start)
        }
    }

    @Test fun staleSearchResultsCannotBePlayedUnderANewQuery() {
        compose.setContent {
            AppTheme {
                LibraryBrowser(library, PaddingValues(), "new query", LibrarySearchResult("old query", listOf(opening)), {}, null, { _, _ -> error("Stale result played") }, null, {})
            }
        }
        compose.onNodeWithText("Searching…").assertIsDisplayed()
        compose.onNodeWithText("Z Opening").assertDoesNotExist()
    }

    @Test fun searchTapQueuesOnlyTheMatches() {
        var played = emptyList<Track>()
        compose.setContent {
            AppTheme {
                LibraryBrowser(library, PaddingValues(), "opening", LibrarySearchResult("opening", listOf(opening)), {}, null, { tracks, _ -> played = tracks }, null, {})
            }
        }
        compose.onNodeWithText("Z Opening").performClick()
        compose.runOnIdle { assertEquals(listOf(opening), played) }
    }

    @Test fun queryCanBeEnteredAndClearedWithoutChangingTheLibrary() {
        val query = mutableStateOf("")
        compose.setContent {
            AppTheme { LibraryBrowser(library, PaddingValues(), query.value, null, { query.value = it }, null, { _, _ -> }, null, {}) }
        }
        compose.onNodeWithText("Search your music").performTextInput("Singer")
        compose.onNodeWithText("Searching…").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Z Opening").assertIsDisplayed()
        compose.onNodeWithText("A Finale").assertIsDisplayed()
    }
}
