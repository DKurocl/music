package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongKeyTest {
    private fun key(artist: String, title: String) = SongKey.of(artist, title)

    @Test fun caseAccentsAndPunctuationDoNotMatter() {
        assertEquals(key("Beyoncé", "Déjà Vu"), key("BEYONCE", "deja vu"))
        assertEquals(key("AC/DC", "T.N.T."), key("ac/dc", "T N T"))
        assertEquals("a title that starts with 'with' survives", key("U2", "with or without you"), key("U2", "With or Without You"))
        assertNotEquals(key("U2", "With or Without You"), key("U2", "One"))
        assertEquals(key("Океан Ельзи", "Обійми"), key("океан ельзи", "ОБІЙМИ"))
    }

    @Test fun featuredArtistsAreDropped() {
        assertEquals(key("Daft Punk", "Get Lucky"), key("Daft Punk feat. Pharrell Williams", "Get Lucky"))
        assertEquals(key("Daft Punk", "Get Lucky"), key("Daft Punk", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)"))
        assertEquals(key("Daft Punk", "Get Lucky"), key("Daft Punk, Pharrell Williams", "Get Lucky [ft. Pharrell]"))
    }

    @Test fun editionNotesAreDropped() {
        assertEquals(key("Queen", "Bohemian Rhapsody"), key("Queen", "Bohemian Rhapsody (Remastered 2011)"))
        assertEquals(key("Queen", "Bohemian Rhapsody"), key("Queen", "Bohemian Rhapsody - 2011 Remaster"))
        assertEquals(key("Queen", "Bohemian Rhapsody"), key("Queen", "Bohemian Rhapsody (Official Video)"))
    }

    @Test fun differentRecordingsStayDifferent() {
        assertNotEquals(key("Nirvana", "About a Girl"), key("Nirvana", "About a Girl (Live)"))
        assertNotEquals(key("The Weeknd", "Blinding Lights"), key("The Weeknd", "Blinding Lights (Chromatics Remix)"))
        assertNotEquals(key("Radiohead", "Creep"), key("Radiohead", "Creep - Acoustic"))
    }

    @Test fun sameSongNeedsMatchingLength() {
        fun track(id: String, ms: Long) = Track(TrackId(id), "", "Creep", "Radiohead", durationMs = ms)
        assertTrue(SongKey.sameSong(track("local:1", 238_000), track("yt:x", 239_500)))
        assertFalse(SongKey.sameSong(track("local:1", 238_000), track("yt:x", 262_000)))
        assertTrue("an unknown length does not rule a match out", SongKey.sameSong(track("local:1", 238_000), track("sc:9", 0)))
    }

    @Test fun formatLabelsAndRanks() {
        val hires = AudioFormat("flac", 96_000, 24)
        val cd = AudioFormat("flac", 44_100, 16)
        val mp3 = AudioFormat("mp3", 44_100, bitrateKbps = 320)
        assertEquals("FLAC 24/96", hires.label)
        assertEquals("FLAC 16/44.1", cd.label)
        assertEquals("MP3 320", mp3.label)
        assertTrue(hires.hiRes); assertFalse(cd.hiRes)
        assertTrue(hires.rank > cd.rank && cd.rank > mp3.rank)
    }

    @Test fun trackIds() {
        val id = TrackId.of(Origin.YOUTUBE, "dQw4w9WgXcQ")
        assertEquals("yt:dQw4w9WgXcQ", id.value)
        assertEquals(Origin.YOUTUBE, id.origin)
        assertEquals("dQw4w9WgXcQ", id.raw)
    }
}
