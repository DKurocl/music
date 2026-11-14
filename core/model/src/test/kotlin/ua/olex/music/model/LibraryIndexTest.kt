package ua.olex.music.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIndexTest {
    @Test fun compilationsBelongToAlbumArtistAndFollowDiscThenTrackOrder() {
        val tracks = listOf(
            song("d2t1", "Second disc", disc = 2, number = 1),
            song("unknown", "Untitled", number = null),
            song("d1t2", "Another singer", number = 2, artist = "Guest"),
            song("d1t1", "Opening", number = 1),
        )
        val library = LibraryIndex.build(tracks)
        assertEquals(1, library.albums.size)
        assertEquals("Various Artists", library.artists.single().name)
        assertEquals(listOf("d1t1", "d1t2", "unknown", "d2t1"), library.albums.single().tracks.map { it.id.raw })
    }

    @Test fun albumsWithTheSameTitleAndDifferentArtistsStaySeparate() {
        val library = LibraryIndex.build(listOf(
            song("1", "One").copy(albumArtist = "First"),
            song("2", "Two").copy(albumArtist = "Second"),
        ))
        assertEquals(2, library.albums.size)
        assertNotEquals(library.albums[0].id, library.albums[1].id)
    }

    @Test fun songAndCollectionSortIgnoresArticlesAndAccents() {
        val library = LibraryIndex.build(listOf(
            song("z", "The Zebra").copy(album = "The Zebra"),
            song("b", "A Bear").copy(album = "A Bear"),
            song("a", "Álpha").copy(album = "Álpha"),
        ))
        assertEquals(listOf("a", "b", "z"), library.songs.map { it.id.raw })
        assertEquals(listOf("Álpha", "A Bear", "The Zebra"), library.albums.map { it.name })
    }

    @Test fun foldersUseTheWholePathAndGenresUseTheirOwnTags() {
        val library = LibraryIndex.build(listOf(
            song("1", "One").copy(path = "Music/One/Hits/01.flac", genre = "Rock"),
            song("2", "Two").copy(path = "Music/Two/Hits/02.flac", genre = "Jazz"),
            song("3", "Three").copy(path = null, genre = null),
        ))
        assertEquals(3, library.folders.size)
        assertEquals(2, library.folders.count { it.name == "Hits" })
        assertEquals(3, library.folders.map { it.id }.distinct().size)
        assertEquals(listOf("Jazz", "Rock", "Unknown genre"), library.genres.map { it.name })
    }

    @Test fun blankAlbumArtistFallsBackAndGroupingIsCaseInsensitive() {
        val library = LibraryIndex.build(listOf(
            song("1", "One", artist = "Björk").copy(albumArtist = " "),
            song("2", "Two", artist = "BJÖRK").copy(albumArtist = null),
        ))
        assertEquals(1, library.albums.size)
        assertEquals(1, library.artists.size)
    }

    @Test fun shuffledInputHasDeterministicQueuesAndCollectionIds() {
        val tracks = (1..30).map { song(it.toString(), "Song ${it % 5}", disc = it % 2 + 1, number = it % 7) }
        assertEquals(LibraryIndex.build(tracks), LibraryIndex.build(tracks.reversed()))
    }

    @Test fun tenThousandTracksAppearExactlyOnceInEveryBrowseView() {
        val tracks = (0 until 10_000).map { index ->
            song(index.toString(), "Track $index", number = index % 10 + 1).copy(
                albumArtist = "Artist ${index / 100}", album = "Album ${index / 10}",
                path = "Music/Album ${index / 10}/$index.flac", genre = "Genre ${index % 5}",
            )
        }
        val library = LibraryIndex.build(tracks)
        val expectedIds = tracks.map { it.id }.toSet()
        for (collections in listOf(library.albums, library.artists, library.genres, library.folders)) {
            val indexed = collections.flatMap { it.tracks }.map { it.id }
            assertEquals(10_000, indexed.size)
            assertEquals(expectedIds, indexed.toSet())
        }
        assertEquals(1_000, library.albums.size)
        assertEquals(100, library.artists.size)
        assertEquals(1_000, library.folders.size)
        assertEquals(5, library.genres.size)
        assertEquals(expectedIds, library.byId.keys)
    }

    @Test fun sortingDoesNotChangeWithThePhoneLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("indigo", LibraryOrder.name("The INDIGO"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun searchTreatsOperatorsAndPunctuationAsUserText() {
        assertEquals("\"Bey*\" \"déj*\"", LibrarySearch.expression("Bey déj"))
        assertEquals("\"title*\" \"OR*\" \"AC*\" \"DC*\"", LibrarySearch.expression("title: OR \"AC/DC\"*"))
        assertEquals("\"Обій*\"", LibrarySearch.expression("Обій"))
        assertNull(LibrarySearch.expression(" \"*():- "))
        assertTrue(LibraryIndex.build(emptyList()).songs.isEmpty())
    }

    private fun song(id: String, title: String, disc: Int? = 1, number: Int? = 1, artist: String = "Singer") = Track(
        id = TrackId.of(Origin.LOCAL, id), uri = "", title = title, artist = artist,
        album = "Compilation", albumArtist = "Various Artists", trackNumber = number, discNumber = disc,
    )
}
