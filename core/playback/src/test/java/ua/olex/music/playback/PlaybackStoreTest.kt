package ua.olex.music.playback

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PlaybackStoreTest {
    private lateinit var directory: File
    @Before fun create() { directory = File(RuntimeEnvironment.getApplication().cacheDir, "playback-test"); directory.deleteRecursively(); directory.mkdirs() }
    @After fun delete() { directory.deleteRecursively() }

    private fun state(generation: String = "one") = PlaybackCheckpoint(generation,
        listOf(PlayerConnection.mediaItem(Track(TrackId("local:1"), "content://1", "Song", "Artist", durationMs = 180_000, path = "Music/song.flac"))),
        listOf(0), true, 2, 0, 45_000)

    @Test fun restoreRetainsQueueIdentityMetadataPositionAndModes() = runBlocking {
        val original = state()
        PlaybackStore(directory).write(original)
        val restored = requireNotNull(PlaybackStore(directory).read())
        assertEquals(original.items.single().mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY), restored.items.single().mediaMetadata.extras?.getString(PlayerConnection.QUEUE_KEY))
        assertEquals("Music/song.flac", restored.items.single().mediaMetadata.extras?.getString(PlayerConnection.PATH_KEY))
        assertEquals("content://1", restored.items.single().localConfiguration?.uri.toString())
        assertEquals(180_000L, restored.items.single().mediaMetadata.durationMs)
        assertEquals(45_000L, restored.positionMs)
        assertEquals(listOf(0), restored.shuffleOrder)
        assertEquals(2, restored.repeat)
        assertTrue(restored.shuffle)
    }

    @Test fun progressCheckpointDoesNotRewriteLargeQueue() = runBlocking {
        val store = PlaybackStore(directory)
        val first = state()
        store.write(first)
        val queue = File(directory, "playback-queue.json")
        val bytes = queue.readBytes()
        store.write(first.copy(positionMs = 90_000))
        assertArrayEquals(bytes, queue.readBytes())
        assertEquals(90_000L, PlaybackStore(directory).read()?.positionMs)
    }

    @Test fun interruptedTwoFileUpdateRejectsTheOldCursor() = runBlocking {
        val store = PlaybackStore(directory)
        store.write(state())
        val cursor = File(directory, "playback-position.json")
        val oldCursor = cursor.readBytes()
        store.write(state("replacement").copy(positionMs = 9_000))
        cursor.writeBytes(oldCursor)
        assertEquals(9_000L, PlaybackStore(directory).read()?.positionMs)
    }

    @Test fun corruptCursorFallsBackToQueueCheckpoint() = runBlocking {
        val store = PlaybackStore(directory)
        store.write(state())
        File(directory, "playback-position.json").writeText("broken")
        assertEquals(45_000L, PlaybackStore(directory).read()?.positionMs)
    }

    @Test fun savedEmptyQueueDoesNotResurrectPreviousMusic() = runBlocking {
        val store = PlaybackStore(directory)
        store.write(state())
        store.write(PlaybackCheckpoint("empty", emptyList(), emptyList(), false, 0, 0, 0))
        assertTrue(requireNotNull(PlaybackStore(directory).read()).items.isEmpty())
    }

    @Test fun unknownVersionIsRejectedWithoutDeletingState() = runBlocking {
        val file = File(directory, "playback-queue.json")
        file.writeText("{\"version\":999}")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { PlaybackStore(directory).read() } }
        assertEquals("{\"version\":999}", file.readText())
    }
}
