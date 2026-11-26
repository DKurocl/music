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

    @Test fun restoredUnavailableItemKeepsItsOriginalIntentAndCannotOpenBeforeResolution() = runBlocking {
        val track = Track(TrackId("yt:offline"), "ytm://offline", "Song", "Artist")
        val original = PlayerConnection.mediaItem(track)
        val unavailable = PlaybackSourceItems.convert(original,
            ua.olex.music.model.PlaybackSourceChoice(track, ua.olex.music.model.SourceIssue.OFFLINE))
        PlaybackStore(directory).write(state().copy(items = listOf(unavailable)))
        val restored = requireNotNull(PlaybackStore(directory).read()).items.single()
        assertEquals(unavailable.localConfiguration?.uri, restored.localConfiguration?.uri)
        assertEquals(track.uri, restored.requestMetadata.mediaUri.toString())
        assertEquals("yt:offline", PlayerConnection.track(restored).requestedId)
        assertNotNull(PlaybackSourceItems.error(restored))
    }

    @Test fun aReadBarrierSeesTheLatestStateFromAnEnqueuedWriteBurst() = runBlocking {
        val journal = PlaybackJournal(PlaybackStore(directory))
        try {
            repeat(1_000) { journal.write(state().copy(positionMs = it.toLong())) }
            assertEquals(999L, journal.read()?.positionMs)
            journal.write(state("new").copy(positionMs = 5_000))
            assertEquals("new", journal.read()?.generation)
            assertEquals(5_000L, journal.read()?.positionMs)
        } finally {
            journal.close()
        }
    }

    @Test fun playingContextSurvivesRestartAndAnOlderCheckpointWithoutItStillLoads() = runBlocking {
        val item = PlayerConnection.mediaItem(Track(TrackId("yt:one"), "ytm://one", "Song", "Artist"), playbackContext = "Evening playlist")
        PlaybackStore(directory).write(state().copy(items = listOf(item)))
        assertEquals("Evening playlist", PlaybackStore(directory).read()!!.items.single().mediaMetadata.extras?.getString(PlayerConnection.CONTEXT_KEY))
        val file = File(directory, "playback-queue.json")
        val data = org.json.JSONObject(file.readText())
        data.getJSONArray("items").getJSONObject(0).remove("context")
        file.writeText(data.toString())
        assertNull(PlaybackStore(directory).read()!!.items.single().mediaMetadata.extras?.getString(PlayerConnection.CONTEXT_KEY))
    }

    @Test fun replayGainTagsSurviveQueueCheckpointAndOldQueuesStayReadable() = runBlocking {
        val item = PlayerConnection.mediaItem(Track(TrackId("local:gain"), "content://gain", "Gain", "Artist",
            metadata = ua.olex.music.model.FileMetadata(trackGainDb = -6f, albumGainDb = -4f)))
        val checkpoint = state().copy(items = listOf(item))
        PlaybackStore(directory).write(checkpoint)
        val restored = requireNotNull(PlaybackStore(directory).read()).items.single()
        assertEquals(-6.0, ReplayGainCodec.tags(restored.mediaMetadata.extras?.getString(ReplayGainCodec.TAGS)).trackGainDb)
        val file = File(directory, "playback-queue.json")
        val data = org.json.JSONObject(file.readText())
        data.getJSONArray("items").getJSONObject(0).remove("replayGain")
        file.writeText(data.toString())
        val legacy = requireNotNull(PlaybackStore(directory).read()).items.single()
        assertEquals(ua.olex.music.dsp.ReplayGainTags(), ReplayGainCodec.tags(legacy.mediaMetadata.extras?.getString(ReplayGainCodec.TAGS)))
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
        val original = state()
        store.write(original)
        store.write(original.copy(positionMs = 83_000))
        val cursor = File(directory, "playback-position.json")
        val oldCursor = cursor.readBytes()
        store.write(state("replacement").copy(positionMs = 9_000))
        assertArrayEquals(oldCursor, cursor.readBytes())
        assertEquals(9_000L, PlaybackStore(directory).read()?.positionMs)
    }

    @Test fun firstPositionLivesInTheQueueAndUnchangedPositionsDoNotRewriteTheCursor() = runBlocking {
        val store = PlaybackStore(directory)
        val original = state()
        val cursor = File(directory, "playback-position.json")
        store.write(original)
        store.write(original)
        assertFalse(cursor.exists())
        assertEquals(45_000L, PlaybackStore(directory).read()?.positionMs)

        val progress = original.copy(positionMs = 46_000)
        store.write(progress)
        assertTrue(cursor.setLastModified(10_000))
        val stamp = cursor.lastModified()
        store.write(progress)
        assertEquals(stamp, cursor.lastModified())
        assertEquals(46_000L, PlaybackStore(directory).read()?.positionMs)
        store.write(progress.copy(positionMs = 30_000))
        assertEquals(30_000L, PlaybackStore(directory).read()?.positionMs)
    }

    @Test fun oversizedCursorCannotOverrideTheDurableQueuePosition() = runBlocking {
        val store = PlaybackStore(directory)
        store.write(state())
        File(directory, "playback-position.json").writeText(
            """{"generation":"one","index":0,"position":90000}""".padEnd(2_048, ' '),
        )
        assertEquals(45_000L, store.read()?.positionMs)
    }

    @Test fun aFailedCursorWriteCanRetryTheSamePosition() = runBlocking {
        val store = PlaybackStore(directory)
        val original = state()
        store.write(original)
        val progress = original.copy(positionMs = 90_000)
        val blocked = File(directory, "playback-position.json.new")
        assertTrue(blocked.mkdir())
        assertThrows(java.io.IOException::class.java) { runBlocking { store.write(progress) } }
        assertEquals(45_000L, store.read()?.positionMs)
        blocked.delete()
        store.write(progress)
        assertEquals(90_000L, PlaybackStore(directory).read()?.positionMs)
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

    @Test fun recreatedServiceReadWaitsForPreviousServicesFinalWrite() = runBlocking {
        val journal = PlaybackJournal(PlaybackStore(directory))
        try {
            journal.write(state())
            journal.write(state().copy(positionMs = 99_000))
            assertEquals(99_000L, journal.read()?.positionMs)
            journal.write(state("new-queue").copy(positionMs = 8_000))
            assertEquals("new-queue", journal.read()?.generation)
            assertEquals(8_000L, journal.read()?.positionMs)
        } finally { journal.close() }
    }

    @Test fun recordingAndReserveSurviveCheckpointWhileLegacyItemsRemainReadable() = runBlocking {
        val online = Track(TrackId("yt:reserve"), "ytm://reserve", "Song", "Artist", durationMs = 180_000, artworkUri = "https://cover")
        val file = Track(TrackId("local:one"), "content://one", "Song", "Artist", durationMs = 180_000, path = "Music/song.flac")
        val linked = ua.olex.music.model.SavedSong.from(online).copy(id = "recording:one").bind(file)
        val item = PlayerConnection.mediaItem(linked, "occurrence")
        PlaybackStore(directory).write(state().copy(items = listOf(item)))
        val restored = PlayerConnection.track(PlaybackStore(directory).read()!!.items.single())
        assertEquals("recording:one", restored.recordingId)
        assertEquals(linked.sources, restored.sources)
        assertEquals("ytm://reserve", ua.olex.music.model.SavedSong.from(restored).online()!!.uri)
        val jsonFile = File(directory, "playback-queue.json")
        val data = org.json.JSONObject(jsonFile.readText())
        data.getJSONArray("items").getJSONObject(0).apply { remove("recording"); remove("sources") }
        jsonFile.writeText(data.toString())
        val legacy = PlayerConnection.track(PlaybackStore(directory).read()!!.items.single())
        assertNull(legacy.recordingId); assertTrue(legacy.sources.isEmpty())
        assertEquals(file.uri, legacy.uri)
    }

}
