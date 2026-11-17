package ua.olex.music.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.AppGraph
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackLyricsTest {
    @Test fun editedOnlineLyricsWinWithoutLookingForALocalFileOrMakingANetworkRequest() = runBlocking {
        val graph = AppGraph(RuntimeEnvironment.getApplication())
        val track = Track(TrackId("yt:edited"), "ytm://edited", "Shared timing fixture", "Artist")
        try {
            graph.downloadedLyrics.saveEdited(track, "[00:01.25]Edited words")
            val result = graph.resolveLyrics(track)
            assertTrue(result.edited)
            assertEquals("Your timing", result.source)
            assertEquals("Edited words", result.lyrics.lines.single().text)
            assertEquals(1_250L, result.lyrics.lines.single().startMs)
        } finally { graph.downloadedLyrics.deleteEdited(track) }
    }
}
