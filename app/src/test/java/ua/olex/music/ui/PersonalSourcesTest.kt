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
        assertEquals(setOf("local:a", "local:b", "yt:one"), personal.favouriteByTrackId.keys)
        assertEquals(setOf(song.id), personal.favouriteByTrackId.values.map { it.id }.toSet())
        assertEquals(1, personal.favourites.size)
    }
}
