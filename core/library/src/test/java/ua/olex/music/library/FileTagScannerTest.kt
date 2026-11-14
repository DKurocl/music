package ua.olex.music.library

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.model.SongKey
import ua.olex.music.tags.AudioTags
import ua.olex.music.tags.Picture

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FileTagScannerTest {
    private fun row() = TrackEntity("local:1", "content://1", "System title", "System artist", "System album", null,
        100_000, null, null, null, null, "flac", null, null, null, "Music/song.flac", "old", 12, 1_000, 1)
    private val tags = AudioTags("flac", "flac", 96_000, 24, 2, 120_000, fields = mapOf(
        "TITLE" to listOf("Tagged song"), "ARTIST" to listOf("AC/DC", "Guest"), "ALBUMARTIST" to listOf("Album artist"),
        "GENRE" to listOf("Rock", "Electronic"), "TRACKNUMBER" to listOf("2/12"), "DISCNUMBER" to listOf("1/2"),
        "REPLAYGAIN_TRACK_GAIN" to listOf("-6.5 dB"), "REPLAYGAIN_TRACK_PEAK" to listOf("0.95"),
        "LYRICS" to listOf("[00:01]Words"), "MUSICBRAINZ_TRACKID" to listOf("recording-id")),
        pictures = listOf(Picture(100, 300, "image/jpeg", 3)), encoderDelay = 576, encoderPadding = 100)

    @Test fun fileTagsWinAndAllConsumersReceiveThePersistedMetadata() = runBlocking {
        val result = FileTagScanner.enrich(listOf(row()), emptyList()) { tags }.single()
        val track = result.toTrack()
        assertEquals("Tagged song", track.title)
        assertEquals(listOf("AC/DC", "Guest"), track.artists)
        assertEquals(listOf("Rock", "Electronic"), track.genres)
        assertEquals("FLAC 24/96", track.format?.label)
        assertEquals(2, track.format?.channels)
        assertEquals(120_000L, track.durationMs)
        assertEquals(2, track.trackNumber)
        assertEquals(SongKey.of(track.artist, track.title).value, result.songKey)
        assertEquals(-6.5f, track.metadata?.trackGainDb)
        assertEquals(0.95f, track.metadata?.trackPeak)
        assertEquals(100L, track.metadata?.coverOffset)
        assertEquals(300, track.metadata?.coverLength)
        assertEquals("[00:01]Words", track.metadata?.lyrics)
        assertEquals(576, track.metadata?.encoderDelay)
    }

    @Test fun unchangedFileIsNotReopenedButReceivesTheNewScanId() = runBlocking {
        val first = FileTagScanner.enrich(listOf(row()), emptyList()) { tags }.single()
        val next = FileTagScanner.enrich(listOf(row().copy(scanId = 2)), listOf(first)) { error("Unchanged file opened") }.single()
        assertEquals(2L, next.scanId)
        assertEquals(first.metadataJson, next.metadataJson)
        assertEquals(first.title, next.title)
    }

    @Test fun mtimeSizePathAndReaderVersionChangesInvalidateCachedTags() = runBlocking {
        val first = FileTagScanner.enrich(listOf(row()), emptyList()) { tags }.single()
        for (changed in listOf(row().copy(modifiedAt = 13), row().copy(sizeBytes = 1_001), row().copy(path = "Moved/song.flac"))) {
            var opens = 0
            FileTagScanner.enrich(listOf(changed), listOf(first)) { opens++; tags }
            assertEquals(1, opens)
        }
        var opens = 0
        FileTagScanner.enrich(listOf(row()), listOf(first.copy(tagReaderVersion = 0))) { opens++; tags }
        assertEquals(1, opens)
    }

    @Test fun unsupportedFileKeepsSystemValuesAndIsNotParsedOnEveryScan() = runBlocking {
        val first = FileTagScanner.enrich(listOf(row()), emptyList()) { null }.single()
        assertEquals(row().title, first.title)
        assertEquals(row().codec, first.codec)
        FileTagScanner.enrich(listOf(row()), listOf(first)) { error("Unsupported unchanged file reopened") }
    }

    @Test fun transientFileErrorFallsBackAndRetriesWithoutHidingOtherSongs() = runBlocking {
        val failed = FileTagScanner.enrich(listOf(row()), emptyList()) { throw IOException("File copying") }.single()
        assertEquals(0, failed.tagReaderVersion)
        val retry = FileTagScanner.enrich(listOf(row()), listOf(failed)) { tags }.single()
        assertEquals("Tagged song", retry.title)
    }

    @Test fun permissionAndCancellationAbortInsteadOfPublishingPartialMetadata() {
        assertThrows(SecurityException::class.java) { runBlocking { FileTagScanner.enrich(listOf(row()), emptyList()) { throw SecurityException() } } }
        assertThrows(CancellationException::class.java) { runBlocking { FileTagScanner.enrich(listOf(row()), emptyList()) { throw CancellationException() } } }
    }

    @Test fun invalidCoverAndNonfiniteGainAreNotPersisted() {
        val result = FileTagScanner.merge(row(), tags.copy(pictures = listOf(Picture(900, 300, "image/jpeg", 3)), fields = mapOf("REPLAYGAIN_TRACK_GAIN" to listOf("9999999999999999999999999999999999999999999999"))))
        assertNull(result.toTrack().metadata?.coverOffset)
        assertNull(result.toTrack().metadata?.trackGainDb)
    }
}
