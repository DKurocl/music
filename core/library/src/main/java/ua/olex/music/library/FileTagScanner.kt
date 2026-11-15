package ua.olex.music.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ua.olex.music.model.FileMetadata
import ua.olex.music.model.SongKey
import ua.olex.music.tags.AudioTags
import ua.olex.music.tags.ChannelSource
import ua.olex.music.tags.TagReader

/** One descriptor per changed file, closed even when parsing or seeking fails. No artwork bytes are loaded. */
internal class AndroidTagSource(private val context: Context) {
    fun read(uri: String): AudioTags? {
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: throw IOException("No file descriptor")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream -> TagReader.read(ChannelSource(stream.channel)) }
    }
}

/** Incremental enrichment runs on the repository's IO dispatcher before its atomic cache replacement. */
internal object FileTagScanner {
    const val READER_VERSION = 2

    /** [cue] finds a .cue file beside a long single-file album (folder access needed); null when there is none. */
    suspend fun enrich(rows: List<TrackEntity>, cached: List<TrackEntity>, cue: (String) -> String? = { null }, read: (String) -> AudioTags?): List<TrackEntity> {
        val previous = cached.associateBy { it.id }
        return rows.map { row ->
            currentCoroutineContext().ensureActive()
            val old = previous[row.id]
            if (old != null && old.tagReaderVersion == READER_VERSION && old.uri == row.uri && old.path == row.path &&
                old.modifiedAt == row.modifiedAt && old.sizeBytes == row.sizeBytes) {
                old.copy(scanId = row.scanId, artworkUri = row.artworkUri, addedAt = row.addedAt)
            } else {
                // Permission loss is a failed scan; per-file IO errors fall back and are retried next scan.
                try { withSidecarCue(merge(row, read(row.uri)), cue).copy(tagReaderVersion = READER_VERSION) }
                catch (_: IOException) { row }
            }
        }
    }

    /** Albums ripped to one file are usually longer than 10 minutes; shorter files are never looked at. */
    internal fun withSidecarCue(row: TrackEntity, cue: (String) -> String?): TrackEntity {
        val path = row.path ?: return row
        if (row.durationMs < 10 * 60_000L) return row
        val metadata = row.metadataJson?.let { runCatching { FileMetadataCodec.decode(it) }.getOrNull() } ?: FileMetadata()
        if (metadata.cueSheet != null) return row
        val text = runCatching { cue(path) }.getOrNull() ?: return row
        return row.copy(metadataJson = FileMetadataCodec.encode(metadata.copy(cueSheet = text)))
    }

    internal fun merge(row: TrackEntity, tags: AudioTags?): TrackEntity {
        if (tags == null) return row
        fun String?.known() = this?.takeIf { it.isNotBlank() }
        val artists = tags.artists.ifEmpty { listOf(row.artist) }
        val genres = tags.genres.ifEmpty { listOfNotNull(row.genre) }
        val title = tags.title.known() ?: row.title
        val artist = artists.joinToString("; ")
        val gain = tags.replayGain
        val cover = tags.frontCover?.takeIf { it.offset >= 0 && it.length > 0 && it.offset <= row.sizeBytes - it.length }
        val metadata = FileMetadata(tags.albumArtists, tags.composers,
            gain?.trackGainDb?.takeIf { it.isFinite() }, gain?.trackPeak?.takeIf { it.isFinite() && it > 0 },
            gain?.albumGainDb?.takeIf { it.isFinite() }, gain?.albumPeak?.takeIf { it.isFinite() && it > 0 },
            tags.encoderDelay?.takeIf { it >= 0 }, tags.encoderPadding?.takeIf { it >= 0 }, tags.lyrics,
            tags.first("MUSICBRAINZ_TRACKID"), tags.first("MUSICBRAINZ_ALBUMID"), cover?.offset, cover?.length, cover?.mime, tags.cueSheet, tags.stars)
        return row.copy(
            title = title, artist = artist, album = tags.album.known() ?: row.album,
            albumArtist = tags.albumArtists.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: row.albumArtist,
            durationMs = tags.durationMs?.takeIf { it > 0 } ?: row.durationMs,
            trackNumber = tags.trackNumber ?: row.trackNumber, discNumber = tags.discNumber ?: row.discNumber, year = tags.year ?: row.year,
            codec = tags.codec.known() ?: row.codec, sampleRateHz = tags.sampleRateHz?.takeIf { it > 0 } ?: row.sampleRateHz,
            bitDepth = tags.bitDepth?.takeIf { it > 0 } ?: row.bitDepth, bitrateKbps = tags.bitrateKbps?.takeIf { it > 0 } ?: row.bitrateKbps,
            channels = tags.channels?.takeIf { it > 0 }, genre = genres.joinToString("; ").takeIf { it.isNotEmpty() },
            songKey = SongKey.of(artist, title).value, artistsJson = FileMetadataCodec.strings(artists), genresJson = FileMetadataCodec.strings(genres),
            metadataJson = FileMetadataCodec.encode(metadata),
        )
    }
}
