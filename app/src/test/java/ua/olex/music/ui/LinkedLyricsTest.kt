package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class LinkedLyricsTest {
    @Test fun compatibleLinkedFilesCanSupplyLyricsButNamesakesAndClipsCannot() {
        val video = Track(TrackId("yt:one"), "ytm://one", "Song", "Artist", durationMs = 180_000)
        val file = video.copy(id = TrackId("local:file"), path = "Music/song.flac", uri = "content://file")
        val linked = SavedSong.from(SavedSong.from(video).bind(file))
        val index = LibraryIndex.build(listOf(file))
        assertEquals(file.id, linkedLyricFile(linked, video, index)?.id)
        assertNull(linkedLyricFile(linked, video.copy(uri = "ytm://two"), index))
        assertNull(linkedLyricFile(linked, video.copy(durationMs = 180_600), index))
        assertNull(linkedLyricFile(linked, video, LibraryIndex.build(listOf(file.copy(path = "Music/namesake.flac")))))
        assertNull(linkedLyricFile(linked, video, LibraryIndex.build(listOf(file.copy(clipStartMs = 1_000)))))
    }
}
