package ua.olex.music.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryBrowserTest {
    @get:Rule val compose = createComposeRule()
    private val opening = Track(TrackId("local:1"), "", "Z Opening", "Singer", album = "Record", trackNumber = 1)
    private val finale = Track(TrackId("local:2"), "", "A Finale", "Singer", album = "Record", trackNumber = 2)
    private val library = LibraryIndex.build(listOf(finale, opening))

    @Test fun albumPlayAllUsesTrackOrderRatherThanSongTitleOrder() {
        var played = emptyList<Track>()
        var start = -1
        compose.setContent {
            BrowserTestTheme { LibraryBrowser(library, PaddingValues(), "", null, {}, null, { tracks, index -> played = tracks; start = index }, null, {}) }
        }
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("Record").performClick()
        capture("album")
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
            BrowserTestTheme { LibraryBrowser(library, PaddingValues(), "", null, {}, null, { tracks, index -> played = tracks; start = index }, null, {}) }
        }
        compose.onNodeWithText("Z Opening").performClick()
        capture("songs")
        compose.runOnIdle {
            assertEquals(listOf(finale, opening), played)
            assertEquals(1, start)
        }
    }

    @Test fun sortedSongTapUsesTheDisplayedOrderAndIndex() {
        val order = mutableStateOf(ua.olex.music.model.TrackSortOrder())
        val long = opening.copy(durationMs = 3_000)
        val short = finale.copy(durationMs = 1_000)
        val index = LibraryIndex.build(listOf(short, long))
        var played = emptyList<Track>()
        var start = -1
        compose.setContent { BrowserTestTheme {
            LibraryBrowser(index, PaddingValues(), "", null, {}, null, { tracks, selected -> played = tracks; start = selected }, null, {},
                sortOrders = mapOf(ua.olex.music.model.TrackSortScope.SONGS to order.value), onSortOrder = { _, value -> order.value = value })
        } }
        compose.onNodeWithText("Sort: Default").performClick()
        compose.onNodeWithText("Duration", substring = false).performClick()
        compose.onNodeWithText("Sort: Duration").performClick()
        compose.onNodeWithText("Descending", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A Finale").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A Finale").performClick()
        compose.runOnIdle {
            assertEquals(listOf(long, short), played)
            assertEquals(1, start)
        }
    }

    @Test fun selectedTracksQueueInVisibleOrderWithoutPlayingOnSelection() {
        var queued = emptyList<Track>()
        var next = false
        compose.setContent { BrowserTestTheme {
            LibraryBrowser(library, PaddingValues(), "", null, {}, null, { _, _ -> error("Selection started playback") }, null, {},
                onEnqueue = { tracks, playNext -> queued = tracks; next = playNext })
        } }
        compose.onNodeWithText("Z Opening").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select A Finale").performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithContentDescription("Play next").performClick()
        compose.runOnIdle { assertEquals(listOf(finale, opening), queued); assertEquals(true, next) }
    }

    @Test fun staleSearchResultsCannotBePlayedUnderANewQuery() {
        compose.setContent {
            BrowserTestTheme {
                LibraryBrowser(library, PaddingValues(), "new query", LibrarySearchResult("old query", listOf(opening)), {}, null, { _, _ -> error("Stale result played") }, null, {})
            }
        }
        compose.onNodeWithText("Searching…").assertIsDisplayed()
        compose.onNodeWithText("Z Opening").assertDoesNotExist()
    }

    @Test fun searchTapQueuesOnlyTheMatches() {
        var played = emptyList<Track>()
        compose.setContent {
            BrowserTestTheme {
                LibraryBrowser(library, PaddingValues(), "opening", LibrarySearchResult("opening", listOf(opening)), {}, null, { tracks, _ -> played = tracks }, null, {})
            }
        }
        compose.onNodeWithText("Z Opening").performClick()
        compose.runOnIdle { assertEquals(listOf(opening), played) }
    }

    @Test fun failedSearchCanBeRetriedWithoutEditingTheQuery() {
        var retries = 0
        compose.setContent {
            BrowserTestTheme {
                LibraryBrowser(library, PaddingValues(), "opening", LibrarySearchResult("opening", emptyList(), failed = true), {}, null, { _, _ -> }, null, {}, { retries++ })
            }
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun queryCanBeEnteredAndClearedWithoutChangingTheLibrary() {
        val query = mutableStateOf("")
        compose.setContent {
            BrowserTestTheme { LibraryBrowser(library, PaddingValues(), query.value, null, { query.value = it }, null, { _, _ -> }, null, {}) }
        }
        compose.onNodeWithText("Search your music").performTextInput("Singer")
        compose.onNodeWithText("Searching…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear").performClick()
        compose.onNodeWithText("Z Opening").assertIsDisplayed()
        compose.onNodeWithText("A Finale").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun collectionActionsRemainReachableWithLargeTextInDarkMode() {
        var played = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                BrowserTestTheme { LibraryBrowser(library, PaddingValues(), "", null, {}, null, { _, _ -> played = true }, null, {}) }
            }
        }
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Play all").performScrollTo().assertIsDisplayed().performClick()
        capture("album-dark-font2")
        compose.runOnIdle { assertEquals(true, played) }
    }

    private fun capture(name: String) {
        // These are actual test renders for human review, not approved screenshot goldens.
        val directory = File("build/reports/ui-previews/${System.getProperty("music.testVariant", "test")}").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@Composable
private fun BrowserTestTheme(content: @Composable () -> Unit) {
    AppTheme { androidx.compose.runtime.CompositionLocalProvider(LocalStartTab provides "SONGS") { Surface(Modifier.fillMaxSize(), content = content) } }
}
