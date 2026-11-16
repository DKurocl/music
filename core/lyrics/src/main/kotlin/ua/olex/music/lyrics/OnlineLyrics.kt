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
    /**
     * The song's lyrics, trying names from exact to clean: services match titles literally, so
     * "Juicy (2005 Remaster)" or "Song - Remastered 2011" finds nothing until the extras go, and
     * "A, B & C" until only the first artist is asked. The last try is LRCLIB's free search.
     */
    fun find(artist: String, title: String, album: String?, durationMs: Long): FoundLyrics? {
        for ((a, t) in variants(artist, title)) {
            runCatching { kugou(a, t, durationMs) }.getOrNull()?.let { return it }
            runCatching { lrclib(a, t, album.takeIf { a == artist && t == title }, durationMs) }.getOrNull()?.let { return it }
        }
        return runCatching { lrclibSearch("${variants(artist, title).last().first} ${variants(artist, title).last().second}", durationMs) }.getOrNull()
    }

    fun variants(artist: String, title: String): List<Pair<String, String>> {
        val cleanTitle = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "")
            .replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s.*$"""), "").replace(Regex("""\s+"""), " ").trim().ifEmpty { title }
        val firstArtist = artist.split(Regex(""",|&| x | feat\.? | ft\.? """)).first().trim().ifEmpty { artist }
        return listOf(artist to title, artist to cleanTitle, firstArtist to cleanTitle).distinct()
    }

    internal fun lrclibSearch(query: String, durationMs: Long): FoundLyrics? {
        val body = http("https://lrclib.net/api/search?q=${enc(query)}") ?: return null
        val hits = (Json.parse(body) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val hit = hits.filter { close((it["duration"] as? Double)?.times(1000)?.toLong(), durationMs) }
            .firstOrNull { !(it["syncedLyrics"] as? String).isNullOrBlank() } ?: hits.firstOrNull { close((it["duration"] as? Double)?.times(1000)?.toLong(), durationMs) && !(it["plainLyrics"] as? String).isNullOrBlank() }
            ?: return null
        val text = (hit["syncedLyrics"] as? String)?.takeIf { it.isNotBlank() } ?: hit["plainLyrics"] as? String ?: return null
        val synced = !(hit["syncedLyrics"] as? String).isNullOrBlank()
        return FoundLyrics(text, "LRCLIB", if (synced) FoundLyrics.Format.LRC else FoundLyrics.Format.PLAIN, Lyrics.parse(text))
    }

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
