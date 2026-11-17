package ua.olex.music.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ua.olex.music.lyrics.FoundLyrics
import ua.olex.music.lyrics.OnlineLyrics
import ua.olex.music.model.Track
import java.io.File

/**
 * Lyrics fetched from the internet when the owner asks, kept in the app's own storage per song
 * identity (so every copy of a song shares them, and a rescan keeps them). Sidecar files beside
 * the music still win; these come before lyrics embedded in the file.
 */
class DownloadedLyrics(context: Context, private val online: OnlineLyrics = OnlineLyrics()) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
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
     * Looks the song up (Kugou, then LRCLIB), drops translations and credit lines, and keeps the
     * result as LRC (word-timed where the source is); returns it, or null when nothing matched.
     */
    suspend fun fetch(track: Track): Fetched? = withContext(Dispatchers.IO) {
        val found = online.find(track.artists.firstOrNull() ?: track.artist, track.title, track.album, track.durationMs) ?: return@withContext null
        val clean = ua.olex.music.lyrics.LyricsCleanup.clean(found.lyrics, track.title, track.artist)
        val plain = clean.sync == ua.olex.music.lyrics.Sync.NONE
        val text = if (plain) clean.lines.joinToString("\n") { it.text } else ua.olex.music.lyrics.LrcWriter.write(clean)
        val format = if (plain) FoundLyrics.Format.PLAIN else FoundLyrics.Format.LRC
        saveFetched(track, format, text)
        Fetched(found.source, text)
    }

    // UI and lock-screen loads share this store. AtomicFile itself does not serialize writers.
    @Synchronized private fun saveFetched(track: Track, format: FoundLyrics.Format, text: String) {
        FoundLyrics.Format.entries.forEach { File(dir, name(writeKey(track), it)).delete() }
        write(File(dir, name(writeKey(track), format)), text)
        _revision.value++
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
        FoundLyrics.Format.entries.forEach { File(dir, name(writeKey(track), it)).delete() }
        write(File(dir, name(writeKey(track), FoundLyrics.Format.PLAIN)), "")
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
