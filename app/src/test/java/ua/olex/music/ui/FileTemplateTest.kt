package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

class FileTemplateTest {
    private val t = Track(TrackId("1"), "", "Song: Part 1", "Artist", album = "Rec/ord", albumArtist = "Band", trackNumber = 3, year = 1999)

    @Test fun namesAndFoldersFromTags() {
        assertEquals("03 - Song_ Part 1", FileTemplate.fill("%track% - %title%", t))
        assertEquals("Music/Band/Rec_ord", FileTemplate.fill("Music/%albumartist%/%album%", t, folder = true))
        assertEquals("1999 New", FileTemplate.fill("%year% %title%", t, mapOf("TITLE" to listOf("New"))))
    }
}
