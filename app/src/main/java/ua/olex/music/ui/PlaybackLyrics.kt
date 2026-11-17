package ua.olex.music.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import ua.olex.music.AppGraph
import ua.olex.music.model.Track

/** One source order for the app and the session: edited, sidecar, downloaded/embedded, online. */
internal suspend fun AppGraph.resolveLyrics(track: Track): LocalLyricsState = withContext(Dispatchers.IO) {
    downloadedLyrics.edited(track)?.let(ua.olex.music.lyrics.Lyrics::parse)?.takeIf { it.lines.isNotEmpty() }?.let {
        return@withContext LocalLyricsState(it, "Your timing", mediaId = track.id.value, edited = true)
    }
    // A YouTube Music song has no file: its lyrics come from the internet, found by themselves
    // and kept, so they show at once the next time.
    if (YouTubeMusic.isOnline(track)) {
        val kept = downloadedLyrics.read(track)?.let { (text, _) -> ua.olex.music.lyrics.Lyrics.parse(text) }?.takeIf { it.lines.isNotEmpty() }
        val found = kept ?: runCatching { downloadedLyrics.fetch(track)?.let { ua.olex.music.lyrics.Lyrics.parse(it.text) } }.getOrNull()
        return@withContext if (found != null && found.lines.isNotEmpty()) LocalLyricsState(found, "Found online", mediaId = track.id.value)
            else LocalLyricsState(ua.olex.music.lyrics.Lyrics.EMPTY, "No lyrics found online", mediaId = track.id.value)
    }
    val embedded = track.metadata?.lyrics?.let(ua.olex.music.lyrics.Lyrics::parse) ?: ua.olex.music.lyrics.Lyrics.EMPTY
    val downloaded = downloadedLyrics.read(track)?.let { (text, _) -> ua.olex.music.lyrics.Lyrics.parse(text) }?.takeIf { it.lines.isNotEmpty() }
    val fallback = if (downloaded != null && (embedded.lines.isEmpty() || downloaded.sync.ordinal >= embedded.sync.ordinal))
        LocalLyricsState(downloaded, "Downloaded lyrics", mediaId = track.id.value)
        else LocalLyricsState(embedded, if (embedded.lines.isEmpty()) "No local lyrics" else "Embedded lyrics", mediaId = track.id.value)
    try {
        val sidecar = musicFiles.lyrics(track.path)
        if (sidecar.status == ua.olex.music.library.SidecarStatus.FOUND) {
            val parsed = sidecar.text?.let(ua.olex.music.lyrics.Lyrics::parse) ?: ua.olex.music.lyrics.Lyrics.EMPTY
            if (parsed.lines.isNotEmpty()) fallback.copy(lyrics = parsed, source = "Sidecar: ${sidecar.name}")
            else fallback.copy(note = "The sidecar is empty or invalid. Showing embedded lyrics when available.")
        } else fallback.copy(note = if (track.path.isNullOrBlank()) "This song has no library file path for sidecar lookup." else when (sidecar.status) {
            ua.olex.music.library.SidecarStatus.NO_FOLDER -> "Choose a music folder to read lyrics beside your files."
            ua.olex.music.library.SidecarStatus.OUTSIDE_FOLDER -> "Choose a folder containing this song to read its sidecar lyrics."
            ua.olex.music.library.SidecarStatus.AMBIGUOUS -> "Several sidecar files match. No file was chosen."
            else -> null
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { fallback.copy(note = "Folder access was revoked. Choose the music folder again.") }
    catch (_: Exception) { fallback.copy(note = "Couldn't read the sidecar. Use UTF-8 lyrics up to 1 MB, then Reload.") }
}
