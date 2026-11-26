package ua.olex.music.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.lyrics.LyricsStore
import ua.olex.music.lyrics.OnlineLyrics
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DownloadedLyricsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.filesDir, "lyrics")
    private val online = Track(TrackId("yt:one"), "ytm://one", "Song", "Artist", durationMs = 200_000)
    private val file = Track(TrackId("local:one"), "content://one", "Song", "Artist", durationMs = 200_000, path = "Music/song.flac")
    @Before fun create() { directory.deleteRecursively(); directory.mkdirs() }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun timingBelongsToTheRecordingAcrossBothSources() {
        val recording = SavedSong.from(online).copy(id = "recording:one")
        val store = DownloadedLyrics(context)
        store.saveEdited(recording.bind(file), "[00:01.00]Words")
        assertEquals("[00:01.00]Words", DownloadedLyrics(context).edited(recording.online()!!))
    }

    @Test fun legacyAndMergedEditsRemainReadableAndResetDoesNotResurrectThem() {
        val legacy = File(directory, LyricsStore.stem(file.key.value) + ".edited.lrc")
        legacy.writeText("[00:01.00]Legacy"); legacy.setLastModified(100)
        val alias = File(directory, LyricsStore.stem("older-recording") + ".edited.lrc")
        alias.writeText("[00:02.00]Alias"); alias.setLastModified(200)
        val track = file.copy(recordingId = "recording:one")
        val store = DownloadedLyrics(context)
        assertEquals("[00:02.00]Alias", store.edited(track, listOf("older-recording")))
        store.deleteEdited(track)
        assertNull(DownloadedLyrics(context).edited(track, listOf("older-recording")))
        assertTrue("An alias's only edit remains available to its original recording", alias.isFile)
    }

    @Test fun fetchedLegacyLyricsSurviveIdentityAssignment() {
        File(directory, LyricsStore.stem(file.key.value) + ".lrc").writeText("[00:01.00]Kept")
        val store = DownloadedLyrics(context)
        val track = file.copy(recordingId = "recording:one")
        assertEquals("[00:01.00]Kept", store.read(track)!!.first)
        store.delete(track)
        assertEquals("", store.read(track)!!.first)
    }

    @Test fun temporaryLookupFailureDoesNotPreventALaterRetry() = runBlocking {
        var available = false
        val store = DownloadedLyrics(context, OnlineLyrics { url ->
            if (!available) throw java.io.IOException("fixture")
            if ("lrclib.net/api/get" in url) """{"id":1,"syncedLyrics":"[00:01.00]Recovered"}""" else null
        })
        assertNull(store.fetch(file))
        available = true
        assertTrue(requireNotNull(store.fetch(file)).text.contains("Recovered"))
    }

    @Test fun removingLyricsDuringLookupKeepsTheRemovalMarker() = runBlocking {
        withTimeout(3_000) {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            val store = DownloadedLyrics(context, OnlineLyrics { url ->
                if ("lrclib.net/api/get" !in url) null else {
                    started.complete(Unit)
                    response.await()
                }
            })
            val lookup = async { store.fetch(file) }
            started.await()
            store.delete(file)
            response.complete("""{"id":1,"syncedLyrics":"[00:01.00]Do not resurrect"}""")
            assertNull(lookup.await())
            assertEquals("", store.read(file)?.first)
        }
    }

    @Test fun concurrentTimingWritesLeaveOneCompleteEdit() {
        val store = DownloadedLyrics(context)
        val track = file.copy(recordingId = "recording:one")
        val edits = (1..30).map { "[00:01.00]Edit $it " + "words ".repeat(100) }
        val writers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val work = edits.map { text -> writers.submit { store.saveEdited(track, text) } }
            work.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(DownloadedLyrics(context).edited(track) in edits)
        } finally { writers.shutdownNow() }
    }
}
