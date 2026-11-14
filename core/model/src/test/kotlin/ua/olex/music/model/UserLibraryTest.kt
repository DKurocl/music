package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class UserLibraryTest {
    private val original = Track(TrackId("local:1"), "content://1", "Song", "Artist", durationMs = 120_000, path = "Music/song.flac")

    @Test fun referenceSurvivesMediaStoreRenumbering() {
        val rescanned = original.copy(id = TrackId("local:900"), uri = "content://900")
        assertEquals(SavedSong.from(original).id, SavedSong.from(rescanned).id)
        assertEquals(rescanned, SavedSong.from(original).resolve(LibraryIndex.build(listOf(rescanned))))
    }

    @Test fun exactFileWinsOverHigherResolutionDuplicate() {
        val duplicate = original.copy(id = TrackId("local:2"), path = "Other/song.flac", format = AudioFormat("flac", 96_000, 24))
        assertEquals(original, SavedSong.from(original).resolve(LibraryIndex.build(listOf(duplicate, original))))
    }

    @Test fun movedFileUsesBestAvailableMatchingRecording() {
        val lossy = original.copy(id = TrackId("local:2"), path = "New/song.mp3", format = AudioFormat("mp3", bitrateKbps = 320))
        val lossless = original.copy(id = TrackId("local:3"), path = "New/song.flac", format = AudioFormat("flac"))
        assertEquals(lossless, SavedSong.from(original).resolve(LibraryIndex.build(listOf(lossy, lossless))))
    }

    @Test fun wrongDurationAndDifferentRecordingStayUnavailable() {
        val tooLong = original.copy(durationMs = 124_000)
        val live = original.copy(title = "Song (Live)")
        assertNull(SavedSong.from(original).resolve(LibraryIndex.build(listOf(tooLong, live))))
        assertNull(SavedSong.from(original).resolve(LibraryIndex.EMPTY))
    }

    @Test fun differentPathsRemainDistinctSavedFiles() {
        assertNotEquals(SavedSong.from(original).id, SavedSong.from(original.copy(path = "Copy/song.flac")).id)
    }
}
