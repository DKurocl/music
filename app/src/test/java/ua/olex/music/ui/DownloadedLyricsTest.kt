package ua.olex.music.ui

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
}
