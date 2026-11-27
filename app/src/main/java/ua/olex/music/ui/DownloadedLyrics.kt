package ua.olex.music.ui

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.olex.music.lyrics.FoundLyrics
import ua.olex.music.lyrics.OnlineLyrics
import ua.olex.music.model.Track
import java.io.File
import java.io.IOException

/**
 * Lyrics fetched from the internet (by themselves when a song has none, or when the owner asks), kept in the app's own storage per song
 * identity (so every copy of a song shares them, and a rescan keeps them). Sidecar files beside
 * the music still win; these come before lyrics embedded in the file.
 */
class DownloadedLyrics(
    context: Context,
    private val online: OnlineLyrics = OnlineLyrics { MetadataHttp.get(it, 1 shl 20)?.toString(Charsets.UTF_8) },
    /** Plain lyrics from YouTube Music, asked only when the timed services found nothing. */
    private val lastResort: suspend (Track) -> String? = { null },
) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val misses = LruCache<String, Unit>(512)
    // One lookup per song at a time; songs never wait for each other (a slow miss for the previous
    // song must not hold up the one now playing). Removal during a lookup is tracked per song.
    private val lookups = HashMap<String, Mutex>()
    private val removedDuring = HashMap<String, Boolean>()
    @Synchronized private fun lockFor(key: String) = lookups.getOrPut(key) { Mutex() }
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    @Synchronized fun read(track: Track, aliases: List<String> = emptyList()): Pair<String, String>? {
        val keys = keys(track, aliases)
        val candidates = keys.flatMap { key -> FoundLyrics.Format.entries.map { f -> File(dir, name(key, f)) to f } }
        val found = candidates.filter { it.first.isFile }.maxByOrNull { it.first.lastModified() } ?: return null
        return found.first.readText() to found.second.name
    }

    /** What a lookup found: where from, and the cleaned lyrics as text to keep in the music file. */
    data class Fetched(val source: String, val text: String)

    /**
     * Looks the song up (Kugou, NetEase, LRCLIB, then YouTube Music), drops translations and credit lines, and keeps the
     * result as LRC (word-timed where the source is); returns it, or null when nothing matched.
     */
    suspend fun fetch(track: Track, force: Boolean = false): Fetched? = withContext(Dispatchers.IO) {
        // A song the services don't know is asked once per session unless the owner asks again:
        // the player and the lock screen both resolve lyrics, and a miss shouldn't cost two lookups each play.
        val key = writeKey(track)
        lockFor(key).withLock {
            if (!force) {
                if (misses.get(key) != null) return@withLock null
                read(track)?.first?.let { cached -> return@withLock cached.takeIf(String::isNotBlank)?.let { Fetched("Saved", it) } }
            }
            synchronized(this@DownloadedLyrics) { removedDuring[key] = false }
            try {
                var incomplete = false
                suspend fun <T> attempt(block: suspend () -> T?): T? = try { block() } catch (_: IOException) {
                    incomplete = true
                    null
                }
                val found = attempt { online.find(track.artists.firstOrNull() ?: track.artist, track.title, track.album, track.durationMs) }
                    ?: attempt { lastResort(track) }?.takeIf(String::isNotBlank)?.let { text ->
                        FoundLyrics(text, "YouTube Music", FoundLyrics.Format.PLAIN, ua.olex.music.lyrics.Lyrics.parse(text))
                    }
                currentCoroutineContext().ensureActive()
                if (found == null) {
                    if (!incomplete) misses.put(key, Unit)
                    return@withLock null
                }
                val clean = ua.olex.music.lyrics.LyricsCleanup.clean(found.lyrics, track.title, track.artist)
                if (clean.lines.isEmpty()) return@withLock null
                val plain = clean.sync == ua.olex.music.lyrics.Sync.NONE
                val text = if (plain) clean.lines.joinToString("\n") { it.text } else ua.olex.music.lyrics.LrcWriter.write(clean)
                val format = if (plain) FoundLyrics.Format.PLAIN else FoundLyrics.Format.LRC
                currentCoroutineContext().ensureActive()
                if (saveFetched(track, format, text)) Fetched(found.source, text) else null
            } finally {
                synchronized(this@DownloadedLyrics) { removedDuring.remove(key) }
            }
        }
    }

    // UI and lock-screen loads share this store. AtomicFile itself does not serialize writers.
    @Synchronized private fun saveFetched(track: Track, format: FoundLyrics.Format, text: String): Boolean {
        val key = writeKey(track)
        if (removedDuring[key] == true) return false
        publish(key, format, text)
        misses.remove(key)
        _revision.value++
        return true
    }

    private fun publish(key: String, format: FoundLyrics.Format, text: String) {
        val target = name(key, format)
        write(File(dir, target), text)
        FoundLyrics.Format.entries.map { name(key, it) }.filterNot { it == target }.distinct().forEach { File(dir, it).delete() }
    }

    /** Latest explicit edit wins across recording aliases; an empty reset marker blocks old edits. */
    @Synchronized fun edited(track: Track, aliases: List<String> = emptyList()): String? = keys(track, aliases)
        .map { File(dir, editedName(it)) }.filter { it.isFile }.maxByOrNull { it.lastModified() }?.readText()?.takeIf { it.isNotEmpty() }

    @Synchronized fun saveEdited(track: Track, lrc: String) {
        write(File(dir, editedName(writeKey(track))), lrc)
        _revision.value++
    }

    fun deleteEdited(track: Track) = saveEdited(track, "")

    private fun editedName(key: String) = name(key, FoundLyrics.Format.LRC).removeSuffix(".lrc") + ".edited.lrc"

    @Synchronized fun delete(track: Track) {
        val key = writeKey(track)
        publish(key, FoundLyrics.Format.PLAIN, "")
        if (key in removedDuring) removedDuring[key] = true
        _revision.value++
    }

    private fun writeKey(track: Track) = track.recordingId ?: track.key.value
    private fun keys(track: Track, aliases: List<String>) =
        (listOf(writeKey(track)) + aliases + track.sources.map { it.songKey } + track.key.value).distinct()

    private fun name(key: String, format: FoundLyrics.Format) = ua.olex.music.lyrics.LyricsStore.stem(key) + "." + format.extension

    private fun write(file: File, text: String) {
        val target = android.util.AtomicFile(file)
        val stream = target.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (failure: Exception) { target.failWrite(stream); throw failure }
    }
}
