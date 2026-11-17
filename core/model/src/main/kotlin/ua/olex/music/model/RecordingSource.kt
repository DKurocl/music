package ua.olex.music.model

/** Persisted source categories; numeric values are part of the user database format. */
enum class RecordingSourceKind(val value: Int) {
    LOCAL(0), YOUTUBE(1), MISSING_LOCAL(2);
}

/** One source of a recording, including enough evidence to reject a namesake or another take. */
data class RecordingSource(
    val kind: RecordingSourceKind,
    val songKey: String,
    val uri: String? = null,
    val path: String? = null,
    val durationMs: Long = 0,
    val musicBrainzId: String? = null,
    val qualityRank: Long = 0,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
    val artworkUri: String? = null,
) {
    init {
        require(!uri.isNullOrBlank() || !path.isNullOrBlank()) { "A source needs an address or path" }
        require(kind != RecordingSourceKind.YOUTUBE || uri?.startsWith(SavedSong.ONLINE_PREFIX) == true) { "Invalid YouTube source" }
        require(kind != RecordingSourceKind.YOUTUBE || uri?.removePrefix(SavedSong.ONLINE_PREFIX)?.isNotBlank() == true)
        require(durationMs >= 0 && qualityRank >= 0)
        require(clipStartMs == null || clipStartMs >= 0)
        require(clipEndMs == null || (clipStartMs != null && clipEndMs >= clipStartMs))
    }

    /** A path identifies a local copy across MediaStore renumbering; CUE occurrences keep their clip. */
    val key: String get() = when (kind) {
        RecordingSourceKind.YOUTUBE -> "youtube:${uri!!.removePrefix(SavedSong.ONLINE_PREFIX)}"
        else -> {
            val address = path?.takeIf { it.isNotBlank() }?.let { "file:$it" } ?: "uri:$uri"
            clipStartMs?.let { "clip:$it:${clipEndMs ?: "end"}:$address" } ?: address
        }
    }

    companion object {
        fun from(track: Track): RecordingSource? {
            val online = track.uri.startsWith(SavedSong.ONLINE_PREFIX) || (track.uri.isBlank() && track.id.value.startsWith("yt:"))
            val uri = if (online) track.uri.takeIf { it.startsWith(SavedSong.ONLINE_PREFIX) }
                ?: SavedSong.ONLINE_PREFIX + track.id.value.removePrefix("yt:") else track.uri.takeIf { it.isNotBlank() }
            val path = track.path?.takeIf { it.isNotBlank() }
            if (uri == null && path == null) return null
            return RecordingSource(if (online) RecordingSourceKind.YOUTUBE else RecordingSourceKind.LOCAL,
                track.key.value, uri, if (online) null else path, track.durationMs.coerceAtLeast(0),
                track.metadata?.musicBrainzTrackId?.takeIf { !online && track.clipStartMs == null && it.isNotBlank() },
                if (online) 0 else track.format?.rank?.coerceAtLeast(0) ?: 0,
                track.clipStartMs.takeUnless { online }, track.clipEndMs.takeUnless { online }, track.artworkUri)
        }

        /** Old snapshots lack a playable local URI; resolve their path against the current scan. */
        fun legacy(song: SavedSong): RecordingSource? = when {
            song.path.startsWith(SavedSong.ONLINE_PREFIX) -> RecordingSource(RecordingSourceKind.YOUTUBE,
                song.songKey, uri = song.path, durationMs = song.durationMs.coerceAtLeast(0), artworkUri = song.artworkUri)
            song.path.isNotBlank() -> RecordingSource(if (song.id.startsWith(M3uPlaylist.MISSING_ID_PREFIX)) RecordingSourceKind.MISSING_LOCAL else RecordingSourceKind.LOCAL,
                song.songKey, path = song.path, durationMs = song.durationMs.coerceAtLeast(0), artworkUri = song.artworkUri)
            else -> null
        }
    }
}

/** A persisted identity and its source evidence. Earlier durable identities win a confirmed merge. */
data class RecordingCandidate(val id: String, val createdAt: Long, val sources: List<RecordingSource>,
    val legacySongKey: String? = null, val legacyDurationMs: Long = 0)

/** Conservative recording reconciliation, independent of Room, Android and network clients. */
object RecordingMatcher {
    private val generic = setOf("", "unknown", "unknownartist", "unknowntitle", "track", "track1", "track01")
    /**
     * Returns an oldest-first merge group, or nothing when the metadata is ambiguous. Unknown
     * duration requires an exact source. Explicitly linked credits may differ, but the take may not.
     */
    fun group(incoming: List<RecordingSource>, candidates: List<RecordingCandidate>): List<RecordingCandidate> {
        if (incoming.isEmpty() || !consistent(incoming)) return emptyList()
        val matches = candidates.filter { candidate ->
            consistent(evidence(candidate) + incoming) && evidence(candidate).any { old -> incoming.any { same(old, it) } }
        }
        if (matches.isEmpty()) return emptyList()
        val exact = matches.filter { c -> c.sources.any { old -> incoming.any { it.key == old.key && same(old, it) } } }
        val selected = if (consistent(matches.flatMap(::evidence) + incoming)) matches
            else if (exact.size == 1) exact else return emptyList()
        return selected.sortedWith(compareBy<RecordingCandidate> { it.createdAt }.thenBy { it.id })
    }

    // Early saved online rows had metadata but no address. Positive duration can identify them
    // on a later confirmed playback; this comparison token is never returned as a playable source.
    private fun evidence(candidate: RecordingCandidate): List<RecordingSource> = candidate.sources.ifEmpty {
        val key = candidate.legacySongKey
        if (key == null || candidate.legacyDurationMs <= 0) emptyList()
        else listOf(RecordingSource(RecordingSourceKind.LOCAL, key, uri = "metadata-ref:${candidate.id}", durationMs = candidate.legacyDurationMs))
    }

    private fun same(a: RecordingSource, b: RecordingSource): Boolean {
        val exact = a.key == b.key
        if (exact && (a.kind == RecordingSourceKind.MISSING_LOCAL || b.kind == RecordingSourceKind.MISSING_LOCAL)) return true
        if (a.songKey != b.songKey) return false
        if (!exact && (!useful(a.songKey) || !useful(b.songKey))) return false
        if (a.durationMs <= 0 || b.durationMs <= 0) return exact
        return kotlin.math.abs(a.durationMs - b.durationMs) <= SongKey.LENGTH_TOLERANCE_MS
    }

    private fun useful(key: String): Boolean {
        return key.substringBefore('|').replace(" ", "") !in generic && key.substringAfter('|').replace(" ", "") !in generic
    }

    private fun consistent(sources: List<RecordingSource>): Boolean {
        val real = sources.filter { it.kind != RecordingSourceKind.MISSING_LOCAL }
        if (real.map { it.songKey.substringAfter('|') }.distinct().size > 1) return false
        if (real.mapNotNull { it.musicBrainzId?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf(String::isNotEmpty) }.distinct().size > 1) return false
        val lengths = real.map { it.durationMs }.filter { it > 0 }
        if (lengths.isNotEmpty() && lengths.max() - lengths.min() > SongKey.LENGTH_TOLERANCE_MS) return false
        val clips = real.filter { it.kind == RecordingSourceKind.LOCAL && it.path != null && it.clipStartMs != null }
        if (clips.groupBy { it.path }.values.any { at -> at.map { it.clipStartMs }.distinct().size > 1 }) return false
        return true
    }
}

/** Versioned bounded queue metadata. It carries source references, never audio or artwork bytes. */
object RecordingSourcesCodec {
    const val MAX_SOURCES = 32
    private const val MAX_CHARS = 65_536

    fun encode(sources: List<RecordingSource>): String {
        require(sources.size <= MAX_SOURCES)
        return buildString {
            append("1;").append(sources.size).append(';')
            for (s in sources) for (field in listOf(s.kind.value.toString(), s.songKey, s.uri, s.path,
                s.durationMs.toString(), s.musicBrainzId, s.qualityRank.toString(), s.clipStartMs?.toString(), s.clipEndMs?.toString(), s.artworkUri)) {
                if (field == null) append("-1:") else append(field.length).append(':').append(field)
            }
        }.also { require(it.length <= MAX_CHARS) }
    }

    fun decode(text: String?): List<RecordingSource> {
        if (text == null) return emptyList()
        require(text.length <= MAX_CHARS)
        var at = 0
        fun number(separator: Char): Int {
            val end = text.indexOf(separator, at)
            require(end >= at)
            return text.substring(at, end).toInt().also { at = end + 1 }
        }
        fun field(): String? {
            val size = number(':')
            if (size == -1) return null
            require(size >= 0 && size <= text.length - at)
            return text.substring(at, at + size).also { at += size }
        }
        require(number(';') == 1)
        val count = number(';')
        require(count in 0..MAX_SOURCES)
        val sources = List(count) {
            val value = field()!!.toInt()
            val kind = RecordingSourceKind.entries.firstOrNull { it.value == value } ?: throw IllegalArgumentException("Unknown source kind")
            RecordingSource(kind,
                field()!!, field(), field(), field()!!.toLong(), field(), field()!!.toLong(), field()?.toLong(), field()?.toLong(), field())
        }
        require(at == text.length)
        return sources
    }
}
