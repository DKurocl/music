package ua.olex.music.playback

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.olex.music.model.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackSourceItemsTest {
    private val file = Track(TrackId("local:one"), "content://one", "Song", "Artist", durationMs = 200_000,
        path = "Music/song.flac", metadata = FileMetadata(trackGainDb = -8f), clipStartMs = 40_000, clipEndMs = 240_000)
    private val online = file.copy(id = TrackId("yt:one"), uri = "ytm://one", path = null, metadata = null,
        clipStartMs = null, clipEndMs = null, artworkUri = "https://cover")

    @Test fun sourceReplacementPreservesOccurrencesAndClearsFileOnlyGainAndClipping() {
        val reference = SavedSong.from(online).copy(id = "recording:one")
        val original = PlayerConnection.mediaItem(reference.bind(file), "same-occurrence", "Evening mix")
        val result = PlaybackSourceItems.convert(original, PlaybackSourceChoice(reference.online()!!))
        assertEquals("same-occurrence", result.mediaMetadata.extras!!.getString(PlayerConnection.QUEUE_KEY))
        assertEquals("Evening mix", result.mediaMetadata.extras!!.getString(PlayerConnection.CONTEXT_KEY))
        assertEquals(online.uri, result.localConfiguration!!.uri.toString())
        assertEquals(0L, result.clippingConfiguration.startPositionMs)
        assertEquals(androidx.media3.common.C.TIME_END_OF_SOURCE, result.clippingConfiguration.endPositionMs)
        assertNull(ReplayGainCodec.tags(result.mediaMetadata.extras!!.getString(ReplayGainCodec.TAGS)).trackGainDb)
        assertEquals("recording:one", PlayerConnection.track(result).recordingId)
    }

    @Test fun anUnavailableOccurrenceCannotOpenTheOriginalSourceAndCanRecoverLater() {
        val original = PlayerConnection.mediaItem(file, "same")
        val staged = PlaybackSourceItems.convert(original, PlaybackSourceChoice(file, SourceIssue.AMBIGUOUS))
        assertEquals("unavailable", staged.localConfiguration!!.uri.scheme)
        assertEquals(file.uri, PlayerConnection.track(staged).uri)
        assertNotNull(PlaybackSourceItems.error(staged))
        var opens = 0
        val delegate = ByteArrayDataSource(byteArrayOf(1, 2, 3))
        val probe = object : DataSource by delegate {
            override fun open(dataSpec: DataSpec): Long { opens++; return delegate.open(dataSpec) }
        }
        assertThrows(java.io.FileNotFoundException::class.java) { SourceGuard(probe).open(DataSpec(staged.localConfiguration!!.uri)) }
        assertEquals(0, opens)
        val recovered = PlaybackSourceItems.convert(staged, PlaybackSourceChoice(online))
        assertNull(PlaybackSourceItems.error(recovered))
        assertEquals("same", recovered.mediaMetadata.extras!!.getString(PlayerConnection.QUEUE_KEY))
        assertEquals(online.uri, recovered.localConfiguration!!.uri.toString())
    }

    @Test fun duplicateOccurrencesUseOneBulkBridgeAndRemainDistinct() = runBlocking {
        var calls = 0
        val owner = object : MediaCatalogOwner {
            override suspend fun browseTree() = BrowseTree(LibraryIndex.EMPTY)
            override suspend fun playbackSources(tracks: List<Track>, rejected: Set<String>, allowDirect: Boolean): List<PlaybackSourceChoice> {
                calls++; assertEquals(2, tracks.size)
                return tracks.map { PlaybackSourceChoice(online) }
            }
        }
        val original = listOf(PlayerConnection.mediaItem(file, "first"), PlayerConnection.mediaItem(file, "second"))
        val result = PlaybackSourceItems.resolve(owner, original)
        assertEquals(1, calls)
        assertEquals(listOf("first", "second"), result.map { it.mediaMetadata.extras!!.getString(PlayerConnection.QUEUE_KEY) })
        assertEquals(listOf("yt:one", "yt:one"), result.map { it.mediaId })
        assertTrue(PlaybackSourceItems.resolve(owner, emptyList()).isEmpty())
        assertEquals(1, calls)
    }

    @Test fun aZeroStartOpenEndedCueKeepsItsSourceIdentity() {
        val cue = file.copy(clipStartMs = 0, clipEndMs = null)
        val restored = PlayerConnection.track(PlayerConnection.mediaItem(cue))
        assertEquals(0L, restored.clipStartMs)
        assertNull(restored.clipEndMs)
        assertEquals(RecordingSource.from(cue)!!.key, RecordingSource.from(restored)!!.key)
    }
}
