package ua.olex.music.soulseek

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
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
    // Signed in only while there is something to download: the server connection is closed two
    // minutes after the last job, so an idle Soulseek never keeps the radio awake.
    private var idle: kotlinx.coroutines.Job? = null

    /** One download as the app shows it. [key] names what is being fetched, so a button finds its own job. */
    data class Job(val id: Int, val key: String, val title: String, val status: String, val progress: Float? = null, val done: Boolean = false, val failed: Boolean = false, val cancelled: Boolean = false) {
        val running get() = !done && !failed && !cancelled
    }
    private val active = HashMap<Int, kotlinx.coroutines.Job>()
    private val lock = Any()
    private var starting: Int? = null
    private var migrating = false
    private val retriesAfterCancellation = HashSet<Int>()
    private fun record(id: Int) = "download:$id"
    private fun stored(id: Int) = prefs.getString(record(id), null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    init {
        _jobs.value = prefs.all.keys.mapNotNull { name ->
            if (!name.startsWith("download:")) return@mapNotNull null
            val id = name.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
            stored(id)?.let { data ->
                val key = data.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val state = data.optString("state")
                Job(id, key, data.optString("title", "Download"), data.optString("status", "Waiting…"),
                    done = state == "done", failed = state == "failed", cancelled = state == "cancelled")
            }
        }.sortedBy { it.id }
    }

    var account: Pair<String, String>?
        get() = prefs.getString("user", null)?.let { u -> prefs.getString("password", null)?.let { u to it } }
        set(value) { prefs.edit().apply { if (value == null) { remove("user"); remove("password") } else { putString("user", value.first); putString("password", value.second) } }.apply(); client.disconnect() }

    /** Finds and saves one song in the best quality the network has. */
    fun song(artist: String, title: String, album: String?, durationMs: Long): Unit = start(songKey(artist, title), "$artist · $title",
        listOf("s", artist, title, album.orEmpty(), durationMs.toString()).joinToString(SEP)) { id, update ->
        // From exact to loose; the first search with a good copy wins.
        val phrases = queries(artist, title)
        val results = ArrayList<SoulseekClient.Result>()
        var best: SoulseekClient.Result? = null
        val partial = prefs.getString("peer-file:$id", null)
        for ((i, q) in phrases.withIndex()) {
            update(if (phrases.size > 1) "Searching Soulseek\u2026 ${i + 1} of ${phrases.size}" else "Searching Soulseek\u2026", null)
            results += client.search(q, seconds = 7)
            val same = partial?.let { key -> results.firstOrNull { peerFileKey(it) == key } }
            best = same ?: chooseSong(results, title, durationMs / 1000)
            if (same != null || (partial == null && best != null)) break
        }
        save(id, best ?: error("Not on Soulseek in lossless or 320 kbps"), artist, album ?: "Singles", update, 1, 1)
    }

    /** Finds the best copy of a whole album (one peer's folder) and saves every song in it. */
    fun album(artist: String, album: String): Unit = start(albumKey(artist, album), "$artist · $album",
        listOf("a", artist, album).joinToString(SEP)) { id, update ->
        update("Searching Soulseek…", null)
        var files: List<SoulseekClient.Result>? = null
        val partial = prefs.getString("peer-file:$id", null)
        for (q in queries(artist, album)) {
            val results = client.search(q, seconds = 10)
            val same = partial?.let { key -> results.firstOrNull { peerFileKey(it) == key } }
            val original = same?.let { candidate ->
                chooseAlbum(results.filter { it.username == candidate.username && it.folder == candidate.folder })
            }
            if (original != null) { files = original; break }
            if (files == null) files = chooseAlbum(results)
            if (partial == null && files != null) break
        }
        if (files == null) error("This album isn't on Soulseek in lossless or 320 kbps")
        files.forEachIndexed { i, f -> save(id, f, artist, album, update, i + 1, files.size) }
    }

    /** For screenshots and tests: shows these jobs as if they ran. */
    @androidx.annotation.VisibleForTesting
    internal fun show(jobs: List<Job>) { _jobs.value = jobs }

    /** The latest job for [key], running or finished. */
    fun job(key: String): Job? = _jobs.value.lastOrNull { it.key == key }

    /** Retry the same durable request, keeping already published album files. */
    fun retry(job: Job) = retry(job.id)
    fun retry(id: Int) {
        synchronized(lock) {
            val old = _jobs.value.firstOrNull { it.id == id && (it.failed || it.cancelled) } ?: return
            val data = stored(id) ?: return
            write(data, old.copy(status = "Waiting…", failed = false, cancelled = false))
            if (id in active) retriesAfterCancellation.add(id)
        }
        requestResume()
    }

    /** Cancel both queued and active work. Completed files stay in the library. */
    fun cancel(id: Int) {
        synchronized(lock) {
            val old = _jobs.value.firstOrNull { it.id == id && it.running } ?: return
            val data = stored(id) ?: return
            write(data, old.copy(status = "Cancelled", cancelled = true))
            active[id]?.cancel()
        }
    }

    fun clearFinished() {
        synchronized(lock) {
            val finished = _jobs.value.filterNot { it.running || it.id in active }
            val edit = prefs.edit()
            finished.forEach { job ->
                edit.remove(record(job.id))
                edit.remove("completed:${job.id}")
                if (prefs.contains("file:${job.id}")) scope.launch { runCatching { cleanInterruptedFile(job.id, discard = true) } }
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_BASE + job.id)
            }
            edit.apply()
            _jobs.update { rows -> rows - finished.toSet() }
        }
    }

    /** Start only from a visible app or an explicit notification interaction. */
    fun requestResume() {
        if (!_jobs.value.any { it.running } && prefs.getStringSet("pending", emptySet()).isNullOrEmpty()) return
        runCatching { context.startForegroundService(android.content.Intent(context, DownloadService::class.java)) }
            .onFailure {
                synchronized(lock) {
                    _jobs.value.filter { it.running && it.id !in active }.forEach { job ->
                        stored(job.id)?.let { write(it, job.copy(status = "Waiting for Music to open")) }
                    }
                }
            }
    }

    /** Called by the foreground service once it has promoted itself. */
    internal fun resume() {
        synchronized(lock) {
            migrating = true
            try {
                prefs.getStringSet("pending", emptySet()).orEmpty().toList().forEach(::dispatch)
                prefs.edit().remove("pending").apply()
            } finally {
                migrating = false
            }
            _jobs.value.filter { it.running }.forEach { job ->
                stored(job.id)?.optString("request")?.let { request ->
                    startStored(job.id, request)
                }
            }
        }
    }

    internal fun suspendTransfers() {
        synchronized(lock) {
            retriesAfterCancellation.clear()
            _jobs.value.filter { it.id in active && it.running }.forEach { job ->
                stored(job.id)?.let { write(it, job.copy(status = "Paused. Open Music to resume", progress = null)) }
            }
            active.values.toList().forEach { it.cancel() }
        }
    }

    private fun startStored(id: Int, request: String) {
        starting = id
        try { dispatch(request) } finally { starting = null }
    }

    private fun dispatch(request: String) {
        val parts = request.split(SEP)
        when (parts.firstOrNull()) {
            "s" -> if (parts.size >= 5) song(parts[1], parts[2], parts[3].ifEmpty { null }, parts[4].toLongOrNull() ?: 0L)
            "a" -> if (parts.size >= 3) album(parts[1], parts[2])
        }
    }

    private fun write(data: org.json.JSONObject, job: Job) {
        val state = when {
            job.cancelled -> "cancelled"
            job.failed -> "failed"
            job.done -> "done"
            else -> "waiting"
        }
        data.put("state", state).put("status", job.status)
        prefs.edit().putString(record(job.id), data.toString()).apply()
        showStatus(job)
    }

    private fun showStatus(job: Job) {
        _jobs.update { rows -> (rows.filterNot { it.id == job.id } + job).sortedBy { it.id } }
        notify(job)
    }

    private fun start(
        key: String, title: String, request: String,
        work: suspend (Int, (String, Float?) -> Unit) -> Unit,
    ) {
        synchronized(lock) {
            val resumedId = starting
            if (resumedId == null) {
                if (_jobs.value.any { it.key == key && it.running }) return
                val id = prefs.getInt("next-id", 0) + 1
                val data = org.json.JSONObject().put("key", key).put("title", title).put("request", request)
                prefs.edit().putInt("next-id", id).apply()
                write(data, Job(id, key, title, "Waiting…"))
                if (!migrating) requestResume()
                return
            }
            val id = resumedId
            if (id in active) return
            val data = stored(id) ?: return
            val task = scope.launch(start = CoroutineStart.LAZY) {
                one.withLock {
                    try {
                        cleanInterruptedFile(id)
                        val (user, password) = account ?: error("Add your Soulseek account in Settings")
                        if (client.state.value !is SoulseekClient.State.Online) {
                            progress(id, key, title, "Signing in to Soulseek…", null)
                            client.login(user, password)
                        }
                        work(id) { status, fraction -> progress(id, key, title, status, fraction) }
                        synchronized(lock) {
                            if (_jobs.value.any { it.id == id && it.running }) {
                                write(data, Job(id, key, title, "Saved to Music/Soulseek" + (lastKind?.let { " · $it" } ?: ""), 1f, done = true))
                            }
                        }
                        onSaved()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            if (_jobs.value.any { it.id == id && it.running }) {
                                write(data, Job(id, key, title, failure.message ?: "The download failed", failed = true))
                            }
                        }
                    }
                }
            }
            active[id] = task
            idle?.cancel()
            task.invokeOnCompletion {
                synchronized(lock) {
                    active.remove(id, task)
                    if (retriesAfterCancellation.remove(id) && _jobs.value.any { it.id == id && it.running }) {
                        stored(id)?.optString("request")?.let { startStored(id, it) }
                    }
                    if (active.isEmpty()) {
                        idle?.cancel()
                        idle = scope.launch { kotlinx.coroutines.delay(120_000); synchronized(lock) { if (active.isEmpty()) client.disconnect() } }
                    }
                }
            }
            task.start()
        }
    }

    private fun progress(id: Int, key: String, title: String, status: String, fraction: Float?) {
        synchronized(lock) {
            if (_jobs.value.any { it.id == id && it.running }) showStatus(Job(id, key, title, status, fraction))
        }
    }

    private fun cleanInterruptedFile(id: Int, discard: Boolean = false) {
        val raw = prefs.getString("file:$id", null) ?: return
        val uri = android.net.Uri.parse(raw)
        val resolver = context.contentResolver
        val pending = resolver.query(uri, arrayOf(MediaStore.Audio.Media.IS_PENDING), null, null)?.use {
            it.moveToFirst() && it.getInt(0) == 1
        } ?: false
        if (pending && !discard) return
        if (pending) resolver.delete(uri, null, null)
        val finished = prefs.getString("file-key:$id", null)
        val exists = !pending && resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null)?.use { it.moveToFirst() } == true
        val edit = prefs.edit().remove("file:$id").remove("file-key:$id").remove("peer-file:$id")
        if (exists && finished != null) {
            val completed = prefs.getStringSet("completed:$id", emptySet()).orEmpty()
            edit.putStringSet("completed:$id", completed + finished)
        }
        edit.apply()
    }

    private fun peerFileKey(file: SoulseekClient.Result): String =
        org.json.JSONArray(listOf(file.username, file.path, file.size)).toString()

    @Volatile private var lastKind: String? = null

    private suspend fun save(id: Int, file: SoulseekClient.Result, artist: String, album: String, update: (String, Float?) -> Unit, n: Int, of: Int) {
        currentCoroutineContext().ensureActive()
        val checkpoint = "completed:$id"
        val fileKey = "${file.name}/${file.size}"
        val completed = prefs.getStringSet(checkpoint, emptySet()).orEmpty()
        if (fileKey in completed) return
        lastKind = if (file.extension in LOSSLESS) file.extension.uppercase() else "${file.extension.uppercase()} ${file.bitrate ?: ""}".trim()
        val resolver = context.contentResolver
        val safe = { s: String -> s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "Unknown" } }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safe(file.name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Soulseek/${safe(artist)}/${safe(album)}/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        // A partial can be appended only for the same advertised file on the same peer.
        val peerKey = peerFileKey(file)
        val previous = prefs.getString("file:$id", null)?.let(android.net.Uri::parse)
        val previousSize = if (previous != null && prefs.getString("peer-file:$id", null) == peerKey) {
            resolver.openFileDescriptor(previous, "r")?.use { it.statSize } ?: -1L
        } else -1L
        val resume = previous != null && previousSize in 0..file.size
        if (!resume && previous != null) cleanInterruptedFile(id, discard = true)
        val offset = if (resume) previousSize else 0L
        val uri = if (resume) requireNotNull(previous) else
            resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Couldn't create the file")
        prefs.edit().putString("file:$id", uri.toString()).putString("file-key:$id", fileKey)
            .putString("peer-file:$id", peerKey).apply()
        val prefix = if (of > 1) "$n of $of · " else ""
        if (offset < file.size) {
            val waiting = if (offset > 0) "Resuming with" else "Waiting for"
            update("$prefix$waiting ${file.username}…", null)
            var shown = -1
            val output = resolver.openOutputStream(uri, if (offset == 0L) "wt" else "wa") ?: error("Couldn't open the download")
            output.use { out ->
                client.download(file, out, { got, size ->
                    val percent = (got * 100 / size.coerceAtLeast(1)).toInt()
                    if (percent != shown) { shown = percent; update("$prefix$percent% of ${megabytes(size)}", got.toFloat() / size) }
                }, offset = offset)
            }
        }
        currentCoroutineContext().ensureActive()
        val written = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        check(written == file.size) { "The downloaded file size doesn't match the transfer" }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1) { "Couldn't publish the download" }
        prefs.edit().putStringSet(checkpoint, completed + fileKey).remove("file:$id").remove("file-key:$id").remove("peer-file:$id").apply()
    }

    private val lastShown = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    internal fun channel(): String {
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun notify(job: Job) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (job.running && now - (lastShown[job.id] ?: -500L) < 500) return
        if (job.running) lastShown[job.id] = now else lastShown.remove(job.id)
        val n = Notification.Builder(context, channel()).setSmallIcon(
            if (job.done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download,
        ).setContentTitle(job.title).setContentText(job.status).setOnlyAlertOnce(true)
            .setOngoing(job.running).setAutoCancel(!job.running).setContentIntent(DownloadActions.open(context, job.id))
            .setGroup(CHANNEL)
            .apply {
                if (job.running) {
                    val percent = ((job.progress ?: 0f) * 100).toInt()
                    setProgress(100, percent, job.progress == null)
                    // A Live Update (Android 16+): the download's progress in the status bar chip and on the
                    // lock screen, as Google's own downloads show it. Plain progress stays the fallback.
                    runCatching {
                        setStyle(Notification.ProgressStyle().setStyledByProgress(true).setProgress(percent).setProgressIndeterminate(job.progress == null))
                        setRequestPromotedOngoing(true)
                        if (job.progress != null) setShortCriticalText("$percent%")
                    }
                    addAction(Notification.Action.Builder(null, "Cancel", DownloadActions.action(context, job.id, retry = false)).build())
                } else if (job.failed || job.cancelled) {
                    addAction(Notification.Action.Builder(null, "Retry", DownloadActions.action(context, job.id, retry = true)).build())
                }
            }.build()
        runCatching { manager.notify(NOTIFICATION_BASE + job.id, n) }
    }

    companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_BASE = 20_000_000
        private const val SEP = "\u0001"
        fun megabytes(bytes: Long): String = if (bytes >= 1_000_000_000) String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9) else "${(bytes + 500_000) / 1_000_000} MB"
        fun songKey(artist: String, title: String) = "song:" + TextFold.fold(artist) + "\u0000" + TextFold.fold(title)
        fun albumKey(artist: String, album: String) = "album:" + TextFold.fold(artist) + "\u0000" + TextFold.fold(album)
        val LOSSLESS = setOf("flac", "wav", "aiff", "alac", "ape", "wv")

        private fun words(s: String) = TextFold.fold(s).replace(Regex("""[(\[].*?[)\]]"""), " ").split(Regex("""\W+""")).filter { it.length > 1 }.toSet()

        /** A file's words, brackets included: "02 - wanton soup (chopped and screwed).flac" has "chopped". */
        private fun fileWords(s: String) = TextFold.fold(s.substringBeforeLast('.')).split(Regex("""\W+""")).filter { it.length > 1 }.toSet()

        /** Search phrases, exact to loose, without brackets, "feat." and symbols the server ignores. */
        fun queries(artist: String, title: String): List<String> {
            fun clean(s: String) = s.replace(Regex("""[(\[].*?[)\]]"""), " ").replace(Regex("""(?i)\s(feat\.?|ft\.?|featuring)\s.*$"""), " ")
                .replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace(Regex("""\s+"""), " ").trim()
            val a = clean(artist.substringBefore(",")); val t = clean(title)
            val short = t.split(' ').take(3).joinToString(" ")
            return listOf("$a $t", t, "$a $short").map { it.trim() }.filter { it.length >= 3 }.distinct()
        }

        /** The title's words in the file: all of a short title, three in four of a long one. */
        private fun named(file: String, want: Set<String>): Boolean {
            if (want.isEmpty()) return true
            val have = fileWords(file)
            val hits = want.count { it in have }
            return if (want.size <= 2) hits == want.size else hits * 4 >= want.size * 3
        }

        /** A lossy copy good enough to keep: MP3 (or AAC) at 256 kbps or more, by its tag or by size over length. */
        fun goodLossy(r: SoulseekClient.Result): Boolean {
            if (r.extension !in setOf("mp3", "m4a", "aac", "ogg", "opus")) return false
            val kbps = r.bitrate ?: r.seconds?.takeIf { it > 0 }?.let { (r.size * 8 / 1000 / it).toInt() } ?: return false
            return kbps >= 256
        }

        /**
         * The best file for a song: lossless first; when nobody has it, the best MP3 at 256 kbps or
         * more (most music on Soulseek outside the big catalogues is 320 kbps). Named like the
         * song, the right length, then the quickest peer to start.
         */
        fun chooseSong(results: List<SoulseekClient.Result>, title: String, seconds: Long): SoulseekClient.Result? {
            val want = words(title)
            val named = results.filter { r -> named(r.name, want) }
                .filter { r -> seconds <= 0 || r.seconds == null || kotlin.math.abs(r.seconds - seconds) <= 5 }
            fun best(list: List<SoulseekClient.Result>) = list.maxByOrNull { r -> (if (r.extension == "flac") 1_000_000L else 0L) + (if (r.slotFree) 500_000L else 0L) - r.queue * 1_000 + r.speed / 1_000 + (r.bitrate ?: 0) * 100L }
            return best(named.filter { it.extension in LOSSLESS && it.size in 2_000_000..400_000_000 }) ?: best(named.filter(::goodLossy))
        }

        /** The best album: one peer's folder with the most lossless songs, the quickest peer winning ties. */
        fun chooseAlbum(results: List<SoulseekClient.Result>): List<SoulseekClient.Result>? {
            fun best(keep: (SoulseekClient.Result) -> Boolean) = results.filter(keep).groupBy { it.username to it.folder }.values
                .filter { it.size >= 3 }
                .maxByOrNull { files -> files.size * 1_000_000L + (if (files.first().slotFree) 500_000L else 0L) + files.first().speed / 1_000 }
                ?.sortedBy { it.name }
            // Lossless when anyone has it; else the fullest folder at 256 kbps or more.
            return best { it.extension in LOSSLESS } ?: best(::goodLossy)
        }
    }
}
