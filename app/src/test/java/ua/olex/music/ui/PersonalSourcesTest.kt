package ua.olex.music.ui

import org.junit.Assert.*
import org.junit.Test
import ua.olex.music.model.*

class PersonalSourcesTest {
    @Test fun everyPhysicalCopyAndTheVideoShareTheSameHeart() {
        val online = Track(TrackId("yt:one"), "ytm://one", "Song", "Artist", durationMs = 200_000)
        val a = online.copy(id = TrackId("local:a"), uri = "content://a", path = "Music/a.flac", format = AudioFormat("flac"))
        val b = a.copy(id = TrackId("local:b"), uri = "content://b", path = "Other/b.flac", format = AudioFormat("flac", 96_000, 24))
        val song = SavedSong.from(online).copy(id = "recording:one")
        val catalogue = SavedCatalog.merge(LibraryIndex.build(listOf(a, b)), listOf(song))
        val personal = PersonalLibraryState.resolve(catalogue, listOf(song), emptyList(), emptyList())
        assertEquals(setOf("local:a", "local:b", "yt:one", "recording:one"), personal.favouriteByTrackId.keys)
        assertEquals(setOf(song.id), personal.favouriteByTrackId.values.map { it.id }.toSet())
        assertEquals(1, personal.favourites.size)
    }

    @Test fun anAmbiguousPhysicalCopyDoesNotBorrowAnotherRecordingsHeart() {
        val file = Track(TrackId("local:raw"), "content://raw", "Song", "Artist", durationMs = 202_000, path = "Music/song.flac")
        val a = SavedSong.from(file.copy(id = TrackId("yt:a"), uri = "ytm://a", path = null, durationMs = 200_000)).copy(id = "recording:a")
        val b = SavedSong.from(file.copy(id = TrackId("yt:b"), uri = "ytm://b", path = null, durationMs = 205_000)).copy(id = "recording:b")
        val raw = SavedSong.from(file).copy(id = "recording:raw")
        val catalogue = SavedCatalog.merge(LibraryIndex.build(listOf(file)), listOf(a, raw), listOf(a, b, raw))
        val state = PersonalLibraryState.resolve(catalogue, listOf(a), emptyList(), emptyList(), mapOf("old-a" to a.id))
        assertFalse(state.favouriteByTrackId.containsKey(file.id.value))
        assertFalse(state.favouriteByTrackId.containsKey(raw.id))
        assertEquals(a.id, state.favouriteByTrackId.getValue("yt:a").id)
        assertEquals(a.id, state.favouriteByTrackId.getValue("old-a").id)
    }

}
