package ua.olex.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PlayerConnectionTest {
    @Test fun constructionDoesNotBindToAService() {
        val connection = PlayerConnection(RuntimeEnvironment.getApplication())
        assertEquals(PlayerState(), connection.state.value)
        assertEquals(0L, connection.positionMs)
        connection.disconnect()
        connection.disconnect()
        assertEquals(PlayerState(), connection.state.value)
    }

    @Test fun invalidQueueDoesNotStartAConnection() {
        val connection = PlayerConnection(RuntimeEnvironment.getApplication())
        connection.play(emptyList(), 0)
        connection.play(listOf(track()), -1)
        connection.play(listOf(track()), 1)
        assertFalse(connection.state.value.connecting)
    }

    @Test fun mediaItemCarriesThePlayableUriAndKnownMetadataAcrossTheSession() {
        val item = PlayerConnection.mediaItem(track())
        assertEquals("local:42", item.mediaId)
        assertEquals("content://media/external/audio/media/42", item.requestMetadata.mediaUri.toString())
        assertEquals(item.requestMetadata.mediaUri, item.localConfiguration?.uri)
        assertEquals("Song", item.mediaMetadata.title)
        assertEquals(120_000L, item.mediaMetadata.durationMs)
        assertEquals(2, item.mediaMetadata.discNumber)
        assertNull(item.mediaMetadata.artworkUri)
    }

    private fun track() = Track(
        TrackId("local:42"), "content://media/external/audio/media/42", "Song", "Artist",
        durationMs = 120_000, discNumber = 2,
    )

    @Test fun repeatedSongHasDistinctQueueOccurrencesAndKeepsDurablePath() {
        val song = track().copy(path = "Music/song.flac")
        val first = PlayerConnection.mediaItem(song)
        val second = PlayerConnection.mediaItem(song)
        assertEquals(first.mediaId, second.mediaId)
        assertNotEquals(first.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY), second.mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY))
        assertEquals(song.path, first.mediaMetadata.extras?.getString(PlayerConnection.PATH_KEY))
    }

    @Test fun radioReceiptAcceptsTheExactOccurrenceAfterFastRefill() {
        val seed = QueueItem("seed occurrence", "same track", "", "", null)
        val tail = QueueItem("new occurrence", "same track", "", "", null)
        val state = PlayerState(queue = listOf(seed, tail))
        assertTrue(state.acknowledgesRadio(seed.key))
        assertFalse(state.acknowledgesRadio(seed.mediaId))
        assertFalse(state.copy(queue = listOf(tail)).acknowledgesRadio(seed.key))
    }
}
