package ua.olex.music.model

import java.math.BigDecimal
import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One local-playlist reference with its source line, optional EXTINF label and duration. */
data class M3uEntry(val path: String, val line: Int, val label: String? = null, val durationMs: Long = 0)
/** The original entries stay intact while the owner edits the import's base folder. */
data class M3uDocument(val name: String?, val entries: List<M3uEntry>, val warnings: List<String>)
/** Explicit preview outcomes: unavailable local references are kept, unsupported/repeated ones reported. */
enum class M3uMatch { EXACT, RELOCATED, MISSING, AMBIGUOUS, UNSUPPORTED, NEEDS_FOLDER, REPEATED }
/** An unresolved local path remains a durable song; unsupported URIs are reported, never opened. */
data class M3uResolved(val entry: M3uEntry, val match: M3uMatch, val song: SavedSong?)
/** Ordered, deduplicated import candidates and precomputed counts for the preview UI. */
data class M3uImport(val entries: List<M3uResolved>) {
    val songs: List<SavedSong> = entries.mapNotNull { it.song }
    private val counts = entries.groupingBy { it.match }.eachCount()
    fun count(match: M3uMatch) = counts[match] ?: 0
    val canImport: Boolean = songs.isNotEmpty() && count(M3uMatch.NEEDS_FOLDER) == 0
}

/** Bounded UTF-8 M3U8 text codec. Neither parsing nor resolution touches files or the network. */
object M3uPlaylist {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    private val drive = Regex("^[A-Za-z]:/")
    internal const val MISSING_ID_PREFIX = "m3u8-path:"

    fun parse(text: String): M3uDocument {
        require(text.length <= MAX_BYTES) { "Playlist is too large" }
        var name: String? = null
        var label: String? = null
        var duration = 0L
        var pendingLine: Int? = null
        val entries = ArrayList<M3uEntry>()
        val warnings = ArrayList<String>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            val number = index + 1
            require(line.length <= 8192 && '\u0000' !in line) { "Invalid playlist line $number" }
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXT-X-", true) -> throw IllegalArgumentException("HLS is not a local playlist")
                line.startsWith("#PLAYLIST:", true) -> name = line.substringAfter(':').trim().ifBlank { null }
                line.startsWith("#EXTINF:", true) -> {
                    pendingLine?.let { warnings += "Line $it: metadata has no file path" }
                    val value = line.substringAfter(':')
                    label = value.substringAfter(',', "").trim().ifBlank { null }
                    val seconds = value.substringBefore(',').trim().toDoubleOrNull()
                    duration = seconds?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }
                        ?.let { (it * 1000).toLong() } ?: 0L
                    pendingLine = number
                }
                line.startsWith('#') -> {
                    if (line.startsWith("#EXTVLCOPT:", true)) warnings += "Line $number: playback options are not imported"
                }
                else -> {
                    require(entries.size < MAX_ENTRIES) { "Too many playlist entries" }
                    entries += M3uEntry(line, number, label, duration)
                    label = null; duration = 0; pendingLine = null
                }
            }
        }
        pendingLine?.let { warnings += "Line $it: metadata has no file path" }
        return M3uDocument(name, entries, warnings)
    }

    fun resolve(document: M3uDocument, library: LibraryIndex, baseFolder: String? = null): M3uImport {
        val seen = HashSet<String>()
        return M3uImport(document.entries.map { entry ->
            val result = library.playlistPaths.resolve(entry.path, baseFolder)
            val song = when (result.match) {
                M3uMatch.EXACT, M3uMatch.RELOCATED -> SavedSong.from(requireNotNull(result.track))
                M3uMatch.MISSING, M3uMatch.AMBIGUOUS -> missing(entry, result.path)
                else -> null
            }
            if (song != null && !seen.add(song.id)) M3uResolved(entry, M3uMatch.REPEATED, null)
            else M3uResolved(entry, result.match, song)
        })
    }

    /** Absolute file URIs make export independent of the folder chosen in Android's document picker. */
    fun encode(name: String, songs: List<SavedSong>, storageRoot: String): String {
        require(songs.size <= MAX_ENTRIES)
        require(storageRoot.startsWith('/') && '\u0000' !in storageRoot)
        fun label(value: String) = value.replace('\r', ' ').replace('\n', ' ')
        return buildString {
            append("#EXTM3U\n#PLAYLIST:").append(label(name)).append('\n')
            for (song in songs) {
                val path = requireNotNull(PlaylistPathIndex.normalize(song.path, null)) { "A song has no exportable file path" }
                require(!path.needsFolder && path.value.isNotBlank()) { "A relative path needs its playlist folder" }
                val full = if (path.value.startsWith('/')) path.value else storageRoot.trimEnd('/') + "/" + path.value
                val uri = if (drive.containsMatchIn(path.value)) {
                    require('\n' !in path.value && '\r' !in path.value)
                    path.value
                } else fileUri(full)
                val seconds = if (song.durationMs > 0) BigDecimal.valueOf(song.durationMs, 3).stripTrailingZeros().toPlainString() else "-1"
                append("#EXTINF:").append(seconds).append(',').append(label("${song.artist} - ${song.title}")).append('\n')
                append(uri).append('\n')
                require(length <= MAX_BYTES) { "Export is too large" }
            }
        }.also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Export is too large" } }
    }

    // URI.toASCIIString normalizes Unicode to NFC, which can point at a different Linux filename.
    private fun fileUri(path: String): String = buildString {
        append("file://")
        val hex = "0123456789ABCDEF"
        for (byte in path.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 255
            if (b in 65..90 || b in 97..122 || b in 48..57 || b.toChar() in "/-._~") append(b.toChar())
            else { append('%'); append(hex[b ushr 4]); append(hex[b and 15]) }
        }
    }

    private fun missing(entry: M3uEntry, path: String): SavedSong {
        val fallback = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Unavailable song" }
        val display = entry.label.orEmpty()
        val artist = if (" - " in display) display.substringBefore(" - ") else "Unknown artist"
        val title = display.substringAfter(" - ", display).ifBlank { fallback }
        return SavedSong("$MISSING_ID_PREFIX${path.length}:$path", SongKey.of(artist, title).value,
            path, title, artist, null, entry.durationMs, null)
    }
}

/** Lazy, shared path lookup: exact paths first, then only a unique suffix of at least two segments. */
class PlaylistPathIndex(tracks: List<Track>) {
    internal data class Path(val value: String, val needsFolder: Boolean = false)
    /** A known track is returned only for an unambiguous exact or relocated path. */
    data class Match(val match: M3uMatch, val path: String, val track: Track? = null)
    private val exact = tracks.mapNotNull { track -> track.path?.let { normalize(it, null)?.value }?.let { it to track } }.groupBy({ it.first }, { it.second })
    private val uris = tracks.groupBy { it.uri }
    private val suffixes: Map<String, List<Track>> = buildMap<String, MutableList<Track>> {
        for ((path, rows) in exact) {
            val parts = path.split('/')
            for (start in 0 until parts.size - 1) getOrPut(parts.drop(start).joinToString("/")) { ArrayList() }.addAll(rows)
        }
    }

    fun resolve(raw: String, baseFolder: String? = null): Match {
        if (raw.startsWith("content:", true)) {
            val matches = uris[raw].orEmpty()
            return when (matches.size) {
                1 -> if (matches.single().path.isNullOrBlank()) Match(M3uMatch.UNSUPPORTED, raw)
                    else Match(M3uMatch.EXACT, matches.single().path.orEmpty(), matches.single())
                else -> Match(M3uMatch.UNSUPPORTED, raw)
            }
        }
        val normalized = normalize(raw, baseFolder) ?: return Match(M3uMatch.UNSUPPORTED, raw)
        val path = normalized.value
        if (normalized.needsFolder) return Match(M3uMatch.NEEDS_FOLDER, path)
        fun match(rows: List<Track>, relocated: Boolean): Match = if (rows.size == 1)
            Match(if (relocated) M3uMatch.RELOCATED else M3uMatch.EXACT, path, rows.single())
            else Match(M3uMatch.AMBIGUOUS, path)
        exact[path]?.let { return match(it, false) }
        val parts = path.split('/')
        for (start in 0 until parts.size - 1) suffixes[parts.drop(start).joinToString("/")]?.let { return match(it, true) }
        return Match(M3uMatch.MISSING, path)
    }

    companion object {
        fun validBaseFolder(folder: String): Boolean = folder.isBlank() || normalize(folder, null)?.needsFolder == false
        private val drive = Regex("^[A-Za-z]:/")
        private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private fun decodePath(raw: String): String? = runCatching {
            val bytes = ByteArrayOutputStream()
            var at = 0
            while (at < raw.length) {
                if (raw[at] == '%') {
                    require(at + 2 < raw.length)
                    bytes.write(raw.substring(at + 1, at + 3).toInt(16)); at += 3
                } else {
                    val end = raw.indexOf('%', at).takeIf { it >= 0 } ?: raw.length
                    bytes.write(raw.substring(at, end).toByteArray(Charsets.UTF_8)); at = end
                }
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }.getOrNull()

        internal fun normalize(raw: String, baseFolder: String?): Path? {
            if (raw.isBlank() || raw.length > 8192 || '\u0000' in raw) return null
            var path = raw.trim().replace('\\', '/')
            if (path.startsWith("file:", true)) {
                val uri = runCatching { URI(path) }.getOrNull() ?: return null
                if (!uri.host.isNullOrBlank() && !uri.host.equals("localhost", true)) return null
                if (uri.rawQuery != null || uri.rawFragment != null) return null
                path = decodePath(uri.rawPath ?: return null) ?: return null
                if (path.startsWith('/') && drive.containsMatchIn(path.drop(1))) path = path.drop(1)
            } else if (!drive.containsMatchIn(path) && scheme.containsMatchIn(path)) return null
            if ('\u0000' in path) return null
            val absolute = path.startsWith('/') || drive.containsMatchIn(path)
            if (!absolute && baseFolder == null && path.split('/').contains("..")) return Path(path, true)
            if (!absolute && baseFolder != null) {
                val base = normalize(baseFolder, null)?.takeUnless { it.needsFolder }?.value ?: return null
                path = if (base.isEmpty()) path else "$base/$path"
            }
            for (root in listOf("/storage/emulated/0/", "/storage/self/primary/", "/sdcard/")) {
                if (path == root.trimEnd('/')) { path = ""; break }
                if (path.startsWith(root)) { path = path.removePrefix(root); break }
            }
            val prefix = when {
                drive.containsMatchIn(path) -> path.take(2).also { path = path.drop(2) }
                path.startsWith('/') -> "/"
                else -> ""
            }
            val parts = path.trimStart('/').split('/')
            if (parts.size > 64) return null
            val stack = ArrayList<String>()
            for (part in parts) when (part) {
                "", "." -> Unit
                ".." -> { if (stack.isEmpty()) return null; stack.removeAt(stack.lastIndex) }
                else -> stack += part
            }
            return Path(prefix + (if (prefix.length == 2) "/" else "") + stack.joinToString("/"))
        }
    }
}
