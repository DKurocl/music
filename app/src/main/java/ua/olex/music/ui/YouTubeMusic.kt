package ua.olex.music.ui

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.playback.OnlineStreams
import java.util.concurrent.TimeUnit

/**
 * YouTube Music as a second source, the way NewPipe reaches it (no account, nothing of the owner's
 * sent): song search, and a song's audio stream found only when the player opens it.
 *
 * Online songs are ordinary [Track]s with the id `yt:<videoId>` and the address `ytm://<videoId>`,
 * so lists, queue and player treat them like the owner's files. Wherever an online song is also in
 * the library ([localCopy]), the file is played instead: better sound, no data, works offline.
 */
internal class YouTubeMusic {
    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) { if (!ready) { NewPipe.init(Http(http)); ready = true } }
    }

    /**
     * Songs for a query, as YouTube Music's own search answers it (the request its web app makes):
     * album tracks first, then official videos, then other uploads; artists, profiles and podcasts
     * are left out.
     */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        post("search", """"query":${quote(query)}""")?.let(::parseSearch) ?: emptyList()
    }

    /** An artist on YouTube Music: photo, a few lines about them, top songs, albums and videos. */
    data class OnlineArtist(val id: String, val name: String, val photo: String?, val about: String?, val songs: List<Track>, val albums: List<OnlineAlbum>, val videos: List<Track>)
    data class OnlineAlbum(val id: String, val title: String, val subtitle: String?, val cover: String?)

    /** The artist's YouTube Music page id for a name: the search's artist whose name matches best. */
    suspend fun artistId(name: String): String? = withContext(Dispatchers.IO) {
        val text = post("search", """"query":${quote(name)}""") ?: return@withContext null
        artistIds(text).let { found -> found.firstOrNull { TextFold.fold(it.second) == TextFold.fold(name) } ?: found.firstOrNull() }?.first
    }

    suspend fun artist(id: String): OnlineArtist? = withContext(Dispatchers.IO) {
        post("browse", """"browseId":${quote(id)}""")?.let { parseArtist(id, it) }
    }

    /** An album's songs, in order, with the album's cover and artist on each. */
    suspend fun album(id: String): Pair<OnlineAlbum, List<Track>>? = withContext(Dispatchers.IO) {
        post("browse", """"browseId":${quote(id)}""")?.let { parseAlbum(id, it) }
    }

    /** One call to YouTube Music, as its web app makes it. */
    private fun post(endpoint: String, fields: String): String? {
        val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$WEB_REMIX","hl":"en","gl":"${java.util.Locale.getDefault().country.ifEmpty { "US" }}"}},$fields}"""
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("User-Agent", USER_AGENT).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/").build()
        return http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    /** The audio stream of a song: the best Opus or AAC stream, kept for four hours (they expire). */
    fun audio(id: String): Uri? = runCatching {
        init()
        synchronized(streams) { streams[id]?.takeIf { System.currentTimeMillis() - it.second < 4 * 3_600_000L }?.first }?.let { return it }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$id")
        val best = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate } ?: return null
        Uri.parse(best.content).also { synchronized(streams) { streams[id] = it to System.currentTimeMillis() } }
    }.getOrNull()

    private val streams = HashMap<String, Pair<Uri, Long>>()

    companion object {
        const val WEB_REMIX = "1.20251110.01.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        fun isOnline(track: Track) = track.id.value.startsWith("yt:")

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

        /** Every object under [key] anywhere in a parsed answer, in document order. */
        private fun collect(root: Any?, key: String): List<Map<*, *>> {
            val out = ArrayList<Map<*, *>>()
            fun walk(o: Any?) {
                when (o) {
                    is Map<*, *> -> { (o[key] as? Map<*, *>)?.let(out::add); o.values.forEach(::walk) }
                    is List<*> -> o.forEach(::walk)
                }
            }
            walk(root)
            return out
        }

        private fun text(o: Any?): String = ((o as? Map<*, *>)?.get("runs") as? List<*>).orEmpty().joinToString("") { ((it as? Map<*, *>)?.get("text") as? String).orEmpty() }.trim()
        private fun biggestImage(o: Any?): String? = Regex(""""url"\s*:\s*"(https://[^"]+)"""").findAll(dump(o)).map { it.groupValues[1] }.lastOrNull()
            ?.replace(Regex("""=w\d+-h\d+"""), "=w1080-h1080")

        /** Artist ids and names in a search answer (results whose link is an artist page). */
        fun artistIds(json: String): List<Pair<String, String>> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            return collect(root, "musicResponsiveListItemRenderer").mapNotNull { row ->
                val nav = dump(row["navigationEndpoint"])
                if (!nav.contains("MUSIC_PAGE_TYPE_ARTIST")) return@mapNotNull null
                val id = Regex(""""browseId"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1) ?: return@mapNotNull null
                val name = (row["flexColumns"] as? List<*>)?.firstOrNull()?.let { text(((it as? Map<*, *>)?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)?.get("text")) }.orEmpty()
                id to name
            }.distinctBy { it.first }
        }

        /** An artist page: header, description, every song row and every album and video card. */
        fun parseArtist(id: String, json: String): OnlineArtist? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val header = collect(root, "musicImmersiveHeaderRenderer").firstOrNull() ?: collect(root, "musicVisualHeaderRenderer").firstOrNull()
            val name = text(header?.get("title")).ifEmpty { return null }
            val about = collect(root, "musicDescriptionShelfRenderer").firstOrNull()?.let { text(it["description"]) }?.takeIf { it.isNotBlank() }
            val songs = parseSearch(json, fallbackArtist = name).filter { it.durationMs > 0 || it.album != null }
            val cards = collect(root, "musicTwoRowItemRenderer")
            val albums = cards.mapNotNull { card ->
                val nav = dump(card["navigationEndpoint"])
                if (!nav.contains("MUSIC_PAGE_TYPE_ALBUM")) return@mapNotNull null
                val albumId = Regex(""""browseId"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1) ?: return@mapNotNull null
                OnlineAlbum(albumId, text(card["title"]), text(card["subtitle"]).ifEmpty { null }, biggestImage(card["thumbnailRenderer"]))
            }.distinctBy { it.id }
            val videos = cards.mapNotNull { card ->
                val videoId = Regex(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""").find(dump(card["navigationEndpoint"]))?.groupValues?.get(1) ?: return@mapNotNull null
                Track(TrackId("yt:$videoId"), "${OnlineStreams.SCHEME}://$videoId", text(card["title"]), name, artworkUri = biggestImage(card["thumbnailRenderer"]))
            }.distinctBy { it.id }
            return OnlineArtist(id, name, biggestImage(header?.get("thumbnail")), about, songs.filterNot { s -> videos.any { it.id == s.id } }, albums, videos)
        }

        /** An album page: its title, artist and cover, then its songs in order. */
        fun parseAlbum(id: String, json: String): Pair<OnlineAlbum, List<Track>>? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val header = collect(root, "musicResponsiveHeaderRenderer").firstOrNull() ?: collect(root, "musicDetailHeaderRenderer").firstOrNull()
            val title = text(header?.get("title")).ifEmpty { return null }
            val artist = text(header?.get("straplineTextOne")).ifEmpty { text(header?.get("subtitle")).split("•").getOrNull(1)?.trim().orEmpty() }
            val cover = biggestImage(header?.get("thumbnail"))
            val album = OnlineAlbum(id, title, text(header?.get("subtitle")).ifEmpty { null }, cover)
            val songs = parseSearch(json, fallbackArtist = artist.ifEmpty { null }, keepOrder = true).map { it.copy(album = it.album ?: title, artworkUri = cover ?: it.artworkUri) }
            return album to songs
        }

        /** Songs from a YouTube Music search answer: album tracks, then official videos, then uploads. */
        fun parseSearch(json: String, fallbackArtist: String? = null, keepOrder: Boolean = false): List<Track> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            val rows = collect(root, "musicResponsiveListItemRenderer")
            val ranked = rows.mapNotNull { row ->
                val id = ((row["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String) ?: return@mapNotNull null
                val kind = Regex(""""musicVideoType"\s*:\s*"([A-Z_]+)"""").find(dump(row["overlay"]))?.groupValues?.get(1) ?: "MUSIC_VIDEO_TYPE_ATV"
                val rank = when (kind) { "MUSIC_VIDEO_TYPE_ATV" -> 0; "MUSIC_VIDEO_TYPE_OMV" -> 1; "MUSIC_VIDEO_TYPE_UGC" -> 2; else -> return@mapNotNull null }
                val columns = (row["flexColumns"] as? List<*>).orEmpty().map { c ->
                    (((c as? Map<*, *>)?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)?.get("text") as? Map<*, *>)?.get("runs") as? List<*> ?: emptyList<Any>()
                }
                val title = columns.getOrNull(0).orEmpty().joinToString("") { ((it as? Map<*, *>)?.get("text") as? String).orEmpty() }.trim()
                // Search rows put "Song • Artist • Album • 3:30" in the second column; artist and album
                // pages spread artist, album and plays over later columns and the length on the right.
                val runs = columns.drop(1).flatten().mapNotNull { it as? Map<*, *> }
                fun page(run: Map<*, *>) = Regex(""""pageType"\s*:\s*"([A-Z_]+)"""").find(dump(run["navigationEndpoint"]))?.groupValues?.get(1)
                val artists = runs.filter { page(it) == "MUSIC_PAGE_TYPE_ARTIST" || page(it) == "MUSIC_PAGE_TYPE_USER_CHANNEL" }.mapNotNull { it["text"] as? String }.distinct()
                val plain = columns.getOrNull(1).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
                    .filter { it.isNotBlank() && it.trim() != "•" && it.trim() != "&" && it.trim() != "," }
                val artist = artists.joinToString(", ").ifEmpty { fallbackArtist ?: plain.getOrNull(1) ?: plain.firstOrNull() ?: return@mapNotNull null }
                val album = runs.firstOrNull { page(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.get("text") as? String
                val clock = Regex("""\d{1,2}(:\d{2}){1,2}""")
                val fixed = Regex(""""text"\s*:\s*"(\d{1,2}(?::\d{2}){1,2})"""").find(dump(row["fixedColumns"]))?.groupValues?.get(1)
                val duration = (plain.lastOrNull { it.matches(clock) } ?: fixed)?.split(':')?.fold(0L) { acc, p -> acc * 60 + p.toLong() } ?: 0L
                val thumbs = Regex(""""url"\s*:\s*"(https://[^"]+)"""").findAll(dump(row["thumbnail"])).map { it.groupValues[1] }.toList()
                // Album art is served at any size: ask for one large enough for the player.
                val cover = thumbs.lastOrNull()?.replace(Regex("""=w\d+-h\d+"""), "=w1080-h1080")
                if (title.isEmpty()) return@mapNotNull null
                rank to Track(TrackId("yt:$id"), "${OnlineStreams.SCHEME}://$id", title, artist, album = album, durationMs = duration * 1000, artworkUri = cover)
            }
            return (if (keepOrder) ranked else ranked.sortedBy { it.first }).map { it.second }.distinctBy { it.id }.take(if (keepOrder) 200 else 20)
        }

        private fun dump(o: Any?): String = when (o) {
            is Map<*, *> -> o.entries.joinToString(",", "{", "}") { "\"${it.key}\":${dump(it.value)}" }
            is List<*> -> o.joinToString(",", "[", "]") { dump(it) }
            is String -> "\"" + o.replace("\"", "\\\"") + "\""
            else -> o.toString()
        }

        /**
         * The owner's own file for an online song: same artist and title (ignoring case, accents and
         * "(Remastered)"-style extras) and a length within three seconds when both are known.
         */
        fun localCopy(online: Track, library: LibraryIndex): Track? {
            fun clean(s: String) = TextFold.fold(s).replace(Regex("""\s*[(\[].*?[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()
            val title = clean(online.title); val artist = clean(online.artist)
            return library.songs.firstOrNull { song ->
                clean(song.title) == title && (clean(song.artist) == artist || song.artists.any { clean(it) == artist }) &&
                    (online.durationMs <= 0 || song.durationMs <= 0 || kotlin.math.abs(song.durationMs - online.durationMs) <= 3_000)
            }
        }
    }

    /** NewPipe's network calls, over OkHttp. */
    private class Http(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody(request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull())
            val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) -> builder.removeHeader(name); values.forEach { builder.addHeader(name, it) } }
            client.newCall(builder.build()).execute().use { r ->
                if (r.code == 429) throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("Too many requests", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }
}
