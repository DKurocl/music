package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseTreeTest {
    private fun t(id: String, title: String, artist: String, album: String, n: Int) =
        Track(TrackId("local:$id"), "content://$id", title, artist, album = album, albumArtist = artist, trackNumber = n, genre = "Rock")
    private val songs = listOf(
        t("1", "Airbag", "Radiohead", "OK Computer", 1), t("2", "Paranoid Android", "Radiohead", "OK Computer", 2),
        t("3", "Karma Police", "Radiohead", "OK Computer", 6), t("4", "Déjà Vu", "Beyoncé", "B'Day", 3),
    )
    private val library = LibraryIndex.build(songs)
    private val tree = BrowseTree(library, smart = listOf("Most played" to listOf(songs[2], songs[0]), "Empty" to emptyList()),
        playlists = listOf(Triple(7L, "Drive", listOf(songs[3], songs[1]))))

    @Test fun rootListsOnlySectionsWithSomething() {
        assertEquals(listOf("Albums", "Artists", "Smart playlists", "Playlists", "Genres", "Songs"), tree.children(BrowseTree.ROOT)!!.map { it.title })
        assertTrue(tree.children(BrowseTree.ROOT)!!.none { it.playable })
        assertEquals(listOf("Albums", "Artists", "Genres", "Songs"), BrowseTree(library).children(BrowseTree.ROOT)!!.map { it.title })
    }

    @Test fun anAlbumPlaysFromTheChosenSongInTrackOrder() {
        val album = tree.children("section:albums")!!.first { it.title == "OK Computer" }
        val songsIn = tree.children(album.id)!!
        assertEquals(listOf("Airbag", "Paranoid Android", "Karma Police"), songsIn.map { it.title })
        val (queue, start) = tree.resolvePlay(songsIn[1].id)!!
        assertEquals(listOf("Airbag", "Paranoid Android", "Karma Police"), queue.map { it.title })
        assertEquals(1, start)
    }

    @Test fun smartAndUserPlaylistsKeepTheirOwnOrder() {
        val smart = tree.children("section:smart")!!.first()
        assertEquals(listOf("Karma Police", "Airbag"), tree.children(smart.id)!!.map { it.title })
        val drive = tree.children("section:playlists")!!.single()
        assertEquals("Drive", drive.title)
        val (queue, start) = tree.resolvePlay(tree.children(drive.id)!![1].id)!!
        assertEquals(listOf("Déjà Vu", "Paranoid Android"), queue.map { it.title }); assertEquals(1, start)
    }

    @Test fun aCollectionChosenByItselfPlaysFromTheTop() {
        val (queue, start) = tree.resolvePlay("playlist:7")!!
        assertEquals("Déjà Vu", queue[start].title)
    }

    @Test fun aPlainSongPlaysAloneAndUnknownIdsAreNull() {
        assertEquals(listOf("Karma Police"), tree.resolvePlay("local:3")!!.first.map { it.title })
        assertNull(tree.resolvePlay("album:nope"))
        assertNull(tree.children("nope:1"))
        assertEquals("Karma Police", tree.item("album:x|local:3")!!.title)
    }

    @Test fun searchRanksTitleThenArtistThenAlbum() {
        assertEquals(listOf("Karma Police"), tree.search("karma").map { it.title })
        assertEquals(listOf("Déjà Vu"), tree.search("beyonce").map { it.title })
        assertEquals(3, tree.search("ok computer").size)
        assertEquals("every word must match", listOf("Paranoid Android"), tree.search("radiohead paranoid").map { it.title })
        assertTrue(tree.search("   ").isEmpty())
    }

    @Test fun aYouTubeMusicSongInAPlaylistCanBeOpenedPlayedAndFound() {
        val online = Track(TrackId("yt:abcdefghijk"), "ytm://abcdefghijk", "Teardrop", "Massive Attack")
        val tree = BrowseTree(LibraryIndex.EMPTY, playlists = listOf(Triple(7L, "Mixed", listOf(online))))
        val node = tree.children("playlist:7")!!.single()
        assertEquals(online, tree.item(node.id)?.track)
        assertEquals(listOf(online) to 0, tree.resolvePlay(node.id))
        assertEquals(listOf(online), tree.search("teardrop"))
    }
}
