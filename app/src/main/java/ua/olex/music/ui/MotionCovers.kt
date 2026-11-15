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
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun key(track: Track): String? {
        val album = track.album?.takeIf { it.isNotBlank() } ?: return null
        val owner = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
        val digest = MessageDigest.getInstance("SHA-256").digest(TextFold.fold("$owner\u0000$album").toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /** The album's moving cover, if one was attached. */
    fun file(track: Track): File? = key(track)?.let { File(dir, "$it.mp4") }?.takeIf { it.isFile && it.length() > 0 }
        // Shown now: the last to go when the budget runs over.
        ?.also { it.setLastModified(System.currentTimeMillis()) }

    /** Copies a video into the album's single file; false when it is not a video or is too large. */
    suspend fun attach(track: Track, video: Uri): Boolean = withContext(Dispatchers.IO) {
        val key = key(track) ?: return@withContext false
        if (resolver.getType(video)?.startsWith("video/") != true) return@withContext false
        val target = File(dir, "$key.mp4"); val tmp = File(dir, "$key.tmp")
        val ok = runCatching {
            resolver.openInputStream(video)?.use { input -> tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_BYTES); out.write(buffer, 0, n) }
            } } != null
        }.getOrDefault(false)
        // The owner's own video is theirs, not cache: marked so the budget never drops it.
        if (ok && tmp.renameTo(target)) { File(dir, "$key.own").createNewFile(); _revision.value++; true } else { tmp.delete(); false }
    }

    /** Keeps a joined video (from Apple Music) as the album's single file. */
    suspend fun attachBytes(track: Track, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val key = key(track) ?: return@withContext false
        if (bytes.size > MAX_BYTES) return@withContext false
        val target = File(dir, "$key.mp4"); val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        if (tmp.renameTo(target)) { File(dir, "$key.own").delete(); trim(); _revision.value++; true } else { tmp.delete(); false }
    }

    fun remove(track: Track) { key(track)?.let { File(dir, "$it.own").delete() }; file(track)?.delete(); _revision.value++ }

    /** Drops the fetched moving covers shown longest ago while they take more than their share of the budget. */
    fun trim() {
        val limit = ua.olex.music.playback.CacheBudget.motionBytes(context)
        val fetched = dir.listFiles { f -> f.name.endsWith(".mp4") && !File(dir, f.nameWithoutExtension + ".own").exists() }.orEmpty().sortedBy { it.lastModified() }
        var total = fetched.sumOf { it.length() }
        for (f in fetched) { if (total <= limit) break; total -= f.length(); f.delete() }
    }

    fun bytes(): Long = dir.listFiles { f -> f.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0L

    companion object { const val MAX_BYTES = 40L * 1024 * 1024 }
}
