package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class OnlineSearchProjectionTest {
    @Test fun twoVideosCanShareADisplayedFileAndStillKeepTheirOwnQueueIntent() {
        val original = Track(TrackId("yt:one"), "ytm://one", "Song", "Artist")
        val other = original.copy(id = TrackId("yt:two"), uri = "ytm://two")
        val copy = original.copy(id = TrackId("local:copy"), uri = "content://copy")
        val search = OnlineSearch("Song", listOf(original, other)).project { _, _ -> copy }
        assertEquals(listOf(copy, copy), search.tracks)
        assertEquals(original, search.requestAt(0))
        assertEquals(other, search.requestAt(1))
        assertEquals(listOf(original, other), search.requestedTracks())
    }
}
