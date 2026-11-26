package ua.olex.music.lyrics

import java.net.URLEncoder

/** What a lyric service found: the text in its own format, where it came from, and how it is timed. */
data class FoundLyrics(val text: String, val source: String, val format: Format, val lyrics: Lyrics) {
    enum class Format(val extension: String) { LRC("lrc"), KRC("krc"), PLAIN("txt") }
}

/**
 * Looks lyrics up on the internet only when the owner asks (no key, no account):
 *  - Kugou: word-timed KRC, the source LastWave uses;
 *  - NetEase: word-timed YRC (read as KRC) for songs Kugou lacks, line-timed LRC otherwise;
 *  - LRCLIB: line-timed LRC and plain text, community maintained.
 * Every source is asked for each name variant until word-timed lyrics are found; otherwise the best
 * found wins (word-timed, then line-timed, then plain). A candidate must have the song's title.
 * A candidate is accepted only when its length is within [LENGTH_TOLERANCE_MS] of the song, so a
 * live or extended version is not taken for the studio one.
 */
class OnlineLyrics(private val http: suspend (String) -> String?) {
    /**
     * The song's lyrics, trying names from exact to clean: services match titles literally, so
     * "Juicy (2005 Remaster)" or "Song - Remastered 2011" finds nothing until the extras go, and
     * "A, B & C" until only the first artist is asked. The last try is LRCLIB's free search.
     */
    suspend fun find(artist: String, title: String, album: String?, durationMs: Long): FoundLyrics? {
        var best: FoundLyrics? = null
        var incomplete = false
        suspend fun attempt(lookup: suspend () -> FoundLyrics?): FoundLyrics? = try {
            lookup()
        } catch (_: java.io.IOException) {
            incomplete = true
            null
        } catch (_: IllegalArgumentException) {
            incomplete = true
            null
        }
        fun rank(f: FoundLyrics) = when (f.lyrics.sync) { Sync.WORD -> 3; Sync.LINE -> 2; Sync.NONE -> 1 }
        // True once word-timed lyrics are in hand: nothing better exists, so the search stops.
        fun keep(found: FoundLyrics?): Boolean {
            if (found == null || found.lyrics.lines.isEmpty()) return false
            if (best == null || rank(found) > rank(best!!)) best = found
            return found.lyrics.sync == Sync.WORD
        }
        val variants = variants(artist, title)
        for ((a, t) in variants) {
            if (keep(attempt { kugou(a, t, durationMs) })) return best
            if (keep(attempt { netease(a, t, durationMs) })) return best
            keep(attempt { lrclib(a, t, album.takeIf { a == artist && t == title }, durationMs) })
        }
        best?.let { return it }
        val (lastArtist, lastTitle) = variants.last()
        val result = attempt { lrclibSearch("$lastArtist $lastTitle", durationMs) }
        if (result == null && incomplete) throw java.io.IOException("Some lyric services were unavailable")
        return result
    }

    /** NetEase Cloud Music: word-timed YRC when it has it (converted to KRC), else its LRC. */
    internal suspend fun netease(artist: String, title: String, durationMs: Long): FoundLyrics? {
        val body = http("https://music.163.com/api/search/get/web?s=${enc("$artist $title")}&type=1&limit=8") ?: return null
        val songs = (((Json.parse(body) as? Map<*, *>)?.get("result") as? Map<*, *>)?.get("songs") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val hit = songs.firstOrNull { s -> sameTitle(s["name"]?.toString(), title) && close((s["duration"] as? Double)?.toLong(), durationMs) } ?: return null
        val id = (hit["id"] as? Double)?.toLong() ?: return null
        val answer = http("https://music.163.com/api/song/lyric?id=$id&lv=1&yv=1&tv=-1") ?: return null
        val root = Json.parse(answer) as? Map<*, *> ?: return null
        ((root["yrc"] as? Map<*, *>)?.get("lyric") as? String)?.takeIf { it.isNotBlank() }?.let { yrc ->
            val krc = yrcToKrc(yrc)
            val lyrics = KrcParser.parse(krc)
            if (lyrics.lines.isNotEmpty()) return FoundLyrics(krc, "NetEase", FoundLyrics.Format.KRC, lyrics)
        }
        val lrc = ((root["lrc"] as? Map<*, *>)?.get("lyric") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val lyrics = LrcParser.parse(lrc)
        return if (lyrics.lines.isEmpty()) null else FoundLyrics(lrc, "NetEase", FoundLyrics.Format.LRC, lyrics)
    }

    fun variants(artist: String, title: String): List<Pair<String, String>> {
        val cleanTitle = title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "")
            .replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s.*$"""), "").replace(Regex("""\s+"""), " ").trim().ifEmpty { title }
        val firstArtist = artist.split(Regex(""",|&| x | feat\.? | ft\.? """)).first().trim().ifEmpty { artist }
        return listOf(artist to title, artist to cleanTitle, firstArtist to cleanTitle).distinct()
    }

    internal suspend fun lrclibSearch(query: String, durationMs: Long): FoundLyrics? {
        val body = http("https://lrclib.net/api/search?q=${enc(query)}") ?: return null
        val hits = (Json.parse(body) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val hit = hits.filter { close((it["duration"] as? Double)?.times(1000)?.toLong(), durationMs) }
            .firstOrNull { !(it["syncedLyrics"] as? String).isNullOrBlank() } ?: hits.firstOrNull { close((it["duration"] as? Double)?.times(1000)?.toLong(), durationMs) && !(it["plainLyrics"] as? String).isNullOrBlank() }
            ?: return null
        val text = (hit["syncedLyrics"] as? String)?.takeIf { it.isNotBlank() } ?: hit["plainLyrics"] as? String ?: return null
        val synced = !(hit["syncedLyrics"] as? String).isNullOrBlank()
        return FoundLyrics(text, "LRCLIB", if (synced) FoundLyrics.Format.LRC else FoundLyrics.Format.PLAIN, Lyrics.parse(text))
    }

    internal suspend fun kugou(artist: String, title: String, durationMs: Long): FoundLyrics? {
        val search = http("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc("$artist - $title")}&duration=$durationMs&hash=") ?: return null
        val candidates = ((Json.parse(search) as? Map<*, *>)?.get("candidates") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        // The song itself, not just one of the same length: its title has to match.
        val best = candidates.firstOrNull { c -> close((c["duration"] as? Double)?.toLong(), durationMs) && sameTitle(c["song"]?.toString(), title) } ?: return null
        val id = best["id"]?.toString()?.removeSuffix(".0") ?: return null
        val key = best["accesskey"]?.toString() ?: return null
        val download = http("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=krc&charset=utf8") ?: return null
        val content = (Json.parse(download) as? Map<*, *>)?.get("content")?.toString() ?: return null
        val text = KrcParser.decrypt(java.util.Base64.getDecoder().decode(content)) ?: return null
        val lyrics = KrcParser.parse(text)
        return if (lyrics.lines.isEmpty()) null else FoundLyrics(text, "Kugou", FoundLyrics.Format.KRC, lyrics)
    }

    internal suspend fun lrclib(artist: String, title: String, album: String?, durationMs: Long): FoundLyrics? {
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

    /** The same title once case, accents, punctuation and "(Remastered)"-style extras are set aside. */
    private fun sameTitle(candidate: String?, title: String): Boolean {
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "").replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), "")
        val a = norm(candidate ?: return true); val b = norm(title)
        return a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a)
    }

    private fun close(candidateMs: Long?, songMs: Long) = candidateMs == null || songMs <= 0 || kotlin.math.abs(candidateMs - songMs) <= LENGTH_TOLERANCE_MS
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val LENGTH_TOLERANCE_MS = 4_000L

        /**
         * NetEase's YRC as KRC: `[start,length](wordStart,wordLength,0)word` with absolute word times
         * becomes `[start,length]<offset,wordLength,0>word` with offsets from the line's start.
         * Metadata lines (JSON objects) are left out.
         */
        fun yrcToKrc(yrc: String): String = yrc.lineSequence().mapNotNull { raw ->
            val m = Regex("""^\[(\d+),(\d+)](.*)$""").matchEntire(raw.trim()) ?: return@mapNotNull null
            val start = m.groupValues[1].toLong()
            val words = Regex("""\((\d+),(\d+),\d+\)([^(]*)""").findAll(m.groupValues[3]).joinToString("") { w ->
                "<${(w.groupValues[1].toLong() - start).coerceAtLeast(0)},${w.groupValues[2]},0>${w.groupValues[3]}"
            }
            if (words.isEmpty()) null else "[$start,${m.groupValues[2]}]$words"
        }.joinToString("\n")

    }
}
