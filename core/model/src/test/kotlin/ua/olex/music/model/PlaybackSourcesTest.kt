package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourcesTest {
    private val file = Track(TrackId("local:one"), "content://one", "Song", "Artist", durationMs = 202_000, path = "Music/song.flac")
    private fun online(id: String, ms: Long = 202_000) = file.copy(id = TrackId("yt:$id"), uri = "ytm://$id", path = null, durationMs = ms, artworkUri = "https://cover/$id")
    private fun saved(id: String, track: Track) = SavedSong.from(track).copy(id = id)

    @Test fun fileLossSelectsTheRetainedVideoAndItsOwnArtwork() {
        val original = saved("recording:one", online("reserve"))
        val linked = SavedSong.from(original.bind(file)).copy(id = original.id)
        val result = PlaybackSources(LibraryIndex.EMPTY, listOf(linked)).select(linked.bind(file))
        assertNull(result.issue)
        assertEquals("ytm://reserve", result.track.uri)
        assertEquals("https://cover/reserve", result.track.artworkUri)
        assertEquals(original.id, result.track.recordingId)
        assertNull(result.track.clipStartMs)
    }

    @Test fun knownRecordingSelectsTheFileAcrossAnOldAlias() {
        val original = saved("recording:new", online("reserve"))
        val policy = PlaybackSources(LibraryIndex.build(listOf(file)), listOf(original), mapOf("old" to original.id))
        val selected = policy.select(online("reserve").copy(recordingId = "old"))
        assertEquals(file.uri, selected.track.uri)
        assertEquals(original.id, selected.track.recordingId)
        assertEquals("ytm://reserve", SavedSong.from(selected.track).online()!!.uri)
    }

    @Test fun aFailedLocalSourceUsesItsReserveWithoutOpeningTheSameFileAgain() {
        val original = saved("recording:one", online("reserve"))
        val linked = SavedSong.from(original.bind(file)).copy(id = original.id)
        val result = PlaybackSources(LibraryIndex.build(listOf(file)), listOf(linked))
            .select(linked.bind(file), setOf(RecordingSource.from(file)!!.key))
        assertEquals("ytm://reserve", result.track.uri)
        assertEquals(2, linked.references.size)
        assertTrue(result.track.sources.all { it.kind == RecordingSourceKind.YOUTUBE })
    }

    @Test fun ambiguousPhysicalCopiesCannotStandInForEitherSavedTake() {
        val a = saved("recording:a", online("a", 200_000))
        val b = saved("recording:b", online("b", 205_000))
        val raw = saved("recording:unresolved", file)
        val policy = PlaybackSources(LibraryIndex.build(listOf(file)), listOf(a, b, raw))
        assertEquals("ytm://a", policy.select(online("a", 200_000)).track.uri)
        assertEquals("ytm://b", policy.select(online("b", 205_000)).track.uri)
        assertEquals(file.uri, policy.select(file).track.uri)
        assertEquals(raw.id, policy.select(file).track.recordingId)
    }

    @Test fun offlineAndMissingChoicesRetainTheRequestedRecording() {
        val original = saved("recording:one", online("reserve"))
        val result = PlaybackSources(LibraryIndex.EMPTY, listOf(original)).select(online("reserve"), available = { false })
        assertEquals(SourceIssue.OFFLINE, result.issue)
        assertEquals(original.id, result.track.recordingId)
        val local = saved("recording:local", file)
        assertEquals(SourceIssue.MISSING, PlaybackSources(LibraryIndex.EMPTY, listOf(local)).select(local.bind(file)).issue)
    }

    @Test fun aTrustedDirectUriNeedsNoMediaStoreEntry() {
        assertEquals(SourceIssue.MISSING, PlaybackSources(LibraryIndex.EMPTY, emptyList()).select(file).issue)
        assertNull(PlaybackSources(LibraryIndex.EMPTY, emptyList()).select(file, allowDirect = true).issue)
    }

    @Test fun rejectingEverySourceDoesNotResurrectTheLegacyPrimaryPath() {
        val song = saved("recording:one", online("failed"))
        val result = PlaybackSources(LibraryIndex.EMPTY, listOf(song)).select(song.online()!!,
            rejected = song.references.mapTo(HashSet()) { it.key })
        assertEquals(SourceIssue.MISSING, result.issue)
        assertEquals(song.id, result.track.recordingId)
    }

    @Test fun aNewRequestedVideoSurvivesMatchingAnExistingLocalRecording() {
        val song = saved("recording:file", file)
        val result = PlaybackSources(LibraryIndex.build(listOf(file)), listOf(song)).select(online("new"))
        assertNull(result.issue)
        assertEquals(file.uri, result.track.uri)
        assertEquals(song.id, result.track.recordingId)
        assertEquals("ytm://new", SavedSong.from(result.track).online()!!.uri)
    }

    @Test fun aWeakUiSubstitutionCannotClaimAnAmbiguousFile() {
        val a = saved("recording:a", online("a", 200_000))
        val b = saved("recording:b", online("b", 205_000))
        val raw = saved("recording:unresolved", file)
        val weak = SavedSong.from(online("a", 200_000)).bind(file).copy(requestedId = "yt:a")
        val policy = PlaybackSources(LibraryIndex.build(listOf(file)), listOf(a, b, raw))
        assertEquals("ytm://a", policy.select(weak).track.uri)
        assertEquals("ytm://new", policy.select(online("new")).track.uri)
    }

    @Test fun verifiedCreditVariantsCanUseTheFileAndRetainTheRequestedVideo() {
        val remote = online("credits").copy(artist = "Guest")
        val local = file.copy(artists = listOf("Artist", "Guest"))
        val recorded = saved("recording:remote", remote)
        val owned = saved("recording:local", local)
        val linked = SavedSong.from(remote).bind(local)
        val policy = PlaybackSources(LibraryIndex.build(listOf(local)), listOf(recorded, owned))
        val result = policy.select(linked)
        assertNull(result.issue)
        assertEquals(local.uri, result.track.uri)
        assertEquals("ytm://credits", SavedSong.from(result.track).online()!!.uri)
    }


    @Test fun aBulkQueueFiltersTheLocalSnapshotOnceRatherThanOncePerSong() {
        val files = (1..400).map { n -> file.copy(id = TrackId("local:$n"), uri = "content://$n", title = "Song $n", path = "Music/$n.flac") }
        val policy = PlaybackSources(LibraryIndex.build(files), emptyList())
        var checks = 0
        val availability: (String) -> Boolean = { checks++; true }
        val selected = files.map { policy.select(it, available = availability) }
        assertTrue(selected.all { it.issue == null })
        assertEquals(files.size, checks)
    }


    @Test fun aBandNamePrefixIsNotProofOfASharedPerformer() {
        val band = file.copy(artist = "Earth, Wind & Fire", artists = listOf("Earth, Wind & Fire"))
        val solo = online("solo").copy(artist = "Earth", artists = listOf("Earth"))
        val selected = PlaybackSources(LibraryIndex.build(listOf(band)), emptyList()).select(solo, proposedLocal = band)
        assertEquals(solo.uri, selected.track.uri)
    }

    @Test fun aPreviouslyMeasuredVideoSuppliesMissingDurationWithoutGuessingANewOne() {
        val measured = saved("recording:measured", online("known"))
        val policy = PlaybackSources(LibraryIndex.build(listOf(file)), listOf(measured))
        assertEquals(file.uri, policy.select(online("known").copy(durationMs = 0)).track.uri)
        assertEquals("ytm://unknown", policy.select(online("unknown").copy(durationMs = 0)).track.uri)
    }

    @Test fun rowsKeepThePlayingMarkAcrossSourceChangesWithoutMarkingAnotherTake() {
        val request = online("request")
        val playing = file.copy(requestedId = request.id.value, recordingId = "recording:one",
            sources = listOf(RecordingSource.from(online("reserve"))!!))
        assertTrue(request.matchesPlayback(playing))
        assertTrue(online("reserve").matchesPlayback(playing))
        assertTrue(file.copy(id = TrackId("local:second"), recordingId = playing.recordingId).matchesPlayback(playing))
        assertFalse(online("other-take").matchesPlayback(playing))
        assertFalse(file.copy(id = TrackId("local:cue2"), recordingId = "recording:other").matchesPlayback(playing))
        assertFalse(request.matchesPlayback(null))
    }

}
