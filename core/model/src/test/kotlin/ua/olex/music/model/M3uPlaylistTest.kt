package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class M3uPlaylistTest {
    private val song = Track(TrackId("local:1"), "content://1", "Пісня + #1", "Artist", durationMs = 120_500, path = "Music/Album/Пісня + #1.flac")
    private fun library(vararg tracks: Track) = LibraryIndex.build(tracks.toList())
    @Test fun bomExtendedMetadataAndPlainPathsPreserveOrderAndResetLabels() {
        val doc = M3uPlaylist.parse("\uFEFF#EXTM3U\r\n#PLAYLIST:Travel\r\n#EXTINF:120.5,Artist - Song\r\nMusic/1.flac\r\nMusic/2.flac")
        assertEquals("Travel", doc.name)
        assertEquals(listOf("Music/1.flac", "Music/2.flac"), doc.entries.map { it.path })
        assertEquals(120_500L, doc.entries.first().durationMs)
        assertNull(doc.entries.last().label)
        assertEquals(0L, doc.entries.last().durationMs)
    }
    @Test fun exportedUtf8PathsWithPlusPercentHashAndSpacesRoundTripWithoutGuessing() {
        for (name in listOf("Пісня + #1", "100% + ready", "e\u0301", "space name")) {
            val track = song.copy(path = "Music/Album/$name.flac")
            val encoded = M3uPlaylist.encode("Travel", listOf(SavedSong.from(track)), "/storage/emulated/0")
            val resolved = M3uPlaylist.resolve(M3uPlaylist.parse(encoded), library(track))
            assertEquals(name, 1, resolved.count(M3uMatch.EXACT))
            assertEquals(SavedSong.from(track), resolved.songs.single())
        }
    }
    @Test fun knownAndroidRootsAndWindowsRelocationsResolveToTheSameLocalFile() {
        val windows = "C:\\Users\\Owner\\Music\\Album\\Пісня + #1.flac"
        val doc = M3uPlaylist.parse("/sdcard/${song.path}\n/storage/self/primary/${song.path}\n$windows")
        val resolved = M3uPlaylist.resolve(doc, library(song))
        assertEquals(1, resolved.count(M3uMatch.EXACT))
        assertEquals(2, resolved.count(M3uMatch.REPEATED))
        assertEquals(1, resolved.songs.size)
        assertEquals(M3uMatch.RELOCATED, library(song).playlistPaths.resolve(windows).match)
    }
    @Test fun anAmbiguousSuffixNeverSelectsAnArbitraryFile() {
        val other = song.copy(id = TrackId("local:2"), path = "Other/Album/Пісня + #1.flac")
        val resolved = M3uPlaylist.resolve(M3uPlaylist.parse("Album/Пісня + #1.flac"), library(song, other))
        assertEquals(1, resolved.count(M3uMatch.AMBIGUOUS))
        assertNull(resolved.songs.single().resolve(library(song, other)))
        assertEquals(song, resolved.songs.single().resolve(library(song)))
    }
    @Test fun missingPathsRemainDurableAndResolveAfterFilesArriveWithDifferentTags() {
        val imported = M3uPlaylist.resolve(M3uPlaylist.parse("#EXTINF:120,Old artist - Old title\n${song.path}"), LibraryIndex.EMPTY)
        assertEquals(1, imported.count(M3uMatch.MISSING))
        assertEquals(song.path, imported.songs.single().path)
        assertEquals(song, imported.songs.single().resolve(library(song)))
    }
    @Test fun relativeParentsNeedAFolderAndAreNotSilentlyDropped() {
        val doc = M3uPlaylist.parse("../Album/Пісня + #1.flac")
        val unresolved = M3uPlaylist.resolve(doc, library(song))
        assertEquals(1, unresolved.count(M3uMatch.NEEDS_FOLDER))
        assertFalse(unresolved.canImport)
        val resolved = M3uPlaylist.resolve(doc, library(song), "Music/Playlists")
        assertEquals(1, resolved.count(M3uMatch.EXACT))
        assertTrue(resolved.canImport)
        assertTrue(PlaylistPathIndex.validBaseFolder("."))
        assertFalse(PlaylistPathIndex.validBaseFolder("../outside"))
        assertFalse(PlaylistPathIndex.validBaseFolder("https://host/folder"))
    }
    @Test fun unsupportedNetworkReferencesAndRemoteFileHostsAreReportedWithoutOpeningThem() {
        val resolved = M3uPlaylist.resolve(M3uPlaylist.parse("https://host/song.flac\nfile://server/share/song.flac\ndata:audio/anything"), library(song))
        assertEquals(3, resolved.count(M3uMatch.UNSUPPORTED))
        assertTrue(resolved.songs.isEmpty())
    }
    @Test fun aFilenameAloneNeverRelocatesToAnUnrelatedFolder() {
        val resolved = M3uPlaylist.resolve(M3uPlaylist.parse("Пісня + #1.flac"), library(song))
        assertEquals(1, resolved.count(M3uMatch.MISSING))
    }
    @Test fun foreignMissingAbsolutePathsArePreservedDuringExport() {
        for (path in listOf("/storage/ABCD-1234/Album/missing.flac", "C:/Users/Music/missing.flac")) {
            val imported = M3uPlaylist.resolve(M3uPlaylist.parse(path), LibraryIndex.EMPTY).songs
            val exported = M3uPlaylist.encode("Keep", imported, "/storage/emulated/0")
            val reparsed = M3uPlaylist.resolve(M3uPlaylist.parse(exported), LibraryIndex.EMPTY)
            assertEquals(path, reparsed.songs.single().path)
        }
    }
    @Test fun hlsOversizedAndMalformedPathsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { M3uPlaylist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\nsegment.ts") }
        assertThrows(IllegalArgumentException::class.java) { M3uPlaylist.parse("x".repeat(M3uPlaylist.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { M3uPlaylist.parse("Music/\u0000song.flac") }
        val invalid = listOf("/../../song.flac", "a/".repeat(65) + "song.flac", "file:///Music/bad%XX.flac", "file:///Music/%FF.flac")
        for (path in invalid) assertEquals(M3uMatch.UNSUPPORTED, library(song).playlistPaths.resolve(path).match)
    }
    @Test fun tenThousandEntriesKeepExactOrderWithoutBeingTruncated() {
        val tracks = (1..10_000).map { id -> song.copy(id = TrackId("local:$id"), path = "Music/Album/$id.flac") }
        val resolved = M3uPlaylist.resolve(M3uPlaylist.parse(tracks.joinToString("\n") { requireNotNull(it.path) }), LibraryIndex.build(tracks))
        assertEquals(10_000, resolved.count(M3uMatch.EXACT))
        assertEquals(tracks.map { it.path }, resolved.songs.map { it.path })
        assertTrue(resolved.canImport)
    }

    @Test fun contentUrisOnlyResolveAlreadyIndexedFilesAndControlCharactersCannotInjectMetadataLines() {
        val imported = M3uPlaylist.resolve(M3uPlaylist.parse("content://1\ncontent://not-indexed"), library(song))
        assertEquals(1, imported.count(M3uMatch.EXACT)); assertEquals(1, imported.count(M3uMatch.UNSUPPORTED))
        val encoded = M3uPlaylist.encode("Name\n#evil", listOf(SavedSong.from(song)), "/storage/emulated/0")
        assertEquals("Name #evil", M3uPlaylist.parse(encoded).name)
    }
}
