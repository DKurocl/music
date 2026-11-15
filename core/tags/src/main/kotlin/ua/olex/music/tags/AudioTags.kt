package ua.olex.music.tags

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random access to a file's bytes: a file channel on the phone, a byte array in tests. */
interface ByteSource {
    val size: Long
    /** Reads up to [length] bytes at [position]; returns how many, or -1 at the end. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}

class ByteArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(length, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), into, offset, n)
        return n
    }
}

/** A file opened by the caller (`FileInputStream(fd).channel`); the caller closes it. */
class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()
    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(into, offset, length), position)
}

/**
 * A picture inside the file, as a byte range: the scanner never loads artwork, the artwork cache
 * reads the range when a cover is first shown. [kind] is the ID3/FLAC picture type (3 = front cover).
 */
data class Picture(val offset: Long, val length: Int, val mime: String?, val kind: Int) {
    val frontCover: Boolean get() = kind == 3
}

/** Loudness correction from the tags, in dB to add; peaks are linear (1.0 = full scale). */
data class ReplayGain(val trackGainDb: Float?, val trackPeak: Float?, val albumGainDb: Float?, val albumPeak: Float?)

/**
 * What a file says about itself: the audio (read from the stream headers, not guessed from the
 * name) and every tag. Tag names are the same for all formats, the Vorbis-comment names in upper
 * case (TITLE, ARTIST, ALBUMARTIST, TRACKNUMBER, REPLAYGAIN_TRACK_GAIN, MUSICBRAINZ_ALBUMID, ...);
 * a name can have several values (two artists, three genres).
 */
data class AudioTags(
    /** "flac", "mp3", "ogg", "mp4", "wav". */
    val container: String,
    /** "flac", "mp3", "opus", "vorbis", "aac", "alac", "pcm". */
    val codec: String?,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val durationMs: Long? = null,
    val bitrateKbps: Int? = null,
    val fields: Map<String, List<String>> = emptyMap(),
    val pictures: List<Picture> = emptyList(),
    /** Samples the encoder added before and after the music (MP3 LAME, AAC iTunSMPB): gapless playback trims them. */
    val encoderDelay: Int? = null,
    val encoderPadding: Int? = null,
) {
    fun all(name: String): List<String> = fields[name].orEmpty()
    fun first(name: String): String? = fields[name]?.firstOrNull()

    val title: String? get() = first("TITLE")
    val album: String? get() = first("ALBUM")
    /** Every credited artist. "A; B" in one value is two artists; "AC/DC" and "Simon & Garfunkel" are one. */
    val artists: List<String> get() = people(all("ARTISTS").ifEmpty { all("ARTIST") })
    val albumArtists: List<String> get() = people(all("ALBUMARTIST"))
    val composers: List<String> get() = people(all("COMPOSER"))
    val genres: List<String> get() = people(all("GENRE"))
    val trackNumber: Int? get() = number("TRACKNUMBER")
    val trackTotal: Int? get() = total("TRACKNUMBER") ?: number("TRACKTOTAL")
    val discNumber: Int? get() = number("DISCNUMBER")
    val discTotal: Int? get() = total("DISCNUMBER") ?: number("DISCTOTAL")
    /** The year of the recording, from the first date field that has one. */
    val year: Int? get() = listOf("ORIGINALDATE", "ORIGINALYEAR", "DATE", "YEAR").firstNotNullOfOrNull { n -> first(n)?.let(YEAR::find)?.value?.toIntOrNull() }
    val compilation: Boolean get() = first("COMPILATION")?.trim() == "1"
    /** Timed lyrics win over plain ones: SYLT (as LRC) or an LRC-looking LYRICS tag. */
    val lyrics: String? get() = first("SYNCEDLYRICS") ?: first("LYRICS")
    /** An embedded cue sheet (FLAC CUESHEET comment), for one-file albums. */
    val cueSheet: String? get() = first("CUESHEET")
    val frontCover: Picture? get() = pictures.firstOrNull { it.frontCover } ?: pictures.firstOrNull()

    /**
     * ReplayGain 2.0 tags, or Opus R128 gains converted to the same scale (R128 is relative to
     * -23 LUFS, ReplayGain to -18 LUFS: +5 dB). Null when the file has neither.
     */
    val replayGain: ReplayGain?
        get() {
            fun db(name: String) = first(name)?.let(DECIMAL::find)?.value?.toFloatOrNull()
            fun r128(name: String) = first(name)?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
            val gain = ReplayGain(
                db("REPLAYGAIN_TRACK_GAIN") ?: r128("R128_TRACK_GAIN"), db("REPLAYGAIN_TRACK_PEAK"),
                db("REPLAYGAIN_ALBUM_GAIN") ?: r128("R128_ALBUM_GAIN"), db("REPLAYGAIN_ALBUM_PEAK"),
            )
            return gain.takeIf { it.trackGainDb != null || it.albumGainDb != null }
        }

    private fun number(name: String) = first(name)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    private fun total(name: String) = first(name)?.substringAfter('/', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    private fun people(values: List<String>) = values.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private companion object {
        val YEAR = Regex("""\d{4}""")
        val DECIMAL = Regex("""[-+]?\d+(\.\d+)?""")
    }
}
