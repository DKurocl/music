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
import java.security.MessageDigest

/**
 * Lyrics fetched from the internet when the owner asks, kept in the app's own storage per song
 * identity (so every copy of a song shares them, and a rescan keeps them). Sidecar files beside
 * the music still win; these come before lyrics embedded in the file.
 */
class DownloadedLyrics(context: Context, private val online: OnlineLyrics = OnlineLyrics()) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun read(track: Track): Pair<String, String>? = FoundLyrics.Format.entries.firstNotNullOfOrNull { f ->
        File(dir, name(track, f)).takeIf { it.isFile }?.let { it.readText() to f.name }
    }

    /** Looks the song up (Kugou, then LRCLIB) and saves what it finds; returns the source, or null. */
    suspend fun fetch(track: Track): String? = withContext(Dispatchers.IO) {
        val found = online.find(track.artists.firstOrNull() ?: track.artist, track.title, track.album, track.durationMs) ?: return@withContext null
        FoundLyrics.Format.entries.forEach { File(dir, name(track, it)).delete() }
        val file = File(dir, name(track, found.format))
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(found.text); tmp.renameTo(file)
        _revision.value++
        found.source
    }

    /** Lyrics the owner retimed by hand; they win over every other source for this song. */
    fun edited(track: Track): String? = File(dir, editedName(track)).takeIf { it.isFile }?.readText()

    fun saveEdited(track: Track, lrc: String) {
        val file = File(dir, editedName(track)); val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(lrc); tmp.renameTo(file); _revision.value++
    }

    fun deleteEdited(track: Track) { File(dir, editedName(track)).delete(); _revision.value++ }

    private fun editedName(track: Track) = name(track, FoundLyrics.Format.LRC).removeSuffix(".lrc") + ".edited.lrc"

    fun delete(track: Track) { FoundLyrics.Format.entries.forEach { File(dir, name(track, it)).delete() }; _revision.value++ }

    private fun name(track: Track, f: FoundLyrics.Format): String {
        return ua.olex.music.lyrics.LyricsStore.stem(track.key.value) + "." + f.extension
    }
}
