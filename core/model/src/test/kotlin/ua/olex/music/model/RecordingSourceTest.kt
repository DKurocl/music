package ua.olex.music.model

import org.junit.Assert.*
import org.junit.Test

class RecordingSourceTest {
    private val key = SongKey.of("Artist", "Song").value
    private fun file(path: String = "Music/song.flac", ms: Long = 200_000) = RecordingSource(
        RecordingSourceKind.LOCAL, key, uri = "content://file", path = path, durationMs = ms)
    private fun online(id: String = "one", ms: Long = 200_500) = RecordingSource(
        RecordingSourceKind.YOUTUBE, key, uri = "ytm://$id", durationMs = ms, artworkUri = "https://cover/$id")
    private fun candidate(id: String, source: RecordingSource, at: Long = 0) = RecordingCandidate(id, at, listOf(source))

    @Test fun sourceIdentitySurvivesMediaStoreRenumberingAndKeepsCueClipsDistinct() {
        val a = file()
        assertEquals(a.key, a.copy(uri = "content://renumbered").key)
        assertNotEquals(a.key, a.copy(path = "Music/copy.flac").key)
        assertNotEquals(a.copy(clipStartMs = 0, clipEndMs = 100_000).key, a.copy(clipStartMs = 100_000, clipEndMs = 200_000).key)
        assertNotEquals(a.copy(path = "Music/song.flac#clip:0:100000").key, a.copy(clipStartMs = 0, clipEndMs = 100_000).key)
    }

    @Test fun knownRecordingLinksBothSourcesAndKeepsTheOldestDurableId() {
        val older = candidate("recording:older", file(), 10)
        val newer = candidate("recording:newer", online(), 20)
        assertEquals(listOf(older, newer), RecordingMatcher.group(listOf(online()), listOf(newer, older)))
        assertEquals(listOf(older, newer), RecordingMatcher.group(listOf(file()), listOf(newer, older)))
    }

    @Test fun anotherTakeOrConflictingMusicBrainzRecordingNeverMerges() {
        val old = candidate("original", file().copy(musicBrainzId = "aaa"))
        assertTrue(RecordingMatcher.group(listOf(online().copy(songKey = SongKey.of("Artist", "Song (Live)").value)), listOf(old)).isEmpty())
        assertTrue(RecordingMatcher.group(listOf(online().copy(musicBrainzId = "bbb")), listOf(old)).isEmpty())
        assertEquals(listOf(old), RecordingMatcher.group(listOf(online().copy(musicBrainzId = "AAA")), listOf(old)))
    }

    @Test fun unknownDurationRequiresTheSameSourceAndGenericMetadataDoesNotGuess() {
        val old = candidate("saved", file())
        assertTrue(RecordingMatcher.group(listOf(online(ms = 0)), listOf(old)).isEmpty())
        assertEquals(listOf(old), RecordingMatcher.group(listOf(file(ms = 0)), listOf(old)))
        val unknown = file().copy(songKey = SongKey.of("Unknown artist", "Song").value)
        assertTrue(RecordingMatcher.group(listOf(unknown.copy(path = "Other/song.flac")), listOf(candidate("unknown", unknown))).isEmpty())
    }

    @Test fun anAmbiguousDurationBridgeDoesNotCollapseDifferentRecordings() {
        val a = candidate("a", file("A.flac", 200_000))
        val b = candidate("b", file("B.flac", 205_000))
        assertTrue(RecordingMatcher.group(listOf(online(ms = 202_000)), listOf(a, b)).isEmpty())
        assertEquals(listOf(a), RecordingMatcher.group(listOf(file("A.flac", 202_000)), listOf(a, b)))
    }

    @Test fun anUnresolvedSourceStaysStableWithoutMergingItsConflictingLinkedTakes() {
        val actual = file(ms = 202_000)
        val a = RecordingCandidate("a", 1, listOf(actual, online("a", 200_000)))
        val b = RecordingCandidate("b", 2, listOf(actual, online("b", 205_000)))
        assertTrue(RecordingMatcher.group(listOf(actual), listOf(a, b)).isEmpty())
        val unresolved = candidate("unresolved", actual, 3)
        assertEquals(listOf(unresolved), RecordingMatcher.group(listOf(actual), listOf(a, b, unresolved)))
        assertEquals(listOf(a), RecordingMatcher.group(listOf(online("a", 200_000)), listOf(a, b, unresolved)))
    }

    @Test fun differentCueOccurrencesInTheSameFileRemainSeparate() {
        val a = file().copy(clipStartMs = 0, clipEndMs = 200_000)
        val b = file().copy(clipStartMs = 200_000, clipEndMs = 400_000)
        assertTrue(RecordingMatcher.group(listOf(b), listOf(candidate("first", a))).isEmpty())
    }

    @Test fun aMissingImportCanAcquireTheRealTagsOfItsExactPath() {
        val missing = file().copy(kind = RecordingSourceKind.MISSING_LOCAL, songKey = "unknown|filename", durationMs = 0, uri = null)
        assertEquals(listOf(candidate("imported", missing)), RecordingMatcher.group(listOf(file()), listOf(candidate("imported", missing))))
    }

    @Test fun explicitCreditsCanLinkWithoutMergingDifferentSongTitles() {
        val original = online().copy(songKey = SongKey.of("Queen", "Under Pressure").value)
        val duet = file().copy(songKey = SongKey.of("David Bowie", "Under Pressure").value)
        assertEquals(1, RecordingMatcher.group(listOf(original, duet), listOf(candidate("duet", original))).size)
        assertTrue(RecordingMatcher.group(listOf(original, duet.copy(songKey = SongKey.of("David Bowie", "Heroes").value)), listOf(candidate("duet", original))).isEmpty())
    }

    @Test fun queueSourceCodecPreservesEveryFieldAndUnicodeWithoutDelimiterCollisions() {
        val local = file("Музика/a:1\n.flac").copy(musicBrainzId = "id", qualityRank = 1_000_000_000, clipStartMs = 0, clipEndMs = 200_000)
        val sources = listOf(local, online(), local.copy(kind = RecordingSourceKind.MISSING_LOCAL, uri = null))
        assertEquals(sources, RecordingSourcesCodec.decode(RecordingSourcesCodec.encode(sources)))
        assertTrue(RecordingSourcesCodec.decode(null).isEmpty())
        assertTrue(RecordingSourcesCodec.decode(RecordingSourcesCodec.encode(emptyList())).isEmpty())
    }

    @Test fun queueSourceCodecRejectsTruncationOversizeAndUnknownVersions() {
        val encoded = RecordingSourcesCodec.encode(listOf(online()))
        for (bad in listOf(encoded.dropLast(1), "2;0;", "1;-1;", "1;33;", encoded + "trailing", "x".repeat(65_537))) {
            assertThrows(IllegalArgumentException::class.java) { RecordingSourcesCodec.decode(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { RecordingSourcesCodec.encode(List(33) { online() }) }
    }

    @Test fun oldMetadataWithoutAnAddressCanRecoverButCannotInventAPlayableSource() {
        val old = RecordingCandidate("legacy", 0, emptyList(), key, 200_000)
        assertEquals(listOf(old), RecordingMatcher.group(listOf(online()), listOf(old)))
        assertTrue(RecordingMatcher.group(listOf(online(ms = 0)), listOf(old)).isEmpty())
        assertTrue(RecordingMatcher.group(listOf(online()), listOf(old.copy(legacyDurationMs = 0))).isEmpty())
    }

    @Test fun selectedUriTakesPriorityOverTheRequestedSourceId() {
        val track = Track(TrackId("yt:one"), "content://actual", "Song", "Artist", path = "Music/song.flac")
        assertEquals(RecordingSourceKind.LOCAL, RecordingSource.from(track)!!.kind)
    }

}
