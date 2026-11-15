package ua.olex.music.model

/** Where a track's audio comes from. A song can exist in several places; see [SongKey]. */
enum class Origin(val prefix: String) {
    LOCAL("local"), YOUTUBE("yt"), SOUNDCLOUD("sc");

    companion object {
        fun of(id: String): Origin? = entries.firstOrNull { id.startsWith(it.prefix + ":") }
    }
}

/**
 * The id every layer uses for a track: "<origin>:<id inside the origin>", for example
 * "local:1432", "yt:dQw4w9WgXcQ". It is also the Media3 mediaId, so the queue, the session and
 * Android Auto all speak about the same thing.
 */
@JvmInline
value class TrackId(val value: String) {
    val origin: Origin get() = Origin.of(value) ?: error("Unknown origin in '$value'")
    val raw: String get() = value.substringAfter(':')

    companion object {
        fun of(origin: Origin, raw: String) = TrackId("${origin.prefix}:$raw")
    }
}

/** What the audio is, as far as known. Null fields were not reported by the source. */
data class AudioFormat(
    val codec: String,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val channels: Int? = null,
) {
    val lossless: Boolean get() = codec.lowercase() in LOSSLESS
    /** Above CD quality: more than 16 bits or more than 48 kHz. */
    val hiRes: Boolean get() = lossless && ((bitDepth ?: 16) > 16 || (sampleRateHz ?: 44_100) > 48_000)

    /** "FLAC 24/96", "MP3 320", "Opus 160". */
    val label: String
        get() = buildString {
            append(codec.uppercase().replace("OPUS", "Opus").replace("VORBIS", "Vorbis"))
            if (lossless && sampleRateHz != null) {
                val khz = sampleRateHz / 1000.0
                append(' ')
                if (bitDepth != null) append(bitDepth).append('/')
                append(if (khz % 1.0 == 0.0) khz.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, khz))
                if (bitDepth == null) append(" kHz")
            } else if (lossless && bitDepth != null) {
                append(' ').append(bitDepth).append("-bit")
            } else if (bitrateKbps != null) append(' ').append(bitrateKbps)
        }

    /** Ranks sources of one song: lossless first, then resolution, then bitrate. */
    val rank: Long
        get() = (if (lossless) 1_000_000_000L else 0L) +
            (if (lossless) (bitDepth ?: 16) * 1_000_000L + (sampleRateHz ?: 44_100) else (bitrateKbps ?: 0) * 1L)

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "ape", "wavpack", "wv", "tta", "dsd", "dsf", "pcm")
    }
}

data class Track(
    val id: TrackId,
    /** What the player opens: a content:// or file:// URI for local files; empty when resolved on demand. */
    val uri: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val artworkUri: String? = null,
    val format: AudioFormat? = null,
    /** Local files only: the path relative to the storage root, for the folder view and the organizer. */
    val path: String? = null,
    val genre: String? = null,
    val artists: List<String> = listOf(artist),
    val genres: List<String> = listOfNotNull(genre),
    val metadata: FileMetadata? = null,
    /** When the file came into the library (epoch ms), for "Recently added"; null when unknown. */
    val addedAt: Long? = null,
    /** A track cut from a longer file (a cue sheet): where it starts and ends in that file, in ms. */
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
) {
    // Browsing does not need song matching; defer normalization until a source lookup asks for it.
    val key: SongKey by lazy(LazyThreadSafetyMode.PUBLICATION) { SongKey.of(artist, title) }
}

/** File-derived information retained for gain, gapless, artwork and lyrics consumers. Unknown stays null. */
data class FileMetadata(
    val albumArtists: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val trackGainDb: Float? = null, val trackPeak: Float? = null,
    val albumGainDb: Float? = null, val albumPeak: Float? = null,
    val encoderDelay: Int? = null, val encoderPadding: Int? = null,
    val lyrics: String? = null,
    val musicBrainzTrackId: String? = null, val musicBrainzAlbumId: String? = null,
    val coverOffset: Long? = null, val coverLength: Int? = null, val coverMime: String? = null,
    /** An embedded cue sheet: the file is a whole album, split into tracks by [CueSheet]. */
    val cueSheet: String? = null,
    /** Stars (1-5) written in the file by this or another app; null when the file has none. */
    val rating: Int? = null,
)
