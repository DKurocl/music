package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class SavedCatalogTest {
    private val online = Track(TrackId("yt:one"), "ytm://one", "Halo", "Beyoncé", album = "I Am", durationMs = 260_000)
    private val file = online.copy(id = TrackId("local:1"), uri = "content://1", path = "Music/Halo.flac", format = AudioFormat("flac"))

    @Test fun savedSongsWorkWithoutFilesAndAppearInCollectionsAndSmartLists() {
        val saved = listOf(SavedSong.from(online), SavedSong.from(online))
        val catalogue = SavedCatalog.merge(LibraryIndex.EMPTY, saved)
        assertEquals(listOf(online), catalogue.songs)
        assertEquals(listOf(online), catalogue.albums.single().tracks)
        assertEquals("Beyoncé", catalogue.artists.single().name)
        assertTrue(catalogue.folders.isEmpty())
        val smart = SmartPlaylist("Played", listOf(SmartRule(SmartField.PLAYS, SmartOp.AT_LEAST, "1")))
        assertEquals(listOf(online), smart.evaluate(catalogue.songs, { SongStats(plays = 3) }, { true }, 0))
        assertEquals(3, LibraryStats.of(catalogue) { SongStats(plays = 3) }.plays)
    }

    @Test fun downloadingAndRemovingAFilePreservesTheOnlineFallbackWithoutDuplicates() {
        val saved = listOf(SavedSong.from(online))
        val local = LibraryIndex.build(listOf(file))
        val merged = SavedCatalog.merge(local, saved)
        assertEquals(file.id, merged.songs.single().id)
        assertEquals(setOf("ytm://one", "content://1"), merged.songs.single().sources.map { it.uri }.toSet())
        assertEquals(listOf(online), SavedCatalog.merge(LibraryIndex.EMPTY, saved).songs)
        assertEquals("ytm://one", saved.single().path)
    }

    @Test fun differentRecordingAndDurationDoNotHideSavedOnlineSongs() {
        val live = file.copy(title = "Halo (Live)")
        val long = file.copy(id = TrackId("local:2"), durationMs = 280_000)
        val catalogue = SavedCatalog.merge(LibraryIndex.build(listOf(live, long)), listOf(SavedSong.from(online)))
        assertEquals(setOf(live.id, long.id, online.id), catalogue.byId.keys)
        assertEquals(2, catalogue.folders.single().tracks.size)
    }

    @Test fun missingLocalSnapshotsNeverBecomePlayableAndOnlineSearchIsLiteral() {
        val catalogue = SavedCatalog.merge(LibraryIndex.EMPTY, listOf(SavedSong.from(file), SavedSong.from(online)))
        assertEquals(listOf(online), SavedCatalog.searchOnline(catalogue, "beyon hal"))
        assertEquals(listOf(online), SavedCatalog.searchOnline(catalogue, "I am HAL"))
        assertTrue(SavedCatalog.searchOnline(catalogue, "eyon").isEmpty())
        assertTrue(SavedCatalog.searchOnline(catalogue, "* \" ").isEmpty())
        assertEquals(listOf(online), catalogue.songs)
    }

    @Test fun mergingSavedSongsDoesNotSplitAlreadyExpandedCueTracksAgain() {
        val cue = """
            TRACK 01 AUDIO
              TITLE "First"
              INDEX 01 00:00:00
            TRACK 02 AUDIO
              TITLE "Second"
              INDEX 01 02:00:00
        """.trimIndent()
        val local = LibraryIndex.build(listOf(file.copy(metadata = FileMetadata(cueSheet = cue))))
        val merged = SavedCatalog.merge(local, listOf(SavedSong.from(online)))
        assertEquals(local.songs, merged.songs.filter { it.id.value.startsWith("local:") })
        assertEquals(3, merged.songs.size)
    }

    @Test fun durableRecordingsBindAllCopiesButAppearOnceInTheCatalogue() {
        val saved = SavedSong.from(online).copy(id = "recording:one", createdAt = 10)
        val copy = file.copy(id = TrackId("local:2"), path = "Other/Halo.flac", format = AudioFormat("flac", 96_000, 24))
        val local = LibraryIndex.build(listOf(file, copy))
        val bound = SavedCatalog.bindLocal(local, listOf(saved))
        assertEquals(listOf("recording:one", "recording:one"), bound.map { it.recordingId })
        val catalogue = SavedCatalog.merge(local, listOf(saved))
        assertEquals(copy.id, catalogue.songs.single().id)
        assertEquals(2, catalogue.folders.sumOf { it.tracks.size })
        assertTrue(catalogue.folders.flatMap { it.tracks }.all { it.recordingId == saved.id })
        assertEquals("recording:one", catalogue.songs.single().statsKey)
        assertEquals(online.uri, SavedSong.from(catalogue.songs.single()).online()!!.uri)
    }

    @Test fun anAmbiguousRecordingCannotClaimAScannedFile() {
        val first = SavedSong.from(online).copy(id = "recording:a", durationMs = 260_000,
            sources = listOf(RecordingSource.from(online)!!))
        val second = SavedSong.from(online.copy(uri = "ytm://two", durationMs = 265_000)).copy(id = "recording:b")
        val middle = file.copy(durationMs = 262_000)
        val local = LibraryIndex.build(listOf(middle))
        assertNull(SavedCatalog.bindLocal(local, listOf(first, second)).single().recordingId)
        assertEquals(3, SavedCatalog.merge(local, listOf(first, second)).songs.size)
        val bridge = SavedSong.from(online.copy(durationMs = 262_000))
        assertEquals(online.uri, bridge.resolve(LibraryIndex.build(listOf(file, file.copy(id = TrackId("local:2"), durationMs = 265_000))))!!.uri)
    }

}
