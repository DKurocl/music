package ua.olex.music.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val dir = File(context.filesDir, "motion")
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

    /** Owner files carry their ownership in the name. Older marker-protected files still play. */
    fun file(track: Track, touch: Boolean = true): File? {
        val keys = keys(track)
        for (key in keys) {
            video(key, owned = true).takeIf(::usable)?.let { return it }
            if (marker(key).exists()) video(key).takeIf(::usable)?.let { return it }
        }
        val cached = keys.firstNotNullOfOrNull { video(it).takeIf(::usable) } ?: return null
        if (touch) {
            val now = System.currentTimeMillis()
            if (now - cached.lastModified() >= TOUCH_INTERVAL_MS) cached.setLastModified(now)
        }
        return cached
    }

    private fun keys(track: Track) = listOfNotNull(key(track), oldKey(track)).distinct()
    private fun video(key: String, owned: Boolean = false) = File(dir, if (owned) "$key.owned.mp4" else "$key.mp4")
    private fun marker(key: String) = File(dir, "$key.own")
    private fun usable(file: File) = file.isFile && file.length() > 0
    private fun writableDirectory() = dir.isDirectory || dir.mkdirs() || dir.isDirectory

    /** Copies a video into the album's single file; false when it is not a video or is too large. */
    suspend fun attach(track: Track, video: Uri): Boolean = withContext(Dispatchers.IO) {
        val key = key(track) ?: return@withContext false
        if (resolver.getType(video)?.startsWith("video/") != true || !writableDirectory()) return@withContext false
        val staged = File.createTempFile(key, ".tmp", dir)
        try {
            val source = resolver.openInputStream(video) ?: return@withContext false
            source.use { input ->
                staged.outputStream().use { out ->
                    val buffer = ByteArray(COPY_CHUNK)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        val written = if (count > 0) count else {
                            val byte = input.read()
                            if (byte < 0) break
                            buffer[0] = byte.toByte()
                            1
                        }
                        total += written
                        if (total > MAX_BYTES) return@withContext false
                        out.write(buffer, 0, written)
                    }
                }
            }
            val job = currentCoroutineContext()
            synchronized(writes) {
                job.ensureActive()
                if (!usable(staged) || !staged.renameTo(video(key, owned = true))) return@synchronized false
                // Only the successful rename replaces an owner's existing video.
                val previous = video(key)
                if (!previous.exists() || previous.delete()) marker(key).delete()
                _revision.value++
                true
            }
        } finally { staged.delete() }
    }

    /** Keeps a joined video (from Apple Music) as the album's single file. */
    internal suspend fun attachBytes(track: Track, bytes: ByteArray, revision: Int, allowed: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val key = key(track) ?: return@withContext false
        if (bytes.isEmpty() || bytes.size > MAX_BYTES || !allowed() || _revision.value != revision || !writableDirectory())
            return@withContext false
        val target = video(key)
        val tmp = File.createTempFile(key, ".tmp", dir)
        try {
            val job = currentCoroutineContext()
            tmp.outputStream().use { destination ->
                for (position in bytes.indices step COPY_CHUNK) {
                    job.ensureActive()
                    destination.write(bytes, position, minOf(COPY_CHUNK, bytes.size - position))
                }
            }
            synchronized(writes) {
                job.ensureActive()
                if (!allowed() || _revision.value != revision ||
                    keys(track).any { usable(video(it, owned = true)) || (marker(it).exists() && usable(video(it))) } ||
                    !tmp.renameTo(target)) return@synchronized false
                trim()
                _revision.value++
                target.exists()
            }
        } finally { tmp.delete() }
    }

    suspend fun remove(track: Track): Unit = withContext(Dispatchers.IO) {
        synchronized(writes) {
            keys(track).forEach {
                video(it, owned = true).delete()
                val previous = video(it)
                if (!previous.exists() || previous.delete()) marker(it).delete()
            }
            _revision.value++
        }
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
        for (f in fetched) {
            if (total <= limit) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    private fun fetchedFiles() = dir.listFiles { f ->
        f.name.endsWith(".mp4") && !f.name.endsWith(".owned.mp4") && !marker(f.nameWithoutExtension).exists()
    }

    fun fetchedBytes(): Long = synchronized(writes) { fetchedFiles().orEmpty().sumOf { it.length() } }

    companion object {
        const val MAX_BYTES = 40L * 1024 * 1024
        private const val COPY_CHUNK = 64 * 1024
        private const val TOUCH_INTERVAL_MS = 60 * 60_000L
    }

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
