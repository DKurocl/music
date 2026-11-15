package ua.olex.music.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.olex.music.lyrics.Json
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track
import java.net.URLEncoder

/**
 * Tags and covers from Apple's public catalogue (the iTunes Search API: no account, no key; only
 * the words searched for are sent). A song is looked up by its artist and title, an album by its
 * album artist and name. Candidates are ranked by how well they fit what the file already says:
 * the length first (a different length is a different recording), then album and artist.
 */
internal class OnlineTags(private val http: (String) -> ByteArray? = { ArtistImages.get(it) }) {

    /** One possible match, with the fields it would write and where its cover is. */
    data class Candidate(
        val title: String?, val artist: String, val album: String?, val albumArtist: String?,
        val year: Int?, val track: Int?, val disc: Int?, val genre: String?, val durationMs: Long?,
        val coverUrl: String?, val score: Int,
    ) {
        /** The fields as the tag editor names them. */
        fun fields(single: Boolean): Map<String, String> = buildMap {
            if (single) title?.let { put("TITLE", it) }
            if (single) put("ARTIST", artist)
            album?.let { put("ALBUM", it) }
            albumArtist?.let { put("ALBUMARTIST", it) }
            year?.let { put("DATE", it.toString()) }
            if (single) track?.let { put("TRACKNUMBER", it.toString()) }
            disc?.let { put("DISCNUMBER", it.toString()) }
            genre?.let { put("GENRE", it) }
        }
    }

    suspend fun search(tracks: List<Track>): List<Candidate> = withContext(Dispatchers.IO) {
        val first = tracks.firstOrNull() ?: return@withContext emptyList()
        val single = tracks.size == 1
        val term = if (single) "${first.artist} ${first.title}" else "${first.albumArtist ?: first.artist} ${first.album.orEmpty()}"
        val entity = if (single) "song" else "album"
        val body = http("https://itunes.apple.com/search?media=music&limit=12&entity=$entity&term=" + URLEncoder.encode(term.trim(), "UTF-8"))
            ?: return@withContext emptyList()
        val rows = ((Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *>)?.get("results") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        rows.mapNotNull { row ->
            fun text(key: String) = row[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: String) = (row[key] as? Number)?.toLong()
            val artist = text("artistName") ?: return@mapNotNull null
            val album = text("collectionName")?.removeSuffix(" - Single")?.removeSuffix(" - EP")
            Candidate(
                title = if (single) text("trackName") ?: return@mapNotNull null else null,
                artist = artist, album = album,
                // Apple names the album's artist as the collection's; "Various Artists" for compilations.
                albumArtist = text("collectionArtistName") ?: artist,
                year = text("releaseDate")?.take(4)?.toIntOrNull(),
                track = number("trackNumber")?.toInt(), disc = number("discNumber")?.toInt(),
                genre = text("primaryGenreName"), durationMs = number("trackTimeMillis"),
                // The 100 px artwork link names its size; the same picture is served larger.
                coverUrl = text("artworkUrl100")?.replace("100x100bb", "1200x1200bb"),
                score = 0,
            ).let { it.copy(score = score(it, first, single)) }
        }.sortedByDescending { it.score }.distinctBy { listOf(it.title, it.album, it.year, it.track) }.take(8)
    }

    private fun score(c: Candidate, t: Track, single: Boolean): Int {
        fun same(a: String?, b: String?) = a != null && b != null && TextFold.fold(a) == TextFold.fold(b)
        fun near(a: String?, b: String?) = a != null && b != null && TextFold.fold(a).let { x -> TextFold.fold(b).let { y -> x.contains(y) || y.contains(x) } }
        var s = 0
        if (single) {
            val d = c.durationMs
            if (d != null && t.durationMs > 0) s += when (kotlin.math.abs(d - t.durationMs)) { in 0..2_000 -> 50; in 0..5_000 -> 30; in 0..15_000 -> 10; else -> -20 }
            if (same(c.title, t.title)) s += 20 else if (near(c.title, t.title)) s += 8
        }
        if (same(c.artist, t.artist) || same(c.artist, t.albumArtist)) s += 15
        if (same(c.album, t.album)) s += 20 else if (near(c.album, t.album)) s += 8
        if (c.year != null && c.year == t.year) s += 5
        return s
    }

    private val thumbs = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 32
    }

    /** A small picture of a match for the list: fetched once, kept only in memory (nothing on disk). */
    suspend fun thumb(url: String): ByteArray? = synchronized(thumbs) { thumbs[url] } ?: withContext(Dispatchers.IO) {
        http(url.replace("1200x1200bb", "200x200bb"))?.takeIf { it.size in 100..(1 shl 20) }?.also { synchronized(thumbs) { thumbs[url] = it } }
    }

    /** The cover as bytes for the file: a JPEG up to 8 MB, or null. */
    suspend fun cover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        http(url)?.takeIf { it.size in 1_000..(8 shl 20) && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
    }
}
