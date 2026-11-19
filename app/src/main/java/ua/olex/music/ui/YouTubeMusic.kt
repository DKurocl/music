package ua.olex.music.ui

import kotlinx.coroutines.sync.withLock
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
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
internal class YouTubeMusic(
    private val transport: (suspend (String, String, String) -> String?)? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val pageStore: OnlinePageStore? = null,
    private val pageScope: () -> String = { "public" },
    private val account: () -> Map<String, String>? = { null },
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).build(),
) {
    private val musicHttp = http.newBuilder().followRedirects(false).build()
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
    suspend fun search(query: String): List<Track> = withContext(ioDispatcher) {
        post("search", """"query":${quote(query)}""")?.let(::parseSearch) ?: emptyList()
    }

    /** The provider marks official music clips separately from audio and user uploads. */
    suspend fun musicVideos(artist: String, title: String): List<Track> = withContext(ioDispatcher) {
        val answer = post("search", """"query":${quote("$artist $title official video")}""")
            ?: throw java.io.IOException("Video search failed")
        val root = ua.olex.music.lyrics.Json.parse(answer) ?: return@withContext emptyList()
        val rows = collect(root, "musicResponsiveListItemRenderer")
            .filter { dump(it["overlay"]).contains("MUSIC_VIDEO_TYPE_OMV") }
        songRows(rows, fallbackArtist = artist, keepOrder = true).distinctBy { it.id }
    }

    /** An artist on YouTube Music: photo, a few lines about them, top songs, albums and videos. */
    data class OnlineArtist(val id: String, val name: String, val photo: String?, val about: String?, val songs: List<Track>, val albums: List<OnlineAlbum>, val videos: List<Track>,
        val singles: List<OnlineAlbum> = emptyList(), val related: List<Card> = emptyList(), val listeners: String? = null, val partial: Boolean = false)

    /** Artists and albums YouTube Music finds for a query, beside its songs (Search). */
    data class Found(val songs: List<Track>, val artists: List<Card>, val albums: List<Card>)

    /**
     * Songs, artists and albums for a query: the plain search (songs, its top result and any albums
     * it lists) and the artists-only search, asked at the same time.
     */
    suspend fun searchAll(query: String): Found = kotlinx.coroutines.coroutineScope {
        val scope = pageScope()
        val artists = async(ioDispatcher) { post("search", """"query":${quote(query)},"params":"$ARTISTS_ONLY"""")?.let(::searchCards).orEmpty() }
        val plain = withContext(ioDispatcher) { post("search", """"query":${quote(query)}""") }
            // No answer (refused, limited, signed out) is an error the screen says, not "nothing found".
            ?: throw java.io.IOException(when (lastStatus) { 429 -> "YouTube Music is limiting requests; try again in a minute"; 401, 403 -> "YouTube Music refused the request"; else -> "YouTube Music didn't answer" })
        val cards = searchCards(plain)
        Found(parseSearch(plain),
            (cards.filter { it.kind == Card.Kind.ARTIST } + artists.await()).distinctBy { it.id }.take(8),
            cards.filter { it.kind == Card.Kind.ALBUM }.take(10)).also { found ->
            if (pageScope() == scope) withContext(ioDispatcher) { rememberAlbumLinks(scope, found.songs) }
        }
    }
    data class OnlineAlbum(val id: String, val title: String, val subtitle: String?, val cover: String?, val partial: Boolean = false)

    /** Prefer an exact artist identity and keep confirmed links available offline. */
    suspend fun artistId(name: String, offline: Boolean = false): String? = withContext(ioDispatcher) {
        val folded = TextFold.fold(name)
        val scope = pageScope()
        val key = "artistId:$folded"
        val memoryKey = "$scope/$key"
        peek<String>(memoryKey)?.let { return@withContext it }
        pageStore?.read(scope, key)?.optString("id")?.takeIf { it.isNotBlank() }?.let {
            keep(memoryKey, it)
            return@withContext it
        }
        if (offline) return@withContext null
        val fields = """"query":${quote(name)}"""
        suspend fun exact(headers: Map<String, String>?, filtered: Boolean): Pair<String, String>? {
            val args = if (filtered) fields + ",\"params\":\"$ARTISTS_ONLY\"" else fields
            return catalogueRequest { post("search", args, headers = headers) }?.let(::artistIds)
                ?.firstOrNull { TextFold.fold(it.second) == folded }
        }
        val headers = account()
        val exact = exact(null, true) ?: exact(null, false)
            ?: headers?.let { exact(it, false) }
        if (pageScope() != scope) return@withContext null
        exact?.let { (id, _) ->
            keep(memoryKey, id)
            pageStore?.write(scope, key, org.json.JSONObject().put("id", id))
        }
        exact?.first
    }

    suspend fun artist(
        id: String, offline: Boolean = false, full: Boolean = false,
        knownName: String? = null,
        onPage: (OnlineArtist) -> Unit = {},
    ): OnlineArtist? = withContext(ioDispatcher) {
        val scope = pageScope()
        val key = if (full) "artist-full:$id" else "artist:$id"
        cached(if (offline) "$scope/offline/$key" else "$scope/$key") {
            fun read(name: String) = pageStore?.read(scope, name)?.let { runCatching { OnlinePageJson.artist(it) }.getOrNull() }
            val saved = read(key) ?: read(if (full) "artist:$id" else "artist-full:$id")
            if (offline) return@cached saved
            saved?.let(onPage)
            val fresh = networkFallback(saved) {
                suspend fun browse(headers: Map<String, String>?): Pair<String, OnlineArtist>? {
                    return cataloguePage(id, headers) { json -> parseArtist(id, json, knownName)?.let { json to it } }
                }
                val public = browse(null)
                val headers = if (public == null) account() else null
                val (json, initial) = public ?: headers?.let { browse(it) } ?: return@networkFallback null
                if (full) {
                    if (saved == null) onPage(initial)
                    expandArtist(initial, json, scope, headers, onPage)
                } else initial
            }
            if (pageScope() != scope) return@cached null
            if (fresh != null && fresh !== saved) {
                pageStore?.write(scope, key, OnlinePageJson.artist(fresh))
                rememberAlbumLinks(scope, fresh.songs + fresh.videos)
            }
            fresh
        }
    }

    /** Cached album metadata can open offline; online opens refresh the disk copy. */
    suspend fun album(id: String, offline: Boolean = false, summary: OnlineAlbum? = null): Pair<OnlineAlbum, List<Track>>? = withContext(ioDispatcher) {
        val scope = pageScope()
        val key = "album:$id"
        val cursorKey = "$scope/$id"
        cached(if (offline) "$scope/offline/$key" else "$scope/$key",
            keepResult = { value: Pair<OnlineAlbum, List<Track>> -> !value.first.partial || more.containsKey(cursorKey) }) {
            val saved = pageStore?.read(scope, key)?.let {
                runCatching { OnlinePageJson.album(it.getJSONObject("album")) to OnlinePageJson.tracks(it, "songs") }.getOrNull()
            }
            if (offline) return@cached saved
            val fresh = networkFallback(saved) {
                suspend fun browse(headers: Map<String, String>?): Pair<OnlineAlbum, List<Track>>? {
                    return cataloguePage(browseId(id), headers) { json ->
                        val token = playlistBrowseToken(json)
                        parseAlbum(id, json, summary)?.takeUnless { id.startsWith("MPRE") && it.second.isEmpty() && token == null }?.also {
                            if (pageScope() == scope) rememberContinuation(cursorKey, token?.let { MusicPage(it, headers != null) })
                        }
                    }
                }
                val headers = account()
                val publicFallback = headers != null && id.removePrefix("VL") !in setOf("LM", "SE")
                browse(headers) ?: (if (publicFallback) browse(null) else null) ?: saved?.takeUnless { it.first.partial }
                    ?: summary?.let {
                        val fallback = playlistFromQueue(it, headers) ?: if (publicFallback) playlistFromQueue(it, null) else null
                        val result = if (fallback?.next == null && fallback?.album?.partial != false)
                            publicPlaylist(it) ?: fallback else fallback
                        result?.let { page ->
                            if (pageScope() == scope) rememberContinuation(cursorKey, page.next)
                            page.album to page.tracks
                        }
                    }
            }
            if (pageScope() != scope) return@cached null
            if (fresh != null && fresh !== saved && !fresh.first.partial) saveAlbum(scope, id, fresh)
            fresh
        }
    }

    /** An incomplete view keeps confirmed tracks playable while a full browse can be retried. */
    suspend fun albumPage(summary: OnlineAlbum, seed: Track?, offline: Boolean): Pair<OnlineAlbum, List<Track>>? = withContext(ioDispatcher) {
        val scope = pageScope()
        album(summary.id, offline, summary)?.let { return@withContext it }
        if (offline || !summary.id.startsWith("MPRE")) return@withContext null
        val anchor = seed?.takeIf { isOnline(it) && albumOf(it) == summary.id }
        val query = listOfNotNull(summary.title, anchor?.artist ?: summary.subtitle?.let(::artistOf)).joinToString(" ")
        val found = catalogueSearch(query).filter { albumOf(it) == summary.id }
        if (pageScope() != scope) return@withContext null
        val songs = (found + listOfNotNull(anchor)).distinctBy { it.id }
        songs.takeIf { it.isNotEmpty() }?.let { summary.copy(partial = true) to it }
    }

    /** Prefer the channel linked from the song. Search credits can recover a page with a failed browse. */
    suspend fun artistPage(name: String, channelId: String?, seed: Track?, offline: Boolean,
        onPage: (OnlineArtist) -> Unit = {}): OnlineArtist? = withContext(ioDispatcher) {
        val scope = pageScope()
        val id = channelId ?: seed?.let { artistOfTrack(it)?.first } ?: artistId(name, offline)
        if (pageScope() != scope) return@withContext null
        if (id != null) artist(id, offline, full = !offline, knownName = name, onPage = onPage)?.let { return@withContext it }
        if (offline) return@withContext null
        val folded = TextFold.fold(name)
        fun matchesName(track: Track) = TextFold.fold(track.artist) == folded ||
            artistCredits(track).any { TextFold.fold(it) == folded }
        val anchor = seed?.takeIf { isOnline(it) && matchesName(it) }
        val found = catalogueSearch(name).filter { track ->
            if (id != null) artistIdsByVideo[track.id.value.removePrefix("yt:")].orEmpty().any { it.first == id }
            else matchesName(track)
        }
        if (pageScope() != scope) return@withContext null
        val songs = (found + listOfNotNull(anchor)).distinctBy { it.id }
        if (songs.isEmpty()) return@withContext null
        OnlineArtist(id ?: songs.firstNotNullOfOrNull { artistOfTrack(it)?.first }.orEmpty(), name,
            null, null, songs, emptyList(), emptyList(), partial = true)
    }

    private suspend fun catalogueSearch(query: String): List<Track> {
        val args = """"query":${quote(query)}""""
        val response = catalogueRequest { post("search", args) } ?: return emptyList()
        return parseSearch(response)
    }

    /** A normal page navigation can carry the first shelf even when its JSON browse endpoint fails. */
    private suspend fun <T : Any> cataloguePage(id: String, headers: Map<String, String>?, parse: (String) -> T?): T? {
        val json = catalogueRequest { post("browse", """"browseId":${quote(id)}"""", headers = headers) }
        json?.let(parse)?.let { return it }
        if (transport != null) return null
        val url = catalogueUrl(id) ?: return null
        val request = okhttp3.Request.Builder().url(url)
            .header("User-Agent", USER_AGENT).header("Accept", "text/html")
            .apply { headers?.get("Cookie")?.let { header("Cookie", it) } }.build()
        val html = kotlinx.coroutines.withTimeoutOrNull(6_000) { catalogueRequest { execute(request) } }
        return html?.let(::initialCatalogueData)?.let(parse)
    }

    private data class OpenedPlaylist(val album: OnlineAlbum, val tracks: List<Track>, val next: PlaylistPage?)

    private suspend fun playlistFromQueue(summary: OnlineAlbum, headers: Map<String, String>?): OpenedPlaylist? {
        val id = browseId(summary.id).takeIf { it.startsWith("VL") }?.removePrefix("VL") ?: return null
        val json = catalogueRequest { post("next", queueFields(id), headers = headers) } ?: return null
        val page = confirmedPlaylistQueue(id, json) ?: return null
        if (page.tracks.isEmpty()) return null
        val next = page.continuation?.let { MusicPage(it, headers != null, watchPlaylist = id, expectedTracks = page.total) }
        val complete = next == null && page.total == page.tracks.size
        return OpenedPlaylist(summary.copy(partial = !complete), page.tracks, next)
    }

    /** Public YouTube can serve the actual playlist when Music only exposes a preview queue. */
    private suspend fun publicPlaylist(summary: OnlineAlbum): OpenedPlaylist? {
        val id = browseId(summary.id).removePrefix("VL")
        if (transport != null || !catalogueId.matches(id) || !(id.startsWith("PL") || id.startsWith("OLAK"))) return null
        val url = "https://www.youtube.com/playlist?list=$id"
        val info = extractPlaylist { PlaylistInfo.getInfo(ServiceList.YouTube, url) } ?: return null
        val songs = publicPlaylistTracks(info.relatedItems)
        if (songs.isEmpty() || info.id != id) return null
        val next = info.nextPage?.takeIf(Page::isValid)?.let { PublicPage(url, it) }
        return OpenedPlaylist(summary.copy(partial = next != null), songs, next)
    }

    private suspend fun <T> extractPlaylist(fetch: () -> T): T? = try {
        kotlinx.coroutines.runInterruptible {
            init()
            fetch()
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: ExtractionException) {
        null
    }

    private fun publicPlaylistTracks(items: List<StreamInfoItem>): List<Track> = items.mapNotNull { item ->
        val id = item.url.toHttpUrlOrNull()?.queryParameter("v")?.takeIf { youtubeVideoId.matches(it) } ?: return@mapNotNull null
        Track(TrackId("yt:$id"), "ytm://$id", item.name, item.uploaderName.orEmpty(),
            durationMs = item.duration.coerceAtLeast(0) * 1000, artworkUri = item.thumbnails.lastOrNull()?.url)
    }

    private suspend fun <T> catalogueRequest(call: suspend () -> T?): T? = try {
        call()
    } catch (_: java.io.IOException) {
        null
    }

    private suspend fun <T> networkFallback(saved: T?, fetch: suspend () -> T?): T? = try {
        fetch() ?: saved
    } catch (failure: java.io.IOException) {
        saved ?: throw failure
    }

    private fun saveAlbum(scope: String, id: String, value: Pair<OnlineAlbum, List<Track>>) {
        val key = "album:$id"
        val previousCount = if (more["$scope/$id"] != null) {
            pageStore?.read(scope, key)?.optJSONArray("songs")?.length() ?: 0
        } else 0
        if (more["$scope/$id"] == null || value.second.size >= previousCount) {
            pageStore?.write(scope, key, org.json.JSONObject().put("album", OnlinePageJson.album(value.first))
                .put("songs", OnlinePageJson.tracks(value.second)))
        }
        rememberAlbumLinks(scope, value.second)
    }

    /** Follow artist shelf links and continuation pages, retaining the initial shelf on failure. */
    private suspend fun expandArtist(
        initial: OnlineArtist, json: String, scope: String, headers: Map<String, String>?, onPage: (OnlineArtist) -> Unit,
    ): OnlineArtist {
        var result = initial
        for (section in artistSections(json)) {
            if (pageScope() != scope) return initial
            val args = "\"browseId\":${quote(section.id)}" + (section.params?.let { ",\"params\":${quote(it)}" } ?: "")
            var page = catalogueRequest { post("browse", args, headers = headers) } ?: continue
            val visited = HashSet<String>()
            while (pageScope() == scope) {
                result = when (section.kind) {
                    "songs" -> result.copy(songs = (result.songs + parseSearch(page, initial.name, keepOrder = true)).distinctBy { it.id })
                    "albums" -> result.copy(albums = (result.albums + artistReleaseCards(page)).distinctBy { it.id })
                    else -> result.copy(singles = (result.singles + artistReleaseCards(page)).distinctBy { it.id })
                }
                onPage(result)
                val token = continuationOf(page) ?: break
                if (!visited.add(token)) break
                page = catalogueRequest { post("browse", "\"continuation\":${quote(token)}", headers = headers)
                    ?: post("browse", "", "&ctoken=${Uri.encode(token)}&continuation=${Uri.encode(token)}&type=next", headers = headers) } ?: break
            }
        }
        return result
    }

    private fun rememberAlbumLinks(scope: String, tracks: List<Track>) {
        val links = tracks.mapNotNull { track -> albumOf(track)?.let { track.id.value to it } }.toMap()
        if (links.isNotEmpty()) pageStore?.mergeLinks(scope, links)
    }

    suspend fun savedAlbumId(mediaId: String): String? = withContext(ioDispatcher) {
        val scope = pageScope()
        albumIds[mediaId.removePrefix("yt:")]?.takeIf { it.startsWith("MPRE") }
            ?: pageStore?.read(scope, "song-albums")?.optString(mediaId)?.takeIf { it.startsWith("MPRE") && pageScope() == scope }
    }


    /**
     * The next part of a long playlist (YouTube Music answers about a hundred songs at a time): the
     * whole list so far, or null when it has no more. Kept in the session cache with the rest.
     */
    suspend fun albumMore(id: String): Pair<OnlineAlbum, List<Track>>? = withContext(ioDispatcher) {
        val scope = pageScope()
        shared("album-more:$scope/$id") {
            val cursor = more["$scope/$id"] ?: return@shared null
            val have = peek<Pair<OnlineAlbum, List<Track>>>("$scope/album:$id") ?: return@shared null
            val source = cursor.page
            val (tracks, next) = when (source) {
                is MusicPage -> musicPlaylistMore(source, have.second.firstOrNull()?.artist)
                is PublicPage -> extractPlaylist { PlaylistInfo.getMoreItems(ServiceList.YouTube, source.url, source.page) }?.let {
                    val items = publicPlaylistTracks(it.items)
                    if (items.isEmpty() && it.errors.isNotEmpty()) null
                    else items to it.nextPage?.takeIf(Page::isValid)?.let { page -> PublicPage(source.url, page) }
                }
            } ?: return@shared null
            val added = tracks.map {
                it.copy(album = it.album ?: have.first.title.takeIf { id.startsWith("MPRE") }, artworkUri = it.artworkUri ?: have.first.cover)
            }
            val visited = cursor.visited + cursor.page.key
            check(next == null || next.key !in visited) { "YouTube Music repeated a playlist page" }
            if (pageScope() != scope) return@shared null
            rememberContinuation("$scope/$id", next, visited)
            // Repeated video ids are distinct playlist occurrences, including across page boundaries.
            val all = have.second + added
            val short = (source as? MusicPage)?.expectedTracks?.let { all.size < it } == true
            (have.first.copy(partial = next != null || short) to all).also {
                if (next != null || !short) keep("$scope/album:$id", it) else synchronized(pages) { pages.remove("$scope/album:$id") }
                saveAlbum(scope, id, it)
            }
        }
    }

    private suspend fun musicPlaylistMore(source: MusicPage, fallbackArtist: String?): Pair<List<Track>, PlaylistPage?>? {
        val headers = if (source.signedIn) account() ?: return null else null
        val watch = source.watchPlaylist
        val endpoint = if (watch == null) "browse" else "next"
        val fields = if (watch == null) "" else queueFields(watch)
        fun parse(json: String): RadioPage? = if (watch == null) parseBrowseContinuation(json, fallbackArtist) else parseQueueContinuation(watch, json)
        val body = listOf(fields, """"continuation":${quote(source.token)}"""").filter(String::isNotEmpty).joinToString(",")
        val page = post(endpoint, body, headers = headers)?.let(::parse) ?: run {
            val token = java.net.URLEncoder.encode(source.token, Charsets.UTF_8.name())
            val legacy = "&ctoken=$token&continuation=$token" + if (watch == null) "&type=next" else ""
            post(endpoint, fields, legacy, headers = headers)?.let(::parse)
        } ?: return null
        return page.tracks to page.continuation?.let { source.copy(token = it) }
    }

    private sealed interface PlaylistPage { val key: String }
    private data class MusicPage(
        val token: String, val signedIn: Boolean, val watchPlaylist: String? = null, val expectedTracks: Int? = null,
    ) : PlaylistPage {
        override val key: String get() = token
    }
    private data class PublicPage(val url: String, val page: Page) : PlaylistPage {
        override val key: String get() = "${page.url}/${page.id}/${page.body?.contentHashCode()}"
    }
    private data class PlaylistCursor(val page: PlaylistPage, val visited: Set<String>)
    private val more = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, PlaylistCursor>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaylistCursor>?) = size > 48
    })
    internal fun hasAlbumContinuation(id: String): Boolean = more.containsKey("${pageScope()}/$id")

    private fun rememberContinuation(id: String, page: PlaylistPage?, visited: Set<String> = emptySet()) {
        if (page == null) more.remove(id) else more[id] = PlaylistCursor(page, visited)
    }

    /** A page already loaded this session, for drawing it in the first frame when it opens again. */
    @Suppress("UNCHECKED_CAST")
    fun <T> peek(key: String): T? = synchronized(pages) { pages[key] } as T?
    fun peekAlbum(id: String): Pair<OnlineAlbum, List<Track>>? = peek("${pageScope()}/album:$id") ?: peek("album:$id")
    fun peekArtist(id: String): OnlineArtist? = peek("${pageScope()}/artist:$id") ?: peek("artist:$id")
    /** Puts a page in the session cache, as if it had been loaded (screenshots and tests). */
    internal fun keep(key: String, value: Any) { synchronized(pages) { pages[key] = value } }

    // Pages opened this session (an artist, an album), newest kept: going back and forth between a
    // playlist and its artist costs no network and no wait. Forgotten with the process.
    private val pages = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 48
    }
    private suspend fun <T : Any> cached(key: String, keepResult: (T) -> Boolean = { true }, load: suspend () -> T?): T? = withContext(ioDispatcher) {
        peek<T>(key) ?: shared(key) {
            // Publish before releasing waiters, so a late initial-page waiter cannot overwrite a
            // newer continuation snapshot and another caller cannot start the same load in the gap.
            peek<T>(key) ?: load()?.also { if (keepResult(it)) keep(key, it) }
        }
    }

    // Requests for the same page in flight at once share one network call (two screens opening the
    // same album, a prefetch and a tap).
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Any?>>()
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> shared(key: String, load: suspend () -> T?): T? = kotlinx.coroutines.coroutineScope {
        val mine = kotlinx.coroutines.CompletableDeferred<Any?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return@coroutineScope running.await() as T?
        try { (load()).also { mine.complete(it) } }
        catch (e: Throwable) { mine.completeExceptionally(e); throw e }
        finally { inFlight.remove(key, mine) }
    }

    /** The HTTP status of the last refused answer (429 too many requests, 401/403 signed out), for honest errors. */
    @Volatile var lastStatus: Int = 0; private set

    /**
     * Likes or unlikes a song on the owner's YouTube Music account, so a heart in the app is a like
     * there too (and the other way it shows in his "Liked music"). Only when signed in; quiet on failure.
     */
    suspend fun like(videoId: String, liked: Boolean, scope: String): Boolean = withContext(ioDispatcher) {
        if (pageScope() != scope) return@withContext false
        val headers = account() ?: return@withContext false
        if (pageScope() != scope) return@withContext false
        try {
            post(if (liked) "like/like" else "like/removelike", """"target":{"videoId":${quote(videoId)}}"""", headers = headers) != null
        } catch (_: java.io.IOException) {
            false
        }
    }

    /**
     * The owner's own playlists on YouTube Music (Liked music first, as there), when signed in; empty
     * otherwise. Kept for the session.
     */
    suspend fun accountPlaylists(offline: Boolean = false): List<Card> = withContext(ioDispatcher) {
        val scope = pageScope()
        val headers = account() ?: return@withContext emptyList()
        val cookie = headers["Cookie"] ?: return@withContext emptyList()
        if (pageScope() != scope) return@withContext emptyList()
        val key = "account-playlists"
        val saved = pageStore?.read(scope, key)?.let { runCatching { OnlinePageJson.cards(it, "playlists") }.getOrNull() }
        if (offline) return@withContext saved.orEmpty()
        cached("account-playlists:${OnlinePageStore.digest(cookie)}") {
            val fresh = networkFallback(saved) {
                var json = post("browse", """"browseId":"FEmusic_liked_playlists"""", headers = headers) ?: return@networkFallback null
                check(ua.olex.music.lyrics.Json.parse(json) is Map<*, *>) { "Invalid YouTube Music playlist page" }
                val cards = LinkedHashMap<String, Card>()
                val visited = HashSet<String>()
                while (true) {
                    playlistCards(json).forEach { cards.putIfAbsent(it.id, it) }
                    val token = continuationOf(json) ?: break
                    check(visited.add(token)) { "YouTube Music repeated an account playlist page" }
                    json = post("browse", """"continuation":${quote(token)}"""", headers = headers)
                        ?: throw java.io.IOException("Couldn't load the rest of your YouTube Music playlists")
                    check(ua.olex.music.lyrics.Json.parse(json) is Map<*, *>) { "Invalid YouTube Music playlist page" }
                }
                cards.values.toList()
            }
            if (pageScope() != scope) return@cached null
            if (fresh != null && fresh !== saved) pageStore?.write(scope, key, org.json.JSONObject().put("playlists", OnlinePageJson.cards(fresh)))
            fresh
        }.orEmpty()
    }

    internal data class PlaylistOccurrence(val videoId: String, val entryId: String?, val title: String)
    internal data class PlaylistSnapshot(val album: OnlineAlbum, val tracks: List<Track>, val entries: List<PlaylistOccurrence>)
    internal data class PlaylistChange(val kind: Kind, val entry: PlaylistOccurrence? = null, val successor: String? = null, val videoId: String? = null) {
        enum class Kind { ADD, REMOVE, MOVE }
    }

    /** Fetch all pages with one captured session. Mutations never operate on cached order. */
    private suspend fun playlistSnapshot(id: String, headers: Map<String, String>): PlaylistSnapshot {
        val first = post("browse", """"browseId":${quote(id)}"""", headers = headers)
            ?: throw java.io.IOException("Couldn't open the YouTube Music playlist.")
        val album = parseAlbum(id, first)?.first ?: throw java.io.IOException("Couldn't read the YouTube Music playlist.")
        val tracks = ArrayList<Track>()
        val entries = ArrayList<PlaylistOccurrence>()
        val cursors = HashSet<String>()
        var json = first
        while (true) {
            val root = ua.olex.music.lyrics.Json.parse(json) as? Map<*, *> ?: throw java.io.IOException("Invalid playlist page.")
            tracks += parseSearch(json, keepOrder = true)
            collect(root, "musicResponsiveListItemRenderer").forEach { row ->
                val video = (row["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String ?: return@forEach
                val entry = collect(row["menu"], "playlistEditEndpoint").firstNotNullOfOrNull { endpoint ->
                    (endpoint["actions"] as? List<*>)?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("setVideoId") as? String }
                }
                val title = text(row["flexColumns"]).ifBlank { video }
                entries += PlaylistOccurrence(video, entry, title)
            }
            val token = continuationOf(json) ?: break
            check(cursors.size < 100 && cursors.add(token)) { "YouTube Music returned an incomplete or repeated playlist." }
            json = post("browse", """"continuation":${quote(token)}"""", headers = headers)
                ?: throw java.io.IOException("Couldn't open the rest of the playlist.")
        }
        val names = tracks.associateBy { it.id.value.removePrefix("yt:") }
        return PlaylistSnapshot(album, tracks, entries.map { it.copy(title = names[it.videoId]?.title ?: it.title) })
    }

    suspend fun likedPlaylist(scope: String): PlaylistSnapshot = remotePlaylist("VLLM", scope)

    suspend fun remotePlaylist(id: String, scope: String): PlaylistSnapshot = withContext(ioDispatcher) {
        val headers = sessionHeaders(scope)
        playlistSnapshot(id, headers).also { check(pageScope() == scope) { "The YouTube Music account changed." } }
    }

    suspend fun editRemotePlaylist(
        id: String, scope: String, expected: List<PlaylistOccurrence>, change: PlaylistChange,
    ): PlaylistSnapshot = withContext(ioDispatcher) {
        val headers = sessionHeaders(scope)
        val before = playlistSnapshot(id, headers)
        check(pageScope() == scope) { "The YouTube Music account changed." }
        val current = before.entries
        val entry = change.entry
        val conflict = "The same playlist entry changed on YouTube Music. Refresh it and try again."
        if (change.kind != PlaylistChange.Kind.ADD) {
            val requested = requireNotNull(entry)
            check(requested in expected) { conflict }
            fun List<PlaylistOccurrence>.indexOfEntry(key: String) = indexOfFirst { it.entryId == key }
            val key = requireNotNull(requested.entryId)
            val actual = current.getOrNull(current.indexOfEntry(key))
            if (actual == null && change.kind == PlaylistChange.Kind.REMOVE) {
                check(current.none { it.videoId == requested.videoId && it.entryId == null }) { conflict }
                rememberPlaylistSnapshot(id, scope, before)
                return@withContext before
            }
            check(actual?.videoId == requested.videoId) { conflict }
            if (change.kind == PlaylistChange.Kind.MOVE) {
                val successor = change.successor
                check(successor == null || current.indexOfEntry(successor) >= 0) { conflict }
                val known = expected.mapNotNull { it.entryId }.toSet()
                val nowKnown = current.mapNotNull { it.entryId }.filter { it in known }
                val previousKnown = expected.mapNotNull { it.entryId }.filter { it in nowKnown }
                val at = nowKnown.indexOf(key)
                val was = previousKnown.indexOf(key)
                val alreadyMoved = current.getOrNull(current.indexOfEntry(key) + 1)?.entryId == successor &&
                    (successor != null || current.lastOrNull()?.entryId == key)
                if (alreadyMoved) {
                    rememberPlaylistSnapshot(id, scope, before)
                    return@withContext before
                }
                check(at > -1 && was > -1 && nowKnown.getOrNull(at - 1) == previousKnown.getOrNull(was - 1) &&
                    nowKnown.getOrNull(at + 1) == previousKnown.getOrNull(was + 1)) { conflict }
            }
        }
        val action = org.json.JSONObject()
        when (change.kind) {
            PlaylistChange.Kind.ADD -> action.put("action", "ACTION_ADD_VIDEO").put("addedVideoId", requireNotNull(change.videoId))
            PlaylistChange.Kind.REMOVE -> {
                val entry = requireNotNull(change.entry)
                check(entry in expected)
                action.put("action", "ACTION_REMOVE_VIDEO").put("removedVideoId", entry.videoId).put("setVideoId", requireNotNull(entry.entryId))
            }
            PlaylistChange.Kind.MOVE -> {
                val entry = requireNotNull(change.entry)
                check(entry in expected)
                check(change.successor == null || expected.any { it.entryId == change.successor })
                action.put("action", "ACTION_MOVE_VIDEO_BEFORE").put("setVideoId", requireNotNull(entry.entryId))
                change.successor?.let { action.put("movedSetVideoIdSuccessor", it) }
            }
        }
        val fields = """"playlistId":${quote(id.removePrefix("VL"))},"actions":[${action}]"""
        val response = post("browse/edit_playlist", fields, headers = headers)
        val status = response?.let { ua.olex.music.lyrics.Json.parse(it) as? Map<*, *> }?.get("status")
        check(status == "STATUS_SUCCEEDED") { "YouTube Music did not confirm the edit. Refresh before retrying." }
        synchronized(pages) { pages.remove("$scope/album:$id"); pages.remove("$scope/offline/album:$id") }
        more.remove("$scope/$id")
        val updated = playlistSnapshot(id, headers)
        check(pageScope() == scope) { "The YouTube Music account changed." }
        rememberPlaylistSnapshot(id, scope, updated)
        updated
    }

    private fun rememberPlaylistSnapshot(id: String, scope: String, snapshot: PlaylistSnapshot) {
        synchronized(pages) { pages.remove("$scope/offline/album:$id") }
        more.remove("$scope/$id")
        val result = snapshot.album to snapshot.tracks
        keep("$scope/album:$id", result)
        saveAlbum(scope, id, result)
    }

    private fun sessionHeaders(scope: String): Map<String, String> {
        check(scope != "public" && pageScope() == scope) { "Sign in to YouTube Music first." }
        val headers = account() ?: error("Sign in to YouTube Music first.")
        check(pageScope() == scope) { "The YouTube Music account changed." }
        return headers
    }

    /** Forgets everything loaded for an account (its playlists, its own home): called on sign-in and sign-out. */
    fun forgetAccount() {
        synchronized(pages) { pages.keys.filter { it.startsWith("account-") || it.startsWith("mood:") }.forEach { pages.remove(it) } }
        moodsCache = null
    }

    /** Resolves the stream of a song ahead of a tap (address only, no audio), so Play starts sooner. */
    suspend fun warm(id: String) { withContext(ioDispatcher) { audio(id) } }

    /** A page of YouTube Music's radio for a song, and the token for the page after it. */
    data class RadioPage(val tracks: List<Track>, val continuation: String?)

    /**
     * YouTube Music's own radio for a song ("Start radio"): the songs its recommender lines up,
     * in its order, which keeps tempo and mood flowing from one song to the next.
     */
    suspend fun radio(videoId: String): RadioPage? = withContext(ioDispatcher) {
        post("next", """"videoId":${quote(videoId)},"playlistId":${quote("RDAMVM$videoId")},"params":"wAEB","isAudioOnly":true""")?.let(::parseRadio)
    }

    /** The radio's next page. */
    suspend fun radioMore(continuation: String): RadioPage? = withContext(ioDispatcher) {
        post("next", """"continuation":${quote(continuation)}""")?.let(::parseRadio)
    }

    /** The song on YouTube Music for a file in the library: the best match of a search for artist and title. */
    suspend fun find(track: Track): Track? = search("${track.artist} ${track.title}").let { found ->
        found.firstOrNull { sameSong(it, track) }
    }

    /** A mood of YouTube Music's home ("Energize", "Sad", "Sleep"): its label and the filter that opens it. */
    data class Mood(val label: String, val params: String?)
    /** What a mood offers: curated playlists, and songs when the page lists any. */
    data class MoodPage(val playlists: List<OnlineAlbum>, val songs: List<Track>)

    @Volatile private var moodsCache: Pair<String, List<Mood>>? = null

    /** The moods as YouTube Music's home lists them; the usual set (without filters) when it can't be reached. */
    suspend fun moods(): List<Mood> = withContext(ioDispatcher) {
        val scope = pageScope()
        moodsCache?.takeIf { it.first == scope }?.let { return@withContext it.second }
        val found = runCatching { homeAnswer(scope)?.let(::parseMoods) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext DEFAULT_MOODS
        if (pageScope() != scope) return@withContext DEFAULT_MOODS
        moodsCache = scope to found
        storeMoods(scope, found)
        found
    }

    // Home and its mood filters share one response per account.
    private val homeLock = kotlinx.coroutines.sync.Mutex()
    private data class HomeResponse(val scope: String, val time: Long, val json: String)
    private var homeAt: HomeResponse? = null
    private suspend fun homeAnswer(scope: String): String? = homeLock.withLock {
        if (pageScope() != scope) return@withLock null
        val now = android.os.SystemClock.elapsedRealtime()
        homeAt?.takeIf { it.scope == scope && now - it.time < 60_000 }?.json
            ?: post("browse", """"browseId":"FEmusic_home"""")?.takeIf { pageScope() == scope }?.also { homeAt = HomeResponse(scope, now, it) }
    }

    /** Home's shelves as last seen for this account: drawn at once on the next start, then refreshed. */
    fun storedHome(): List<Shelf> = runCatching {
        val page = pageStore?.read(pageScope(), "home") ?: return emptyList()
        val shelves = page.getJSONArray("shelves")
        (0 until shelves.length()).map { i -> shelves.getJSONObject(i).let { s ->
            Shelf(s.getString("title"), OnlinePageJson.tracks(s, "songs"), OnlinePageJson.cards(s, "cards")) } }
    }.getOrDefault(emptyList())

    /** The moods as last seen: the chips are right from the first frame. */
    fun storedMoods(): List<Mood>? = runCatching {
        val list = pageStore?.read(pageScope(), "moods")?.getJSONArray("moods") ?: return null
        (0 until list.length()).map { i -> list.getJSONObject(i).let { Mood(it.getString("label"), it.optString("params").ifEmpty { null }) } }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun storeHome(scope: String, shelves: List<Shelf>) = runCatching {
        val array = org.json.JSONArray()
        shelves.forEach { array.put(org.json.JSONObject().put("title", it.title).put("songs", OnlinePageJson.tracks(it.songs)).put("cards", OnlinePageJson.cards(it.cards))) }
        pageStore?.write(scope, "home", org.json.JSONObject().put("shelves", array))
    }
    private fun storeMoods(scope: String, moods: List<Mood>) = runCatching {
        val array = org.json.JSONArray()
        moods.forEach { array.put(org.json.JSONObject().put("label", it.label).put("params", it.params ?: "")) }
        pageStore?.write(scope, "moods", org.json.JSONObject().put("moods", array))
    }

    /** One mood's page: YouTube Music's home filtered by it. */
    suspend fun mood(mood: Mood): MoodPage? {
        val params = mood.params ?: return null
        val scope = pageScope()
        return cached("$scope/mood:$params") {
            val page = post("browse", """"browseId":"FEmusic_home","params":${quote(params)}""")?.let(::parseMoodPage) ?: return@cached null
            if (pageScope() != scope || (page.playlists.isEmpty() && page.songs.isEmpty())) return@cached null
            if (page.songs.isNotEmpty()) page else {
                val first = page.playlists.first()
                page.copy(songs = networkFallback(emptyList()) { album(first.id)?.second }.orEmpty().take(24))
            }
        }
    }
    fun peekMood(mood: Mood): MoodPage? = peek("${pageScope()}/mood:${mood.params}")

    /** A home shelf: its title, and its songs or its cards. */
    data class Shelf(val title: String, val songs: List<Track>, val cards: List<Card>)
    /** A card on a shelf; a mix ([kind] MIX) plays as radio from its playlist id. */
    data class Card(val id: String, val title: String, val subtitle: String?, val cover: String?, val kind: Kind) {
        enum class Kind { ALBUM, PLAYLIST, ARTIST, MIX, SONG }
        fun song() = Track(ua.olex.music.model.TrackId("yt:$id"), "ytm://$id", title, artistOf(subtitle), artworkUri = cover)
    }

    /** YouTube Music's home: the owner's own when signed in (quick picks, mixes, listen again...). */
    suspend fun home(): List<Shelf> = withContext(ioDispatcher) {
        val scope = pageScope()
        val shelves = homeAnswer(scope)?.let(::parseHome).orEmpty()
        if (pageScope() != scope || shelves.isEmpty()) return@withContext emptyList()
        // Some accounts get only mix cards. Opening Home should still offer individual songs.
        val hasSongs = shelves.any { shelf -> shelf.songs.isNotEmpty() || shelf.cards.any { it.kind == Card.Kind.SONG } }
        val mix = if (hasSongs) null else shelves.firstNotNullOfOrNull { shelf -> shelf.cards.firstOrNull { it.kind == Card.Kind.MIX } }
        val tracks = mix?.let { networkFallback(emptyList()) { playlistRadio(it.id)?.tracks } }.orEmpty().take(24)
        val result = if (tracks.isEmpty()) shelves else listOf(Shelf(mix!!.title, tracks, emptyList())) + shelves
        if (pageScope() != scope) return@withContext emptyList()
        storeHome(scope, result)
        result
    }

    /** A mix or other watch playlist, played as YouTube Music plays it ("My Mix 1"). */
    suspend fun playlistRadio(playlistId: String): RadioPage? = withContext(ioDispatcher) {
        post("next", """"playlistId":${quote(playlistId)},"params":"wAEB","isAudioOnly":true""")?.let(::parseRadio)
    }

    internal data class WebClient(val version: String, val apiKey: String? = null, val visitor: String? = null)
    private val webClientMutex = kotlinx.coroutines.sync.Mutex()
    private var webClient: WebClient? = null
    private var webClientUntil = 0L

    /** Bootstrap the public web client once for several concurrent playlist and catalogue loads. */
    private suspend fun webClient(): WebClient = webClientMutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        webClient?.takeIf { now < webClientUntil }?.let { return@withLock it }
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/")
            .header("User-Agent", USER_AGENT).build()
        val fetched = kotlinx.coroutines.withTimeoutOrNull(4_000) {
            try { execute(request, reportStatus = false)?.let(::parseWebClient) }
            catch (_: java.io.IOException) { null }
        }
        val client = fetched ?: webClient ?: WebClient("1.${java.time.LocalDate.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.01.00")
        webClient = client
        webClientUntil = now + if (fetched == null) 5 * 60_000 else 60 * 60_000
        client
    }

    /** One call to YouTube Music, as its web app makes it. */
    private suspend fun post(endpoint: String, fields: String, query: String = "", headers: Map<String, String>? = account()): String? {
        transport?.let { return it(endpoint, fields, query) }
        val web = webClient()
        val visitor = web.visitor?.let { ""","visitorData":${quote(it)}""" }.orEmpty()
        val body = """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"${web.version}","hl":"en","gl":"${java.util.Locale.getDefault().country.ifEmpty { "US" }}"$visitor},"user":{}}${if (fields.isEmpty()) "" else ",$fields"}}"""
        val key = web.apiKey?.let { "&key=${Uri.encode(it)}" }.orEmpty()
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?alt=json&prettyPrint=false$key$query")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("User-Agent", USER_AGENT).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", web.version)
            .apply {
                web.visitor?.let { header("X-Goog-Visitor-Id", it) }
                headers?.forEach { (k, v) -> header(k, v) }
            }.build()
        return execute(request)
    }

    private suspend fun execute(request: okhttp3.Request, reportStatus: Boolean = true): String? {
        // Cancelled with the coroutine that asked (a search replaced by the next keystroke stops its
        // request instead of finishing in the background and holding a connection).
        val call = musicHttp.newCall(request)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.use { r -> if (r.isSuccessful) runCatching { r.body?.string() }.getOrNull() else { if (reportStatus) lastStatus = r.code; null } }
                    if (cont.isActive) cont.resumeWith(Result.success(body))
                }
            })
        }
    }

    /** The audio stream of a song: the best Opus or AAC stream, kept for four hours (they expire). */
    fun audio(id: String): Uri? = runCatching {
        init()
        synchronized(streams) { streams[id]?.takeIf { System.currentTimeMillis() - it.second < 4 * 3_600_000L }?.first }?.let { return it }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$id")
        val best = info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate } ?: return null
        Uri.parse(best.content).also { synchronized(streams) { streams[id] = it to System.currentTimeMillis() } }
    }.getOrNull()

    // Bounded: a long session through many playlists keeps only the recent ones.
    private val streams = object : LinkedHashMap<String, Pair<Uri, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Uri, Long>>?) = size > 300
    }
    /** Forgets a stream address that stopped working, so the next open finds a fresh one. */
    fun forget(id: String) { synchronized(streams) { streams.remove(id) } }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        private val catalogueId = Regex("[A-Za-z0-9_-]{2,128}")
        private val youtubeVideoId = Regex("[A-Za-z0-9_-]{11}")
        private val initialDataAssignment = Regex("""(?:\bytInitialData|window\[["']ytInitialData["']\])\s*=\s*(\{)""")

        private fun queueFields(id: String) =
            """"playlistId":${quote(id)},"isAudioOnly":true,"enablePersistentPlaylistPanel":true,"tunerSettingValue":"AUTOMIX_SETTING_NORMAL""""

        private fun browseId(id: String): String = when {
            id.startsWith("PL") || id.startsWith("OLAK") || id.startsWith("RD") || id in setOf("LM", "SE") -> "VL$id"
            else -> id
        }

        fun catalogueUrl(id: String): okhttp3.HttpUrl? {
            if (!catalogueId.matches(id)) return null
            val normalized = browseId(id)
            val url = YouTubeAccount.ORIGIN.toHttpUrl().newBuilder()
            when {
                normalized.startsWith("VL") && normalized.length > 2 ->
                    url.addPathSegment("playlist").addQueryParameter("list", normalized.removePrefix("VL"))
                normalized.startsWith("UC") -> url.addPathSegment("channel").addPathSegment(normalized)
                normalized.startsWith("MPRE") -> url.addPathSegment("browse").addPathSegment(normalized)
                else -> return null
            }
            return url.build()
        }

        internal fun initialCatalogueData(html: String): String? {
            for (match in initialDataAssignment.findAll(html)) {
                val start = match.groups[1]?.range?.first ?: continue
                var depth = 0
                var string = false
                var escaped = false
                for (index in start until html.length) {
                    val char = html[index]
                    if (string) {
                        when {
                            escaped -> escaped = false
                            char == '\\' -> escaped = true
                            char == '"' -> string = false
                        }
                    } else when (char) {
                        '"' -> string = true
                        '{' -> depth++
                        '}' -> if (--depth == 0) {
                            val data = html.substring(start, index + 1)
                            val root = ua.olex.music.lyrics.Json.parse(data) as? Map<*, *>
                            if (root?.containsKey("contents") == true || root?.containsKey("header") == true) return data
                            break
                        }
                    }
                }
            }
            return null
        }

        internal fun parseWebClient(html: String): WebClient? {
            fun field(name: String) = Regex(""""$name"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            val version = sequenceOf("INNERTUBE_CONTEXT_CLIENT_VERSION", "INNERTUBE_CLIENT_VERSION")
                .mapNotNull(::field).firstOrNull { it.matches(Regex("""1\.\d{8}\.\d{2}\.\d{2}""")) } ?: return null
            val key = field("INNERTUBE_API_KEY")?.takeIf { it.matches(Regex("""[A-Za-z0-9_-]{20,80}""")) }
            val visitor = field("VISITOR_DATA")?.takeIf { it.matches(Regex("""[A-Za-z0-9_%=-]{8,256}""")) }
            return WebClient(version, key, visitor)
        }


        fun isOnline(track: Track) = track.id.value.startsWith("yt:")

        // Album page ids of the songs seen this session, by video id (songs carry only the album's name).
        private val albumIds = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 5_000
        })

        /** The YouTube Music album page of an online song seen this session, if its row named one. */
        fun albumOf(track: Track): String? = albumIds[track.id.value.removePrefix("yt:")]

        internal fun rememberAlbum(video: String, album: String) { albumIds[video] = album }

        private val artistIdsByVideo = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, List<Pair<String, String>>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, String>>>?) = size > 5_000
        })

        fun artistOfTrack(track: Track): Pair<String, String>? = if (isOnline(track)) artistIdsByVideo[track.id.value.removePrefix("yt:")]?.firstOrNull() else null

        fun artistCredits(track: Track): List<String> =
            if (track.artists.size > 1) track.artists else track.artist.split(",").map(String::trim)

        private fun recordCatalogueLinks(video: String, runs: List<Map<*, *>>) {
            val credits = ArrayList<Pair<String, String>>()
            for (run in runs) {
                val endpoint = (run["navigationEndpoint"] as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *> ?: continue
                val id = endpoint["browseId"] as? String ?: continue
                val type = dump(endpoint)
                if (id.startsWith("MPRE") && type.contains("MUSIC_PAGE_TYPE_ALBUM")) rememberAlbum(video, id)
                if (id.startsWith("UC") && (type.contains("MUSIC_PAGE_TYPE_ARTIST") || type.contains("MUSIC_PAGE_TYPE_USER_CHANNEL"))) {
                    val name = text(run["text"])
                    if (name.isNotBlank()) credits += id to name
                }
            }
            if (credits.isNotEmpty()) artistIdsByVideo[video] = credits.distinctBy { it.first }
        }

        /** Playlist cards anywhere in an answer (a library grid, a shelf), in order, without the "New playlist" tile. */
        fun playlistCards(json: String): List<Card> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            return collect(root, "musicTwoRowItemRenderer").mapNotNull { card ->
                val nav = dump(card["navigationEndpoint"])
                if (!nav.contains("MUSIC_PAGE_TYPE_PLAYLIST")) return@mapNotNull null
                val id = Regex(""""browseId"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1) ?: return@mapNotNull null
                Card(id, text(card["title"]).ifEmpty { return@mapNotNull null }, text(card["subtitle"]).ifEmpty { null }, biggestImage(card["thumbnailRenderer"]), Card.Kind.PLAYLIST)
            }.distinctBy { it.id }
        }

        /** The token for the rest of a long list, in either form YouTube Music sends it. */
        fun continuationOf(json: String): String? =
            Regex(""""continuationCommand"\s*:\s*\{[^{}]*?"token"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: Regex(""""nextContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

        private fun pageToken(contents: Any?, continuation: Any? = null): String? =
            collect(contents, "continuationItemRenderer").firstNotNullOfOrNull { item ->
                collect(item["continuationEndpoint"], "continuationCommand").firstNotNullOfOrNull { it["token"] as? String }
            } ?: listOf("nextContinuationData", "nextRadioContinuationData").firstNotNullOfOrNull { kind ->
                collect(continuation, kind).firstNotNullOfOrNull { it["continuation"] as? String }
            }

        private fun playlistBrowseToken(json: String): String? {
            val root = ua.olex.music.lyrics.Json.parse(json) as? Map<*, *> ?: return null
            val shelves = collect(root, "musicPlaylistShelfRenderer").ifEmpty { collect(root, "musicShelfRenderer") }
            return if (shelves.isEmpty()) pageToken(root["contents"], root["continuations"])
                else shelves.firstNotNullOfOrNull { pageToken(it["contents"], it["continuations"]) }
        }

        private fun parseBrowseContinuation(json: String, fallbackArtist: String?): RadioPage? {
            val root = ua.olex.music.lyrics.Json.parse(json) as? Map<*, *> ?: return null
            val shelf = collect(root, "musicPlaylistShelfContinuation").firstOrNull()
                ?: collect(root, "musicShelfContinuation").firstOrNull()
            val appended = collect(root, "appendContinuationItemsAction")
                .firstNotNullOfOrNull { action ->
                    (action["continuationItems"] as? List<*>)?.takeIf { collect(it, "musicResponsiveListItemRenderer").isNotEmpty() }
                }
            val contents = (shelf?.get("contents") ?: appended ?: root["contents"]) as? List<*> ?: return null
            val tracks = songRows(collect(contents, "musicResponsiveListItemRenderer"), fallbackArtist, keepOrder = true)
            return RadioPage(tracks, pageToken(contents, shelf?.get("continuations") ?: root["continuations"]))
        }

        /** The search filter YouTube Music's web app sends for "Artists". */
        private const val ARTISTS_ONLY = "EgWKAQIgAWoKEAoQAxAEEAkQBQ%3D%3D"

        /** Artists and albums in a search answer (its rows and its top result card), in its order. */
        fun searchCards(json: String): List<Card> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            val top = collect(root, "musicCardShelfRenderer").mapNotNull { card ->
                card(dump(card["title"]) + dump(card["onTap"]), text(card["title"]), text(card["subtitle"]), biggestImage(card["thumbnail"]))
            }
            val rows = collect(root, "musicResponsiveListItemRenderer").mapNotNull { row ->
                val columns = (row["flexColumns"] as? List<*>).orEmpty().map { text(((it as? Map<*, *>)?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)?.get("text")) }
                card(dump(row["navigationEndpoint"]), columns.firstOrNull().orEmpty(), columns.getOrNull(1).orEmpty(), biggestImage(row["thumbnail"]))
            }
            return (top + rows).distinctBy { it.id }
        }

        private fun card(nav: String, title: String, subtitle: String, cover: String?): Card? {
            if (title.isEmpty()) return null
            val id = Regex(""""browseId"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1) ?: return null
            val kind = when {
                nav.contains("MUSIC_PAGE_TYPE_ARTIST") || nav.contains("MUSIC_PAGE_TYPE_USER_CHANNEL") -> Card.Kind.ARTIST
                nav.contains("MUSIC_PAGE_TYPE_ALBUM") -> Card.Kind.ALBUM
                else -> return null
            }
            // "Artist \u2022 7.76M monthly audience" reads as the audience; "Album \u2022 Air \u2022 1998" as "Air \u2022 1998".
            val sub = subtitle.split(" \u2022 ").drop(1).joinToString(" \u2022 ").replace(" monthly audience", " listeners").replace(" monthly listeners", " listeners").ifEmpty { null }
            return Card(id, title, sub, cover, kind)
        }

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

        private fun text(value: Any?): String = when (value) {
            is String -> value
            is Map<*, *> -> (value["runs"] as? List<*>)?.joinToString("") { (it as? Map<*, *>)?.get("text") as? String ?: "" }
                ?: text(value["simpleText"] ?: value["content"] ?: value["text"] ?: value["dynamicTextViewModel"])
            else -> ""
        }.trim()

        private fun biggestImage(o: Any?): String? = Regex(""""url"\s*:\s*"(https://[^"]+)"""").findAll(dump(o)).map { it.groupValues[1] }.lastOrNull()
            ?.replace(Regex("""=w\d+-h\d+"""), "=w1080-h1080")

        /** Artist ids and names in a search answer (results whose link is an artist page). */
        fun artistIds(json: String): List<Pair<String, String>> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            val cards = searchCards(json).filter { it.kind == Card.Kind.ARTIST }.map { it.id to it.title }
            // Some results carry the artist only on the credit of a song, not as a standalone card.
            val creditLinks = collect(root, "musicResponsiveListItemFlexColumnRenderer").flatMap { column ->
                val runs = (column["text"] as? Map<*, *>)?.get("runs") as? List<*> ?: emptyList<Any>()
                runs.mapNotNull { run ->
                    val fields = run as? Map<*, *> ?: return@mapNotNull null
                    val name = text(fields["text"])
                    val endpoint = collect(fields["navigationEndpoint"], "browseEndpoint").firstOrNull() ?: return@mapNotNull null
                    val id = endpoint["browseId"] as? String ?: return@mapNotNull null
                    val type = dump(endpoint)
                    if (name.isNotBlank() && id.startsWith("UC") &&
                        (type.contains("MUSIC_PAGE_TYPE_ARTIST") || type.contains("MUSIC_PAGE_TYPE_USER_CHANNEL"))) id to name else null
                }
            }
            return (cards + creditLinks).distinctBy { it.first to TextFold.fold(it.second) }
        }

        internal data class ArtistSection(val kind: String, val id: String, val params: String?)

        internal fun artistSections(json: String): List<ArtistSection> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            val shelves = collect(root, "musicCarouselShelfRenderer") + collect(root, "musicShelfRenderer")
            return shelves.mapNotNull { shelf ->
                val header = shelf["header"] ?: shelf["title"] ?: return@mapNotNull null
                val heading = text((header as? Map<*, *>)?.get("musicCarouselShelfBasicHeaderRenderer")?.let { (it as? Map<*, *>)?.get("title") } ?: header).lowercase()
                val kind = when {
                    heading.contains("song") -> "songs"
                    heading.contains("single") || heading.contains("ep") -> "singles"
                    heading.contains("album") -> "albums"
                    else -> return@mapNotNull null
                }
                val endpoints = collect(header, "browseEndpoint")
                val endpoint = endpoints.firstOrNull { (it["browseId"] as? String)?.isNotBlank() == true } ?: return@mapNotNull null
                ArtistSection(kind, endpoint["browseId"] as String, endpoint["params"] as? String)
            }.distinctBy { Triple(it.kind, it.id, it.params) }
        }

        internal fun artistReleaseCards(json: String): List<OnlineAlbum> {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return emptyList()
            return collect(root, "musicTwoRowItemRenderer").mapNotNull { card ->
                val nav = dump(card["navigationEndpoint"])
                if (!nav.contains("MUSIC_PAGE_TYPE_ALBUM")) return@mapNotNull null
                val id = Regex(""""browseId"\s*:\s*"([^"]+)"""").find(nav)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = text(card["title"]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OnlineAlbum(id, title, text(card["subtitle"]).ifEmpty { null }, biggestImage(card["thumbnailRenderer"]))
            }.distinctBy { it.id }
        }

        /** An artist page: header, description, every song row and every album and video card. */
        fun parseArtist(id: String, json: String, knownName: String? = null): OnlineArtist? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val header = listOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer",
                "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicHeaderRenderer", "pageHeaderViewModel")
                .asSequence().flatMap { collect(root, it).asSequence() }.firstOrNull { text(it["title"]).isNotBlank() }
            val hasMusicShelves = collect(root, "musicShelfRenderer").isNotEmpty() ||
                collect(root, "musicCarouselShelfRenderer").isNotEmpty()
            val name = text(header?.get("title")).ifEmpty { knownName?.takeIf { it.isNotBlank() && hasMusicShelves } ?: return null }
            val about = collect(root, "musicDescriptionShelfRenderer").firstOrNull()?.let { text(it["description"]) }?.takeIf { it.isNotBlank() }
            val songs = parseSearch(json, fallbackArtist = name, keepOrder = true).distinctBy { it.id }
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
            // Shelves by their titles: "Singles & EPs" apart from albums; "Fans might also like" for related artists.
            val shelves = parseHome(json)
            val singleIds = shelves.filter { it.title.contains("single", true) }.flatMap { it.cards }.mapTo(HashSet()) { it.id }
            val related = shelves.flatMap { it.cards }.filter { it.kind == Card.Kind.ARTIST && it.id != id }.distinctBy { it.id }
            val listeners = text(header?.get("monthlyListenerCount")).ifEmpty { null }
            return OnlineArtist(id, name, biggestImage(header?.get("thumbnail")), about, songs.filterNot { s -> videos.any { it.id == s.id } },
                albums.filterNot { it.id in singleIds }, videos, albums.filter { it.id in singleIds }, related, listeners)
        }

        /** An album page: its title, artist and cover, then its songs in order. */
        fun parseAlbum(id: String, json: String, summary: OnlineAlbum? = null): Pair<OnlineAlbum, List<Track>>? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicVisualHeaderRenderer",
                "musicEditablePlaylistDetailHeaderRenderer", "playlistHeaderRenderer", "pageHeaderViewModel")
                .asSequence().flatMap { collect(root, it).asSequence() }.firstOrNull { text(it["title"]).isNotBlank() }
            val shelves = collect(root, "musicPlaylistShelfRenderer")
            val rows = if (shelves.isEmpty()) collect(root, "musicResponsiveListItemRenderer")
                else collect(shelves.map { it["contents"] }, "musicResponsiveListItemRenderer")
            val hasPlaylist = rows.isNotEmpty() || shelves.isNotEmpty()
            val title = text(header?.get("title")).ifEmpty { summary?.title?.takeIf { hasPlaylist } ?: return null }
            val subtitle = text(header?.get("subtitle")).ifEmpty { summary?.subtitle.orEmpty() }
            val playlist = id.startsWith("VL") || id.startsWith("PL") || id == "LM" || id == "SE"
            val artist = if (playlist) null else text(header?.get("straplineTextOne"))
                .ifEmpty { subtitle.split("•").getOrNull(1)?.trim().orEmpty() }.ifEmpty { null }
            val cover = biggestImage(header?.get("thumbnail")) ?: summary?.cover
            val album = OnlineAlbum(id, title, subtitle.ifEmpty { null }, cover)
            val songs = songRows(rows, fallbackArtist = artist, keepOrder = true).map {
                it.copy(album = it.album ?: title.takeUnless { playlist }, artworkUri = it.artworkUri ?: cover)
            }
            if (id.startsWith("MPRE")) songs.forEach { track ->
                val video = track.id.value.removePrefix("yt:")
                albumIds.putIfAbsent(video, id)
            }
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
                val id = ((row["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String)
                    ?: (collect(row["navigationEndpoint"], "watchEndpoint") + collect(row["overlay"], "watchEndpoint"))
                        .firstNotNullOfOrNull { it["videoId"] as? String } ?: return@mapNotNull null
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
                val albumRun = runs.firstOrNull { page(it) == "MUSIC_PAGE_TYPE_ALBUM" }
                val album = albumRun?.get("text") as? String
                // Where "Go to album" leads for this song (its album page on YouTube Music).
                recordCatalogueLinks(id, runs)
                val clock = Regex("""\d{1,2}(:\d{2}){1,2}""")
                val fixed = Regex(""""text"\s*:\s*"(\d{1,2}(?::\d{2}){1,2})"""").find(dump(row["fixedColumns"]))?.groupValues?.get(1)
                val duration = (plain.lastOrNull { it.matches(clock) } ?: fixed)?.split(':')?.fold(0L) { acc, p -> acc * 60 + p.toLong() } ?: 0L
                val thumbs = Regex(""""url"\s*:\s*"(https://[^"]+)"""").findAll(dump(row["thumbnail"])).map { it.groupValues[1] }.toList()
                // Album art is served at any size: ask for one large enough for the player.
                val cover = thumbs.lastOrNull()?.replace(Regex("""=w\d+-h\d+"""), "=w1080-h1080")
                if (title.isEmpty()) return@mapNotNull null
                rank to Track(TrackId("yt:$id"), "${OnlineStreams.SCHEME}://$id", title, artist, album = album, durationMs = duration * 1000, artworkUri = cover, artists = artists.ifEmpty { listOf(artist) })
            }
            val songs = (if (keepOrder) ranked else ranked.sortedBy { it.first }).map { it.second }
            return if (keepOrder) songs else songs.distinctBy { it.id }.take(20)
        }

        /** A radio answer: each queued song (title, "Artist • Album • Year", length, cover) and the continuation. */
        fun parseRadio(json: String): RadioPage? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val tracks = queueTracks(root).distinctBy { it.id }
            val token = Regex(""""nextRadioContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: Regex(""""nextContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            return RadioPage(tracks, token)
        }

        internal data class PlaylistQueue(val tracks: List<Track>, val continuation: String?, val total: Int?)

        internal fun confirmedPlaylistQueue(playlistId: String, json: String): PlaylistQueue? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val panel = collect(root, "playlistPanelRenderer").firstOrNull { pane ->
                val id = pane["playlistId"] ?: collect(pane["contents"], "playlistPanelVideoRenderer")
                    .firstNotNullOfOrNull { row ->
                        collect(row["navigationEndpoint"], "watchEndpoint").firstNotNullOfOrNull { it["playlistId"] as? String }
                    }
                id == playlistId
            } ?: return null
            val contents = panel["contents"] as? List<*> ?: return null
            val tracks = queueTracks(contents)
            val token = pageToken(contents, panel["continuations"])
            val count = panel["totalVideos"]?.toString()?.toIntOrNull()
            return PlaylistQueue(tracks, token, count)
        }

        private fun parseQueueContinuation(playlistId: String, json: String): RadioPage? {
            val root = ua.olex.music.lyrics.Json.parse(json) ?: return null
            val panel = collect(root, "playlistPanelContinuation").firstOrNull()
            if (panel?.get("playlistId")?.let { it != playlistId } == true) return null
            val contents = (panel?.get("contents") ?: collect(root, "appendContinuationItemsAction")
                .firstNotNullOfOrNull { action ->
                    (action["continuationItems"] as? List<*>)?.takeIf { collect(it, "playlistPanelVideoRenderer").isNotEmpty() }
                }) as? List<*> ?: return null
            return RadioPage(queueTracks(contents), pageToken(contents, panel?.get("continuations")))
        }

        private fun queueTracks(root: Any?): List<Track> =
            collect(root, "playlistPanelVideoRenderer").mapNotNull { row ->
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
                if (!video) recordCatalogueLinks(id, runs)
                val year = if (video) null else parts.lastOrNull()?.takeIf { it.matches(Regex("""\d{4}""")) }?.toInt()
                val length = text(row["lengthText"]).split(':').mapNotNull { it.trim().toLongOrNull() }.fold(0L) { acc, p -> acc * 60 + p }
                val cover = biggestImage(row["thumbnail"])
                Track(TrackId("yt:$id"), "${OnlineStreams.SCHEME}://$id", song, artist, album = album, durationMs = length * 1000, year = year, artworkUri = cover, artists = if (video) listOf(artist) else artists.ifEmpty { listOf(artist) })
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
        fun keysOf(song: Track): List<String> = (song.artists + song.artist).map { clean(song.title) + "\u0000" + clean(it) }.distinct()

        /** The key an online song is looked up by. */
        fun keyOf(online: Track): String = clean(online.title) + "\u0000" + clean(online.artist)

        /**
         * The library indexed for [localCopy]: built once per library, so matching a mix of fifty
         * songs is fifty lookups, not fifty passes over every file with regular expressions.
         */
        class Index(songs: List<Track>) {
            private val byKey = HashMap<String, MutableList<Track>>().apply { songs.filterNot(::isOnline).forEach { s -> keysOf(s).forEach { getOrPut(it) { ArrayList(1) } += s } } }
            // The best file of the same recording: lengths within three seconds when both are known (an
            // unknown length needs an existing source link), the best quality first.
            fun copyOf(online: Track): Track? = byKey[keyOf(online)]?.filter { song ->
                sameTitle(online, song) && if (online.durationMs > 0 && song.durationMs > 0) kotlin.math.abs(song.durationMs - online.durationMs) <= 3_000
                else song.sources.any { it.kind == ua.olex.music.model.RecordingSourceKind.YOUTUBE && it.uri == online.uri }
            }?.maxByOrNull { it.format?.rank ?: 0 }


        }

        // Extras like "(Remastered 2011)" or "- 2009 Mix" are dropped; words that make a different
        // recording (live, remix, acoustic, demo, instrumental...) stay, so a live take never stands in
        // for the studio song.
        private val versionWords = Regex("""\b(live|remix|rmx|acoustic|demo|instrumental|unplugged|karaoke|cover|edit|version|session|sped up|slowed|nightcore|orchestral|piano)\b""")
        private fun versionOf(title: String): String = versionWords.findAll(TextFold.fold(title)).map { it.value }.filterNot { it == "version" || it == "edit" }.sorted().joinToString(" ")
        private fun clean(s: String): String {
            val folded = TextFold.fold(s)
            val kept = versionOf(folded)
            val base = folded.replace(Regex("""\s*[(\[].*?[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()
            return if (kept.isEmpty()) base else "$base [$kept]"
        }

        private fun sameTitle(a: Track, b: Track): Boolean =
            a.key.value.substringAfter('|') == b.key.value.substringAfter('|')

        /** Conservative search evidence; the service validates it against all known recordings. */
        fun sameSong(online: Track, song: Track): Boolean = sameTitle(online, song) &&
            keysOf(song).contains(keyOf(online)) && online.durationMs > 0 && song.durationMs > 0 &&
            kotlin.math.abs(song.durationMs - online.durationMs) <= 3_000

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
