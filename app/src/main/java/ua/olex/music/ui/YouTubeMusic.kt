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
internal class YouTubeMusic(private val account: () -> Map<String, String>? = { null }) {
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
        peek<String>("artistId:${TextFold.fold(name)}")?.let { return@withContext it }
        val text = post("search", """"query":${quote(name)}""") ?: return@withContext null
        artistIds(text).let { found -> found.firstOrNull { TextFold.fold(it.second) == TextFold.fold(name) } ?: found.firstOrNull() }?.first
            ?.also { synchronized(pages) { pages["artistId:${TextFold.fold(name)}"] = it } }
    }

    suspend fun artist(id: String): OnlineArtist? = cached("artist:$id") { post("browse", """"browseId":${quote(id)}""")?.let { parseArtist(id, it) } }

    /** An album's songs, in order, with the album's cover and artist on each. */
    suspend fun album(id: String): Pair<OnlineAlbum, List<Track>>? = cached("album:$id") { post("browse", """"browseId":${quote(id)}""")?.let { parseAlbum(id, it) } }

    /** A page already loaded this session, for drawing it in the first frame when it opens again. */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = synchronized(pages) { pages[key] } as T?
    fun peekAlbum(id: String): Pair<OnlineAlbum, List<Track>>? = peek("album:$id")
    fun peekArtist(id: String): OnlineArtist? = peek("artist:$id")
    /** Puts a page in the session cache, as if it had been loaded (screenshots and tests). */
    internal fun keep(key: String, value: Any) { synchronized(pages) { pages[key] = value } }

    // Pages opened this session (an artist, an album), newest kept: going back and forth between a
    // playlist and its artist costs no network and no wait. Forgotten with the process.
    private val pages = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 48
    }
    private suspend fun <T : Any> cached(key: String, load: () -> T?): T? = withContext(Dispatchers.IO) {
        peek<T>(key) ?: load()?.also { synchronized(pages) { pages[key] = it } }
    }

    /** Resolves the stream of a song ahead of a tap (address only, no audio), so Play starts sooner. */
    suspend fun warm(id: String) { withContext(Dispatchers.IO) { audio(id) } }

    /** A page of YouTube Music's radio for a song, and the token for the page after it. */
    data class RadioPage(val tracks: List<Track>, val continuation: String?)

    /**
     * YouTube Music's own radio for a song ("Start radio"): the songs its recommender lines up,
     * in its order, which keeps tempo and mood flowing from one song to the next.
     */
    suspend fun radio(videoId: String): RadioPage? = withContext(Dispatchers.IO) {
        post("next", """"videoId":${quote(videoId)},"playlistId":${quote("RDAMVM$videoId")},"params":"wAEB","isAudioOnly":true""")?.let(::parseRadio)
    }

    /** The radio's next page. */
    suspend fun radioMore(continuation: String): RadioPage? = withContext(Dispatchers.IO) {
        post("next", """"continuation":${quote(continuation)}""")?.let(::parseRadio)
    }

    /** The song on YouTube Music for a file in the library: the best match of a search for artist and title. */
    suspend fun find(track: Track): Track? = search("${track.artist.substringBefore(",")} ${track.title}").let { found ->
        found.firstOrNull { sameSong(it, track) } ?: found.firstOrNull { TextFold.fold(it.artist).contains(TextFold.fold(track.artist.substringBefore(",").trim())) }
    }

    /** A mood of YouTube Music's home ("Energize", "Sad", "Sleep"): its label and the filter that opens it. */
    data class Mood(val label: String, val params: String?)
    /** What a mood offers: curated playlists, and songs when the page lists any. */
    data class MoodPage(val playlists: List<OnlineAlbum>, val songs: List<Track>)

    @Volatile private var moodsCache: List<Mood>? = null

    /** The moods as YouTube Music's home lists them; the usual set (without filters) when it can't be reached. */
    suspend fun moods(): List<Mood> = moodsCache ?: withContext(Dispatchers.IO) {
        (runCatching { post("browse", """"browseId":"FEmusic_home"""")?.let(::parseMoods) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext DEFAULT_MOODS)
            .also { moodsCache = it }
    }

    /** One mood's page: YouTube Music's home filtered by it. */
    suspend fun mood(mood: Mood): MoodPage? = withContext(Dispatchers.IO) {
        val params = mood.params ?: return@withContext null
        post("browse", """"browseId":"FEmusic_home","params":${quote(params)}""")?.let(::parseMoodPage)
    }

    /** A home shelf: its title, and its songs or its cards. */
    data class Shelf(val title: String, val songs: List<Track>, val cards: List<Card>)
    /** A card on a shelf; a mix ([kind] MIX) plays as radio from its playlist id. */
    data class Card(val id: String, val title: String, val subtitle: String?, val cover: String?, val kind: Kind) { enum class Kind { ALBUM, PLAYLIST, ARTIST, MIX, SONG } }

    /** YouTube Music's home: the owner's own when signed in (quick picks, mixes, listen again...). */
    suspend fun home(): List<Shelf> = withContext(Dispatchers.IO) {
        post("browse", """"browseId":"FEmusic_home"""")?.let(::parseHome).orEmpty()
    }

    /** A mix or other watch playlist, played as YouTube Music plays it ("My Mix 1"). */
    suspend fun playlistRadio(playlistId: String): RadioPage? = withContext(Dispatchers.IO) {
        post("next", """"playlistId":${quote(playlistId)},"params":"wAEB","isAudioOnly":true""")?.let(::parseRadio)
    }

    /** One call to YouTube Music, as its web app makes it. */
    private fun post(endpoint: String, fields: String): String? {
        val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"$WEB_REMIX","hl":"en","gl":"${java.util.Locale.getDefault().country.ifEmpty { "US" }}"}},$fields}"""
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("User-Agent", USER_AGENT).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            // Signed in: the owner's own home, mixes and radio (YouTubeAccount).
            .apply { account()?.forEach { (k, v) -> header(k, v) } }
            .build()
        return http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    /** The audio stream of a song: the best Opus or AAC stream, kept for four hours (they expire). */
    fun audio(id: String): Uri? = runCatching {
        init()
        synchronized(streams) { streams[id]?.takeIf { System.currentTimeMillis() - it.second < 4 * 3_600_000L }?.first }?.let { return it }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$id")
        val best = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate } ?: return null
        // What the player will say about the sound: codec and bitrate of the stream it plays.
        val codec = when { best.format?.name?.contains("opus", true) == true || best.codec?.contains("opus", true) == true -> "Opus"
            best.format?.name?.contains("m4a", true) == true || best.codec?.contains("mp4a", true) == true -> "AAC"; else -> best.format?.name ?: "Stream" }
        formats[id] = "$codec \u00b7 ${best.averageBitrate.takeIf { it > 0 } ?: best.bitrate / 1000} kbps"
        Uri.parse(best.content).also { synchronized(streams) { streams[id] = it to System.currentTimeMillis() } }
    }.getOrNull()

    private val streams = HashMap<String, Pair<Uri, Long>>()
    private val formats = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** "Opus \u00b7 160 kbps" once the song's stream was chosen; null before. Quality only: where it plays from is the row's mark. */
    fun format(id: String): String? = formats[id]

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
            return songRows(collect(root, "musicResponsiveListItemRenderer"), fallbackArtist, keepOrder)
        }

        /** Song rows, already parsed, as tracks. */
        private fun songRows(rows: List<Map<*, *>>, fallbackArtist: String? = null, keepOrder: Boolean = false): List<Track> {
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

        /** A radio answer: each queued song (title, "Artist • Album • Year", length, cover) and the continuation. */
        fun parseRadio(json: String): RadioPage? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val tracks = collect(root, "playlistPanelVideoRenderer").mapNotNull { row ->
                val id = row["videoId"] as? String ?: return@mapNotNull null
                val title = text(row["title"]).ifEmpty { return@mapNotNull null }
                val runs = ((row["longBylineText"] as? Map<*, *>)?.get("runs") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                fun page(run: Map<*, *>) = Regex(""""pageType"\s*:\s*"([A-Z_]+)"""").find(dump(run["navigationEndpoint"]))?.groupValues?.get(1)
                val artists = runs.filter { page(it) == "MUSIC_PAGE_TYPE_ARTIST" || page(it) == "MUSIC_PAGE_TYPE_USER_CHANNEL" }.mapNotNull { it["text"] as? String }.distinct()
                val parts = text(row["longBylineText"]).split("•").map { it.trim() }.filter { it.isNotEmpty() }
                // A music video's line is "Channel • 1.8B views • 19M likes": the song is in its title.
                val video = parts.any { it.endsWith(" views") || it.endsWith(" likes") }
                val (artist, song) = if (video) songFromVideo(title, parts.firstOrNull().orEmpty())
                    else (artists.joinToString(", ").ifEmpty { parts.firstOrNull() ?: return@mapNotNull null }) to title
                val album = if (video) null else runs.firstOrNull { page(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.get("text") as? String
                val year = if (video) null else parts.lastOrNull()?.takeIf { it.matches(Regex("""\d{4}""")) }?.toInt()
                val length = text(row["lengthText"]).split(':').mapNotNull { it.trim().toLongOrNull() }.fold(0L) { acc, p -> acc * 60 + p }
                val cover = biggestImage(row["thumbnail"])
                Track(TrackId("yt:$id"), "${OnlineStreams.SCHEME}://$id", song, artist, album = album, durationMs = length * 1000, year = year, artworkUri = cover)
            }.distinctBy { it.id }
            val token = Regex(""""nextRadioContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: Regex(""""nextContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            return RadioPage(tracks, token)
        }

        private val videoNoise = Regex("""\s*[(\[][^)\]]*\b(official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|remaster(ed)?|m/?v)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

        /**
         * Artist and song from a music video's title, "Artist - Song (Official Video) [4K Remaster]":
         * the "official / HD / 4K" brackets go; the channel ("RHINO", "beegees - Topic") is used only
         * when the title names no artist.
         */
        fun songFromVideo(title: String, channel: String): Pair<String, String> {
            val clean = title.replace(videoNoise, "")
                .replace(Regex("""\s+[-|\u2013]\s+(official|music video|video|audio|lyrics?|visuali[sz]er|hd|4k)\b.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ").trim()
            val split = Regex("""^(.+?)\s+[-\u2013]\s+(.+)$""").find(clean)
            if (split != null) return split.groupValues[1].trim() to split.groupValues[2].trim()
            val artist = channel.replace(Regex("""\s*-\s*Topic$|VEVO$|\s*\(official\)$|\s*Official$""", RegexOption.IGNORE_CASE), "").trim()
            return artist to clean
        }

        /** Home shelves: songs (rows) or cards (albums, playlists, artists, mixes); podcasts and episodes are left out. */
        fun parseHome(json: String): List<Shelf> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            return collect(root, "musicCarouselShelfRenderer").mapNotNull { shelf ->
                val header = shelf["header"] as? Map<*, *>
                val title = text((header?.get("musicCarouselShelfBasicHeaderRenderer") as? Map<*, *>)?.get("title")).ifEmpty { return@mapNotNull null }
                val contents = (shelf["contents"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                val songs = songRows(contents.mapNotNull { it["musicResponsiveListItemRenderer"] as? Map<*, *> }, keepOrder = true)
                val cards = contents.mapNotNull { it["musicTwoRowItemRenderer"] as? Map<*, *> }.mapNotNull { card ->
                    val nav = dump(card["navigationEndpoint"])
                    val name = text(card["title"]).ifEmpty { return@mapNotNull null }
                    val sub = text(card["subtitle"]).ifEmpty { null }
                    val cover = biggestImage(card["thumbnailRenderer"])
                    fun id(key: String) = Regex(""""$key"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1)
                    when {
                        nav.contains("MUSIC_PAGE_TYPE_PODCAST") || nav.contains("NON_MUSIC") -> null
                        nav.contains("MUSIC_PAGE_TYPE_ALBUM") -> id("browseId")?.let { Card(it, name, sub, cover, Card.Kind.ALBUM) }
                        nav.contains("MUSIC_PAGE_TYPE_ARTIST") -> id("browseId")?.let { Card(it, name, sub, cover, Card.Kind.ARTIST) }
                        nav.contains("MUSIC_PAGE_TYPE_PLAYLIST") -> id("browseId")?.let { Card(it, name, sub, cover, Card.Kind.PLAYLIST) }
                        nav.contains("watchPlaylistEndpoint") -> id("playlistId")?.let { Card(it, name, sub, cover, Card.Kind.MIX) }
                        nav.contains("watchEndpoint") -> id("videoId")?.let { Card(it, name, sub, cover, Card.Kind.SONG) }
                        else -> null
                    }
                }.distinctBy { it.id }
                if (songs.isEmpty() && cards.isEmpty()) null else Shelf(title, songs, cards)
            }.filterNot { it.title.contains("podcast", true) || it.title.contains("episode", true) }
        }

        /**
         * The artist in a card's subtitle, "Song \u2022 predayed", "Video \u2022 Batón \u2022 201K views":
         * the kind of item, view counts and years are left out, the first name that remains is it.
         */
        fun artistOf(subtitle: String?): String {
            val kinds = setOf("song", "video", "single", "ep", "album", "playlist", "episode", "podcast", "artist", "profile")
            return subtitle.orEmpty().split('\u2022').map { it.trim() }.firstOrNull { part ->
                part.isNotEmpty() && part.lowercase() !in kinds && !part.matches(Regex("""\d{4}""")) &&
                    !Regex("""(views|plays|likes|subscribers|songs)$""", RegexOption.IGNORE_CASE).containsMatchIn(part)
            }.orEmpty()
        }

        /** The usual moods, in YouTube Music's order, for when its home can't be read. */
        val DEFAULT_MOODS = listOf("Energize", "Feel good", "Relax", "Party", "Workout", "Commute", "Romance", "Sad", "Focus", "Sleep").map { Mood(it, null) }

        /** The home's mood chips (Podcasts left out: this is a music player). */
        fun parseMoods(json: String): List<Mood> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            return collect(root, "chipCloudChipRenderer").mapNotNull { chip ->
                val label = text(chip["text"]).ifEmpty { return@mapNotNull null }
                if (label.equals("Podcasts", true)) return@mapNotNull null
                val params = Regex(""""params"\s*:\s*"([^"]+)"""").find(dump(chip["navigationEndpoint"]))?.groupValues?.get(1) ?: return@mapNotNull null
                Mood(label, params)
            }.distinctBy { it.label }
        }

        /** A mood page: every playlist card in its shelves, and any song rows. */
        fun parseMoodPage(json: String): MoodPage {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return MoodPage(emptyList(), emptyList())
            val playlists = collect(root, "musicTwoRowItemRenderer").mapNotNull { card ->
                val nav = dump(card["navigationEndpoint"])
                if (!nav.contains("MUSIC_PAGE_TYPE_PLAYLIST")) return@mapNotNull null
                val id = Regex(""""browseId"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1) ?: return@mapNotNull null
                OnlineAlbum(id, text(card["title"]), text(card["subtitle"]).ifEmpty { null }, biggestImage(card["thumbnailRenderer"]))
            }.distinctBy { it.id }
            return MoodPage(playlists, parseSearch(json, keepOrder = true))
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
        fun localCopy(online: Track, library: LibraryIndex): Track? = indexOf(library).copyOf(online)

        // The index of the current library, built once per library (identity), shared by the pages,
        // Search and the player.
        @Volatile private var indexed: Pair<LibraryIndex, Index>? = null

        /** The library's [Index], building it when the library changed. Call off the main thread when new. */
        fun indexOf(library: LibraryIndex): Index = indexed?.takeIf { it.first === library }?.second
            ?: Index(library.songs).also { indexed = library to it }

        /** The library's [Index] only if it is already built: cheap enough for a composable. */
        fun builtIndex(library: LibraryIndex): Index? = indexed?.takeIf { it.first === library }?.second

        /** Keys under which a library song can be found by an online one (each of its artists). */
        fun keysOf(song: Track): List<String> = (song.artists + song.artist + song.artist.substringBefore(",")).map { clean(song.title) + "\u0000" + clean(it.substringBefore(",")) }.distinct()

        /** The key an online song is looked up by. */
        fun keyOf(online: Track): String = clean(online.title) + "\u0000" + clean(online.artist.substringBefore(","))

        /**
         * The library indexed for [localCopy]: built once per library, so matching a mix of fifty
         * songs is fifty lookups, not fifty passes over every file with regular expressions.
         */
        class Index(songs: List<Track>) {
            private val byKey = HashMap<String, MutableList<Track>>().apply { songs.forEach { s -> keysOf(s).forEach { getOrPut(it) { ArrayList(1) } += s } } }
            fun copyOf(online: Track): Track? = byKey[keyOf(online)]?.firstOrNull { song ->
                online.durationMs <= 0 || song.durationMs <= 0 || kotlin.math.abs(song.durationMs - online.durationMs) <= 3_000
            }
        }

        private fun clean(s: String) = TextFold.fold(s).replace(Regex("""\s*[(\[].*?[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()

        /** The same recording: artist and title alike once extras are dropped, lengths within three seconds. */
        fun sameSong(online: Track, song: Track): Boolean {
            val title = clean(online.title); val artist = clean(online.artist.substringBefore(","))
            return clean(song.title) == title && (clean(song.artist) == artist || song.artists.any { clean(it) == artist } || clean(song.artist.substringBefore(",")) == artist) &&
                (online.durationMs <= 0 || song.durationMs <= 0 || kotlin.math.abs(song.durationMs - online.durationMs) <= 3_000)
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
