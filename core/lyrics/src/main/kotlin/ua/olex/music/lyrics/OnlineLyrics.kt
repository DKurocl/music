package ua.olex.music.lyrics

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a lyric service found: the text in its own format, where it came from, and how it is timed. */
data class FoundLyrics(val text: String, val source: String, val format: Format, val lyrics: Lyrics) {
    enum class Format(val extension: String) { LRC("lrc"), KRC("krc"), PLAIN("txt") }
}

/**
 * Looks lyrics up on the internet only when the owner asks (no key, no account):
 *  - Kugou first: word-timed KRC, the source LastWave uses;
 *  - LRCLIB: line-timed LRC and plain text, community maintained.
 * A candidate is accepted only when its length is within [LENGTH_TOLERANCE_MS] of the song, so a
 * live or extended version is not taken for the studio one.
 */
class OnlineLyrics(private val http: (String) -> String? = ::get) {
    fun find(artist: String, title: String, album: String?, durationMs: Long): FoundLyrics? =
        runCatching { kugou(artist, title, durationMs) }.getOrNull() ?: runCatching { lrclib(artist, title, album, durationMs) }.getOrNull()

    internal fun kugou(artist: String, title: String, durationMs: Long): FoundLyrics? {
        val search = http("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc("$artist - $title")}&duration=$durationMs&hash=") ?: return null
        val candidates = ((Json.parse(search) as? Map<*, *>)?.get("candidates") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val best = candidates.firstOrNull { c -> close((c["duration"] as? Double)?.toLong(), durationMs) } ?: return null
        val id = best["id"]?.toString()?.removeSuffix(".0") ?: return null
        val key = best["accesskey"]?.toString() ?: return null
        val download = http("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=krc&charset=utf8") ?: return null
        val content = (Json.parse(download) as? Map<*, *>)?.get("content")?.toString() ?: return null
        val text = KrcParser.decrypt(java.util.Base64.getDecoder().decode(content)) ?: return null
        val lyrics = KrcParser.parse(text)
        return if (lyrics.lines.isEmpty()) null else FoundLyrics(text, "Kugou", FoundLyrics.Format.KRC, lyrics)
    }

    internal fun lrclib(artist: String, title: String, album: String?, durationMs: Long): FoundLyrics? {
        val seconds = durationMs / 1000
        val exact = http("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}" +
            (album?.let { "&album_name=${enc(it)}" } ?: "") + "&duration=$seconds")
        val row = exact?.let { Json.parse(it) as? Map<*, *> }?.takeIf { it["id"] != null }
            ?: http("https://lrclib.net/api/search?artist_name=${enc(artist)}&track_name=${enc(title)}")?.let { body ->
                (Json.parse(body) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                    .firstOrNull { close((it["duration"] as? Double)?.times(1000)?.toLong(), durationMs) }
            } ?: return null
        (row["syncedLyrics"] as? String)?.takeIf { it.isNotBlank() }?.let { return FoundLyrics(it, "LRCLIB", FoundLyrics.Format.LRC, LrcParser.parse(it)) }
        (row["plainLyrics"] as? String)?.takeIf { it.isNotBlank() }?.let { return FoundLyrics(it, "LRCLIB", FoundLyrics.Format.PLAIN, LrcParser.parse(it)) }
        return null
    }

    private fun close(candidateMs: Long?, songMs: Long) = candidateMs == null || songMs <= 0 || kotlin.math.abs(candidateMs - songMs) <= LENGTH_TOLERANCE_MS
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val LENGTH_TOLERANCE_MS = 4_000L

        /** A plain GET with short timeouts and an honest client name; null on any failure. */
        fun get(url: String): String? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "Music for Android (personal player)")
            try { if (c.responseCode in 200..299) c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } else null } finally { c.disconnect() }
        }.getOrNull()
    }
}
