package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class SidecarLyricsTest {
    @Test fun onlyDescendantsOfTheChosenFolderAreAccessible() {
        assertEquals("Music/Artist", SidecarLyrics.directory("Music/Artist/song.flac", "Music"))
        for (path in listOf("Music2/song.flac", "Music/../Private/song.flac", "Music//song.flac", "Music/song.flac/"))
            assertNull(SidecarLyrics.directory(path, "Music"))
    }
    @Test fun timedSidecarsWinAndExactCaseBeatsAnAmbiguousFallback() {
        assertEquals("song.lrc", SidecarLyrics.choose("Music/song.flac", listOf("song.txt", "song.ttml", "song.LRC", "song.lrc")).name)
        assertEquals("song.LRC", SidecarLyrics.choose("Music/song.flac", listOf("song.LRC")).name)
        assertNull(SidecarLyrics.choose("Music/Song.flac", listOf("song.lrc")).name)
        assertTrue(SidecarLyrics.choose("Music/song.flac", listOf("song.LRC", "song.Lrc")).ambiguous)
    }
    @Test fun unicodeAndMultipleDotsRemainExact() {
        val name = "e\u0301.live.lrc"
        assertEquals(name, SidecarLyrics.choose("Music/e\u0301.live.flac", listOf(name, "é.live.lrc")).name)
        assertNull(SidecarLyrics.choose("Music/missing.flac", listOf("lyrics.txt")).name)
    }
}
