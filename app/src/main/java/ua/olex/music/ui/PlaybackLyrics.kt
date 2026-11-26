package ua.olex.music.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ua.olex.music.AppGraph
import ua.olex.music.lyrics.Lyrics
import ua.olex.music.model.RecordingSource
import ua.olex.music.model.OptionalWork
import ua.olex.music.model.RecordingSourceKind
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track

/** One source order for the app and the session: edited, sidecar, downloaded/embedded, online. */
internal suspend fun AppGraph.resolveLyrics(request: Track, lockScreen: Boolean = false): LocalLyricsState = withContext(Dispatchers.IO) {
    val recording = userLibrary.reference(request).first()
    val track = recording?.bind(request) ?: request
    suspend fun fetchAvailable(): Lyrics? = if (lockScreen)
        resources.run(OptionalWork.LOCK_SCREEN_LYRICS) { kotlinx.coroutines.withTimeoutOrNull(5_000) { fetchedLyrics(track) } }
    else fetchedLyrics(track)
    val aliases = recording?.let { userLibrary.aliases(it.id) }.orEmpty()
    downloadedLyrics.edited(track, aliases)?.let(ua.olex.music.lyrics.Lyrics::parse)?.takeIf { it.lines.isNotEmpty() }?.let {
        return@withContext LocalLyricsState(it, "Your timing", mediaId = track.id.value, edited = true)
    }
    val cached = downloadedLyrics.read(track, aliases)
    val downloaded = cached?.let { (text, _) -> Lyrics.parse(text) }?.takeIf { it.lines.isNotEmpty() }
    if (YouTubeMusic.isOnline(track)) {
        if (downloaded != null) return@withContext LocalLyricsState(downloaded, "Found online", mediaId = track.id.value)
        val linked = linkedLyricFile(recording, track)
        if (linked != null) {
            val embedded = linked.metadata?.lyrics?.let(Lyrics::parse) ?: Lyrics.EMPTY
            val local = localLyrics(linked, LocalLyricsState(embedded, "Embedded lyrics", mediaId = track.id.value))
            if (local.lyrics.lines.isNotEmpty()) return@withContext local
        }
        val found = if (cached == null) fetchAvailable() else null
        return@withContext if (found != null) LocalLyricsState(found, "Found online", mediaId = track.id.value)
            else LocalLyricsState(Lyrics.EMPTY, "No lyrics found online", mediaId = track.id.value)
    }
    val embedded = track.metadata?.lyrics?.let(Lyrics::parse) ?: Lyrics.EMPTY
    val fallback = if (downloaded != null && (embedded.lines.isEmpty() || downloaded.sync.ordinal >= embedded.sync.ordinal))
        LocalLyricsState(downloaded, "Downloaded lyrics", mediaId = track.id.value)
        else LocalLyricsState(embedded, if (embedded.lines.isEmpty()) "No local lyrics" else "Embedded lyrics", mediaId = track.id.value)
    val local = localLyrics(track, fallback)
    // A removed result leaves a cached marker, so it is not fetched again.
    if (local.lyrics.lines.isNotEmpty() || cached != null) return@withContext local
    val found = fetchAvailable()
    if (found != null) LocalLyricsState(found, "Found online", mediaId = track.id.value) else local
}

private suspend fun AppGraph.fetchedLyrics(track: Track): Lyrics? = try {
    downloadedLyrics.fetch(userLibrary.keepReference(track).bind(track))?.let { Lyrics.parse(it.text) }
        ?.takeIf { it.lines.isNotEmpty() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private fun AppGraph.linkedLyricFile(recording: SavedSong?, request: Track): Track? {
    val files = allFiles.value ?: return null
    return linkedLyricFile(recording, request, files)
}

/** Timing from a file is eligible only for an exact linked video with a compatible timeline. */
internal fun linkedLyricFile(recording: SavedSong?, request: Track, index: ua.olex.music.model.LibraryIndex): Track? {
    val saved = recording ?: return null
    val provider = saved.references.firstOrNull {
        it.kind == RecordingSourceKind.YOUTUBE && it.uri == request.uri && it.durationMs > 0
    } ?: return null
    if (request.durationMs > 0 && kotlin.math.abs(request.durationMs - provider.durationMs) > 500) return null
    val file = saved.resolve(index) ?: return null
    val source = RecordingSource.from(file) ?: return null
    return file.takeIf { source.kind == RecordingSourceKind.LOCAL && file.clipStartMs == null &&
        file.durationMs > 0 && kotlin.math.abs(file.durationMs - provider.durationMs) <= 500 &&
        saved.references.any { it.key == source.key } }
}

private suspend fun AppGraph.localLyrics(track: Track, fallback: LocalLyricsState): LocalLyricsState {
    return try {
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
