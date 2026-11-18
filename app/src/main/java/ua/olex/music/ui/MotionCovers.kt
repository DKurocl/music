package ua.olex.music.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track
import java.io.File
import java.security.MessageDigest

/**
 * Moving covers, one file per album: a short looping video the owner attaches to an album once, kept
 * in the app's own storage and played for every song of that album (by album and album artist, so
 * every copy and every rescan finds it). Up to [MAX_BYTES] each. Nothing is fetched from anywhere.
 */
class MotionCovers(private val context: Context) {
    private val dir = File(context.filesDir, "motion").apply { mkdirs() }
    private val resolver = context.contentResolver
    private val writes = Any()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    /**
     * One key per album, the same for every song on it. Without an album artist tag the first artist
     * of the song stands in: "Travis Scott, Drake" and "Travis Scott" are one album's songs, and the
     * whole artist line used to give each guest song a key (and so no moving cover) of its own.
     */
    private fun key(track: Track): String? {
        val album = track.album?.takeIf { it.isNotBlank() } ?: return null
        val owner = track.albumArtist?.takeIf { it.isNotBlank() } ?: firstArtist(track.artist)
        return digest("$owner\u0000$album")
    }

    /** The key songs had before (the whole artist line), so covers kept then still show. */
    private fun oldKey(track: Track): String? {
        val album = track.album?.takeIf { it.isNotBlank() } ?: return null
        return digest("${track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist}\u0000$album")
    }

    private fun digest(s: String) = MessageDigest.getInstance("SHA-256").digest(TextFold.fold(s).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    /** The album's moving cover, if one was attached. */
    fun file(track: Track): File? = (listOfNotNull(key(track), oldKey(track)).distinct()).firstNotNullOfOrNull { k ->
        File(dir, "$k.mp4").takeIf { it.isFile && it.length() > 0 }
    }
        // Shown now: the last to go when the budget runs over.
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    /** Copies a video into the album's single file; false when it is not a video or is too large. */
    suspend fun attach(track: Track, video: Uri): Boolean = withContext(Dispatchers.IO) {
        val key = key(track) ?: return@withContext false
        if (resolver.getType(video)?.startsWith("video/") != true) return@withContext false
        val target = File(dir, "$key.mp4"); val tmp = File.createTempFile(key, ".tmp", dir)
        val ok = runCatching {
            resolver.openInputStream(video)?.use { input -> tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_BYTES); out.write(buffer, 0, n) }
            } } != null
        }.getOrDefault(false)
        // The owner's own video is theirs, not cache: marked so the budget never drops it.
        try {
            synchronized(writes) {
                if (!ok || tmp.length() == 0L || !tmp.renameTo(target)) return@synchronized false
                File(dir, "$key.own").createNewFile()
                _revision.value++
                true
            }
        } finally { tmp.delete() }
    }

    /** Keeps a joined video (from Apple Music) as the album's single file. */
    suspend fun attachBytes(track: Track, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val key = key(track) ?: return@withContext false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext false
        val target = File(dir, "$key.mp4")
        val tmp = File.createTempFile(key, ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            synchronized(writes) {
                // A manual attachment can finish while an automatic fetch is still downloading.
                if (File(dir, "$key.own").exists() || !tmp.renameTo(target)) return@synchronized false
                trim()
                _revision.value++
                target.exists()
            }
        } finally { tmp.delete() }
    }

    fun remove(track: Track) = synchronized(writes) {
        listOfNotNull(key(track), oldKey(track)).distinct().forEach {
            File(dir, "$it.mp4").delete()
            File(dir, "$it.own").delete()
        }
        _revision.value++
    }

    /** Removes every fetched moving cover (Clear cache); the owner's own videos stay. */
    fun clearFetched() = synchronized(writes) {
        fetchedFiles()?.forEach { it.delete() }
        _revision.value++
    }

    /** Drops the fetched moving covers shown longest ago while they take more than their share of the budget. */
    fun trim() = synchronized(writes) {
        val limit = ua.olex.music.playback.CacheBudget.motionBytes(context)
        val fetched = fetchedFiles().orEmpty().sortedBy { it.lastModified() }
        var total = fetched.sumOf { it.length() }
        for (f in fetched) { if (total <= limit) break; total -= f.length(); f.delete() }
    }

    private fun fetchedFiles() = dir.listFiles { f -> f.name.endsWith(".mp4") && !File(dir, f.nameWithoutExtension + ".own").exists() }

    fun fetchedBytes(): Long = synchronized(writes) { fetchedFiles().orEmpty().sumOf { it.length() } }

    companion object { const val MAX_BYTES = 40L * 1024 * 1024 }

}

/** "Travis Scott, Drake", "A & B", "A feat. B", "A x B" give "Travis Scott", "A". */
internal fun firstArtist(artist: String): String =
    artist.split(Regex(""",|&|;|\s+(feat\.?|ft\.?|featuring|x|with)\s+""", RegexOption.IGNORE_CASE)).first().trim().ifEmpty { artist }

/** The album and folder own the cover together. A different album or folder starts on its still. */
internal data class MotionCoverOwner(val album: String?, val artist: String?, val folder: String?, val fallback: String?)

internal fun motionCoverOwner(track: Track): MotionCoverOwner {
    val album = track.album?.let(TextFold::fold)?.takeIf { it.isNotBlank() }
    val folder = track.path?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }
    return MotionCoverOwner(
        album,
        album?.let { TextFold.fold(track.albumArtist?.takeIf(String::isNotBlank) ?: firstArtist(track.artist)) },
        folder,
        track.uri.takeIf { album == null && folder == null },
    )
}
