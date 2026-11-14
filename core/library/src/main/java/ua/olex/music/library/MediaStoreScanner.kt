package ua.olex.music.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ua.olex.music.model.Origin
import ua.olex.music.model.SongKey
import ua.olex.music.model.TrackId

/**
 * The first scanner: what Android's own media index knows. It is instant and needs no native
 * code, but it only knows what MediaStore parsed (one artist, no ReplayGain, no embedded lyrics).
 * The file scanner with TagLib replaces it behind [LibraryRepository] (docs/ROADMAP.md, M1).
 */
internal object MediaStoreScanner {
    private val COLUMNS = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.CD_TRACK_NUMBER,
        MediaStore.Audio.Media.DISC_NUMBER, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.BITRATE, MediaStore.Audio.Media.SAMPLERATE,
        MediaStore.Audio.Media.BITS_PER_SAMPLE, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.GENRE,
    )
    private val ALBUM_ART = MediaStore.AUTHORITY_URI.buildUpon().appendPath("external").appendPath("audio").appendPath("albumart").build()

    suspend fun scan(context: Context, scanId: Long): List<TrackEntity> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = ArrayList<TrackEntity>()
        // A failed provider query is not an empty library: only a complete scan may replace it.
        val cursor = context.contentResolver.query(collection, COLUMNS, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)
            ?: throw IOException("MediaStore did not return a cursor")
        cursor.use { c ->
            val col = COLUMNS.associateWith { c.getColumnIndexOrThrow(it) }
            fun text(name: String) = c.getString(col.getValue(name))?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
            fun number(name: String) = col.getValue(name).let { if (c.isNull(it)) null else c.getLong(it) }
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = c.getLong(col.getValue(MediaStore.Audio.Media._ID))
                val file = text(MediaStore.Audio.Media.DISPLAY_NAME).orEmpty()
                val title = text(MediaStore.Audio.Media.TITLE) ?: file.substringBeforeLast('.')
                val artist = text(MediaStore.Audio.Media.ARTIST) ?: "Unknown artist"
                val mime = text(MediaStore.Audio.Media.MIME_TYPE)
                out += TrackEntity(
                    id = TrackId.of(Origin.LOCAL, id.toString()).value,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = title, artist = artist,
                    album = text(MediaStore.Audio.Media.ALBUM), albumArtist = text(MediaStore.Audio.Media.ALBUM_ARTIST),
                    durationMs = number(MediaStore.Audio.Media.DURATION) ?: 0,
                    // "3" or "3/12"
                    trackNumber = text(MediaStore.Audio.Media.CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
                    discNumber = text(MediaStore.Audio.Media.DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
                    year = number(MediaStore.Audio.Media.YEAR)?.toInt()?.takeIf { it > 0 },
                    artworkUri = number(MediaStore.Audio.Media.ALBUM_ID)?.let { ContentUris.withAppendedId(ALBUM_ART, it).toString() },
                    codec = codec(mime, file),
                    sampleRateHz = number(MediaStore.Audio.Media.SAMPLERATE)?.toInt()?.takeIf { it > 0 },
                    bitDepth = number(MediaStore.Audio.Media.BITS_PER_SAMPLE)?.toInt()?.takeIf { it > 0 },
                    bitrateKbps = number(MediaStore.Audio.Media.BITRATE)?.let { (it / 1000).toInt() }?.takeIf { it > 0 },
                    path = text(MediaStore.Audio.Media.RELATIVE_PATH)?.let { it + file },
                    songKey = SongKey.of(artist, title).value,
                    modifiedAt = number(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0,
                    sizeBytes = number(MediaStore.Audio.Media.SIZE) ?: 0,
                    scanId = scanId,
                    genre = text(MediaStore.Audio.Media.GENRE),
                )
            }
        }
        return out
    }

    /** The codec by MIME type, and by the file name where the type is a container. */
    internal fun codec(mime: String?, file: String): String? {
        val ext = file.substringAfterLast('.', "").lowercase()
        return when (mime?.lowercase()) {
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/opus" -> "opus"
            "audio/ogg", "application/ogg" -> if (ext == "opus") "opus" else "vorbis"
            "audio/x-wav", "audio/wav", "audio/vnd.wave" -> "wav"
            "audio/aiff", "audio/x-aiff" -> "aiff"
            "audio/alac" -> "alac"
            // MP4/M4A may contain AAC or ALAC. A container is not evidence of an audio codec.
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> null
            "audio/aac", "audio/mp4a-latm" -> "aac"
            else -> ext.ifEmpty { null }
        }
    }
}
