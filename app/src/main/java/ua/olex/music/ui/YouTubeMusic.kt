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
        val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$WEB_REMIX","hl":"en","gl":"${java.util.Locale.getDefault().country.ifEmpty { "US" }}"}},"query":${quote(query)}}"""
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("User-Agent", USER_AGENT).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/").build()
        val text = http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext emptyList()
        parseSearch(text)
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

        /** Songs from a YouTube Music search answer: album tracks, then official videos, then uploads. */
        fun parseSearch(json: String): List<Track> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            val rows = ArrayList<Map<*, *>>()
            fun walk(o: Any?) {
                when (o) {
                    is Map<*, *> -> { (o["musicResponsiveListItemRenderer"] as? Map<*, *>)?.let(rows::add); o.values.forEach(::walk) }
                    is List<*> -> o.forEach(::walk)
                }
            }
            walk(root)
            val ranked = rows.mapNotNull { row ->
                val id = ((row["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String) ?: return@mapNotNull null
                val kind = Regex(""""musicVideoType"\s*:\s*"([A-Z_]+)"""").find(dump(row["overlay"]))?.groupValues?.get(1) ?: "MUSIC_VIDEO_TYPE_ATV"
                val rank = when (kind) { "MUSIC_VIDEO_TYPE_ATV" -> 0; "MUSIC_VIDEO_TYPE_OMV" -> 1; "MUSIC_VIDEO_TYPE_UGC" -> 2; else -> return@mapNotNull null }
                val columns = (row["flexColumns"] as? List<*>).orEmpty().map { c ->
                    (((c as? Map<*, *>)?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)?.get("text") as? Map<*, *>)?.get("runs") as? List<*> ?: emptyList<Any>()
                }
                val title = columns.getOrNull(0).orEmpty().joinToString("") { ((it as? Map<*, *>)?.get("text") as? String).orEmpty() }.trim()
                val runs = columns.getOrNull(1).orEmpty().mapNotNull { it as? Map<*, *> }
                fun page(run: Map<*, *>) = Regex(""""pageType"\s*:\s*"([A-Z_]+)"""").find(dump(run["navigationEndpoint"]))?.groupValues?.get(1)
                val artists = runs.filter { page(it) == "MUSIC_PAGE_TYPE_ARTIST" || page(it) == "MUSIC_PAGE_TYPE_USER_CHANNEL" }.mapNotNull { it["text"] as? String }
                val plain = runs.mapNotNull { it["text"] as? String }.filter { it.isNotBlank() && it.trim() != "•" && it.trim() != "&" && it.trim() != "," }
                val artist = artists.joinToString(", ").ifEmpty { plain.getOrNull(1) ?: plain.firstOrNull() ?: return@mapNotNull null }
                val album = runs.firstOrNull { page(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.get("text") as? String
                val duration = plain.lastOrNull { it.matches(Regex("""\d{1,2}(:\d{2}){1,2}""")) }?.split(':')?.fold(0L) { acc, p -> acc * 60 + p.toLong() } ?: 0L
                val thumbs = Regex(""""url"\s*:\s*"(https://[^"]+)"""").findAll(dump(row["thumbnail"])).map { it.groupValues[1] }.toList()
                // Album art is served at any size: ask for one large enough for the player.
                val cover = thumbs.lastOrNull()?.replace(Regex("""=w\d+-h\d+"""), "=w1080-h1080")
                if (title.isEmpty()) return@mapNotNull null
                rank to Track(TrackId("yt:$id"), "${OnlineStreams.SCHEME}://$id", title, artist, album = album, durationMs = duration * 1000, artworkUri = cover)
            }
            return ranked.sortedBy { it.first }.map { it.second }.distinctBy { it.id }.take(20)
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
