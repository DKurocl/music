package ua.olex.music.soulseek

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.olex.music.model.TextFold

/**
 * Lossless downloads from Soulseek, one tap from any song or album: logs in with the owner's
 * account, searches, picks the best copy by itself (FLAC first, the right length, a free slot, a
 * fast peer), and saves it under Music/Soulseek/Artist/Album, where the library finds it. From then
 * on the song plays from that file. Progress shows in a notification.
 */
class SoulseekDownloads(private val context: Context, private val onSaved: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("soulseek", Context.MODE_PRIVATE)
    val client = SoulseekClient(scope)
    private val one = Mutex()

    data class Job(val id: Int, val title: String, val status: String, val progress: Float? = null, val done: Boolean = false, val failed: Boolean = false)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    var account: Pair<String, String>?
        get() = prefs.getString("user", null)?.let { u -> prefs.getString("password", null)?.let { u to it } }
        set(value) { prefs.edit().apply { if (value == null) { remove("user"); remove("password") } else { putString("user", value.first); putString("password", value.second) } }.apply(); client.disconnect() }

    /** Finds and saves one song in the best quality the network has. */
    fun song(artist: String, title: String, album: String?, durationMs: Long) = start("$artist · $title") { update ->
        update("Searching Soulseek…", null)
        val results = client.search("$artist $title")
        val best = chooseSong(results, title, durationMs / 1000) ?: error("No lossless copy found on Soulseek")
        save(best, artist, album ?: "Singles", update, 1, 1)
    }

    /** Finds the best copy of a whole album (one peer's folder) and saves every song in it. */
    fun album(artist: String, album: String) = start("$artist · $album") { update ->
        update("Searching Soulseek…", null)
        val files = chooseAlbum(client.search("$artist $album", seconds = 10)) ?: error("No lossless copy of this album found on Soulseek")
        files.forEachIndexed { i, f -> save(f, artist, album, update, i + 1, files.size) }
    }

    private fun start(title: String, work: suspend (update: (String, Float?) -> Unit) -> Unit) {
        val id = (System.currentTimeMillis() % 1_000_000).toInt()
        fun set(job: Job) { _jobs.value = (_jobs.value.filterNot { it.id == id } + job).takeLast(20); notify(job) }
        set(Job(id, title, "Waiting…"))
        scope.launch {
            one.withLock {
                try {
                    val (user, password) = account ?: error("Add your Soulseek account in Settings")
                    if (client.state.value !is SoulseekClient.State.Online) { set(Job(id, title, "Signing in to Soulseek…")); client.login(user, password) }
                    work { status, progress -> set(Job(id, title, status, progress)) }
                    set(Job(id, title, "Saved to Music/Soulseek", 1f, done = true))
                    onSaved()
                } catch (e: Exception) {
                    set(Job(id, title, e.message ?: "The download failed", failed = true))
                }
            }
        }
    }

    private suspend fun save(file: SoulseekClient.Result, artist: String, album: String, update: (String, Float?) -> Unit, n: Int, of: Int) {
        val resolver = context.contentResolver
        val safe = { s: String -> s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Unknown" } }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safe(file.name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Soulseek/${safe(artist)}/${safe(album)}/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Couldn't create the file")
        val prefix = if (of > 1) "$n of $of · " else ""
        try {
            update("${prefix}Waiting for ${file.username}…", null)
            var shown = -1
            resolver.openOutputStream(uri)!!.use { out ->
                client.download(file, out, { got, size ->
                    val percent = (got * 100 / size.coerceAtLeast(1)).toInt()
                    if (percent != shown) { shown = percent; update("${prefix}Downloading $percent%", got.toFloat() / size) }
                })
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    private fun notify(job: Job) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.title).setContentText(job.status).setOnlyAlertOnce(true)
            .setOngoing(!job.done && !job.failed)
            .apply { if (!job.done && !job.failed) setProgress(100, ((job.progress ?: 0f) * 100).toInt(), job.progress == null) }
            .apply { if (job.done) setSmallIcon(android.R.drawable.stat_sys_download_done) }
            .build()
        runCatching { manager.notify(NOTIFICATION_BASE + job.id % 1000, n) }
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_BASE = 4_000
        val LOSSLESS = setOf("flac", "wav", "aiff", "alac", "ape", "wv")

        private fun words(s: String) = TextFold.fold(s).replace(Regex("""[(\[].*?[)\]]"""), " ").split(Regex("""\W+""")).filter { it.length > 1 }.toSet()

        /** The best file for a song: lossless, named like it, the right length, then the quickest peer to start. */
        fun chooseSong(results: List<SoulseekClient.Result>, title: String, seconds: Long): SoulseekClient.Result? {
            val want = words(title)
            return results.filter { it.extension in LOSSLESS && it.size in 2_000_000..400_000_000 }
                .filter { r -> words(r.name).containsAll(want) }
                .filter { r -> seconds <= 0 || r.seconds == null || kotlin.math.abs(r.seconds - seconds) <= 5 }
                .maxByOrNull { r -> (if (r.extension == "flac") 1_000_000L else 0L) + (if (r.slotFree) 500_000L else 0L) - r.queue * 1_000 + r.speed / 1_000 }
        }

        /** The best album: one peer's folder with the most lossless songs, the quickest peer winning ties. */
        fun chooseAlbum(results: List<SoulseekClient.Result>): List<SoulseekClient.Result>? =
            results.filter { it.extension in LOSSLESS }.groupBy { it.username to it.folder }.values
                .filter { it.size >= 3 }
                .maxByOrNull { files -> files.size * 1_000_000L + (if (files.first().slotFree) 500_000L else 0L) + files.first().speed / 1_000 }
                ?.sortedBy { it.name }
    }
}
