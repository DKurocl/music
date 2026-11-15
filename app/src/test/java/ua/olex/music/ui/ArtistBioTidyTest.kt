package ua.olex.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistBioTidyTest {
    @Test fun dropsSpacesLeftByStrippedNotes() {
        assertEquals("Thom Yorke; brothers Jonny and Colin (bass); Ed O'Brien.",
            tidyBio("Thom Yorke ; brothers Jonny and Colin ( bass ) ; Ed O'Brien ."))
        assertEquals("Björk is a singer.", tidyBio("Björk ( ; ) is a singer."))
    }
}
