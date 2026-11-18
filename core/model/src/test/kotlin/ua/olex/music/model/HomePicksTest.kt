package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePicksTest {
    @Test fun familiarAndProviderOrderShareOneQueueIncludingOfflineLinkedCopies() {
        val a = Track(TrackId("a"), "file:///a", "Take", "Band")
        val stream = a.copy(id = TrackId("yt:x"), uri = "ytm://x")
        val file = a.copy(id = TrackId("b"), uri = "file:///b", sources = listOf(RecordingSource.from(stream)!!))
        val other = stream.copy(id = TrackId("yt:y"), uri = "ytm://y", title = "Other")
        val hidden = other.copy(id = TrackId("yt:z"))
        fun picks(online: Boolean) = HomePicks.select(listOf(a, file), listOf(stream, other, hidden), emptyList(),
            listOf(a), mapOf(a.statsKey to SongStats(plays = 2)), 1, { online || !it.uri.startsWith("ytm://") }, { it.id == hidden.id })
        assertEquals(setOf(a, file, other), picks(true).toSet())
        assertEquals(3, picks(true).size)
        assertEquals(setOf(a, file), picks(false).toSet())
    }
}
