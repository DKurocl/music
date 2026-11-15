package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueSheetTest {
    private val cue = """
        REM GENRE "Jazz"
        REM DATE 1959
        PERFORMER "Quintet"
        TITLE "Album"
        FILE "album.flac" WAVE
          TRACK 01 AUDIO
            TITLE "First"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Second"
            PERFORMER "Guest"
            INDEX 00 03:58:00
            INDEX 01 04:00:37
          track 03 audio
            title "Third"
            index 01 09:10:00
    """.trimIndent()

    private val file = Track(TrackId("local:9"), "content://9", "album", "Unknown", durationMs = 15 * 60_000L)

    @Test fun parsesTracksAndAlbumFields() {
        val sheet = CueSheet.parse(cue)!!
        assertEquals("Album", sheet.title)
        assertEquals("Quintet", sheet.performer)
        assertEquals(3, sheet.tracks.size)
        assertEquals(240_493, sheet.tracks[1].startMs) // 37 frames of 1/75 s, INDEX 00 ignored
    }

    @Test fun splitsTheFileIntoClippedTracks() {
        val tracks = CueSheet.parse(cue)!!.split(file)
        assertEquals(listOf("local:9#1", "local:9#2", "local:9#3"), tracks.map { it.id.value })
        assertEquals(listOf("Quintet", "Guest", "Quintet"), tracks.map { it.artist })
        assertEquals(0L, tracks[0].clipStartMs); assertEquals(240_493L, tracks[0].clipEndMs)
        assertEquals(550_000L, tracks[2].clipStartMs); assertEquals(900_000L, tracks[2].clipEndMs)
        assertEquals(1959, tracks[0].year)
        assertEquals("Jazz", tracks[0].genre)
        assertEquals("Album", tracks[0].album)
    }

    @Test fun libraryIndexShowsTheTracksInsteadOfTheFile() {
        val withCue = file.copy(metadata = FileMetadata(cueSheet = cue))
        val index = LibraryIndex.build(listOf(withCue))
        assertEquals(3, index.songs.size)
        assertEquals(1, index.albums.size)
        assertEquals(listOf("First", "Second", "Third"), index.albums[0].tracks.map { it.title })
    }

    @Test fun oneTrackOrNoneIsNotACueSheet() {
        assertNull(CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:00:00"))
        assertNull(CueSheet.parse("garbage"))
    }
}

class YearsAndComposersTest {
    @Test fun yearsNewestFirstAndComposersByName() {
        val a = Track(TrackId("a"), "", "One", "X", album = "R1", year = 1990, metadata = FileMetadata(composers = listOf("Bach", "Händel")))
        val b = Track(TrackId("b"), "", "Two", "X", album = "R2", year = 2001, metadata = FileMetadata(composers = listOf("bach")))
        val c = Track(TrackId("c"), "", "Three", "X", album = "R2", year = null)
        val index = LibraryIndex.build(listOf(a, b, c))
        assertEquals(listOf("2001", "1990"), index.years.map { it.name })
        assertEquals(listOf("Bach", "Händel"), index.composers.map { it.name })
        assertEquals(2, index.composers[0].tracks.size)
    }
}
