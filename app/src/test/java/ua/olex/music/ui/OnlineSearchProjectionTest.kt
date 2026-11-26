package ua.olex.music.ui

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class OnlineSearchProjectionTest {
    @Test fun offlineHiddenAndReauthenticatedSearchCannotKeepAnOldRequestRunning() = runBlocking {
        withTimeout(5_000) {
            val query = MutableStateFlow("Miles")
            val owner = MutableStateFlow<Long?>(null)
            val events = Channel<String>(Channel.UNLIMITED)
            val collector = launch {
                query.searchWhileOwned(owner, "inactive") { text ->
                    events.send("start:$text")
                    try { awaitCancellation() } finally { events.trySend("stop:$text") }
                }.collect { events.send(it) }
            }
            assertEquals("inactive", events.receive())
            owner.value = 1L
            assertEquals("start:Miles", events.receive())
            owner.value = 2L
            assertEquals("stop:Miles", events.receive())
            assertEquals("start:Miles", events.receive())
            query.value = "Nina"
            assertEquals("stop:Miles", events.receive())
            assertEquals("start:Nina", events.receive())
            owner.value = null
            assertEquals("stop:Nina", events.receive())
            assertEquals("inactive", events.receive())
            collector.cancelAndJoin()
        }
    }

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
