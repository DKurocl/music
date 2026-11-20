package ua.olex.music.ui

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import java.io.File
import java.security.MessageDigest

/** Bounded, account-isolated snapshots with display metadata only. Call from an IO dispatcher. */
internal class OnlinePageStore(private val directory: File) {
    @Synchronized
    fun read(scope: String, key: String): JSONObject? = runCatching {
        val file = file(scope, key)
        // A page not opened for two weeks is out of date and of no use offline any more: gone.
        if (file.exists() && System.currentTimeMillis() - file.lastModified() > MAX_IDLE_MS) { file.delete(); return null }
        val bytes = AtomicFile(file).openRead().use { it.readNBytes(MAX_PAGE + 1) }
        if (bytes.size > MAX_PAGE) return null
        JSONObject(bytes.toString(Charsets.UTF_8)).also { file.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    @Synchronized
    fun write(scope: String, key: String, page: JSONObject) {
        runCatching {
            val bytes = page.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_PAGE || (!directory.isDirectory && !directory.mkdirs())) return
            val target = file(scope, key)
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (failure: Exception) {
                atomic.failWrite(stream)
                throw failure
            }
            val files = directory.listFiles { f -> f.extension == "json" }.orEmpty()
            var total = files.sumOf { it.length() }
            var count = files.size
            val stale = System.currentTimeMillis() - MAX_IDLE_MS
            for (old in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES && count <= MAX_FILES && old.lastModified() >= stale) break
                if (old == target) continue
                val bytesBeforeDeletion = old.length()
                if (old.delete()) { total -= bytesBeforeDeletion; count-- }
            }
        }
    }

    @Synchronized
    fun mergeLinks(scope: String, links: Map<String, String>) {
        val previous = read(scope, "song-albums")
        val ordered = LinkedHashMap<String, String>()
        previous?.let { json -> json.keys().forEach { id -> if (id !in links) ordered[id] = json.optString(id) } }
        ordered.putAll(links)
        val snapshot = JSONObject()
        ordered.entries.toList().takeLast(2_000).forEach { (id, album) -> snapshot.put(id, album) }
        write(scope, "song-albums", snapshot)
    }

    private fun file(scope: String, key: String) = File(directory, digest("$scope/$key") + ".json")

    companion object {
        private const val MAX_PAGE = 2 * 1024 * 1024
        private const val MAX_BYTES = 16L * 1024 * 1024
        private const val MAX_FILES = 96
        /** Pages unread this long are dropped (their last read touches the file). */
        private const val MAX_IDLE_MS = 14L * 24 * 60 * 60 * 1000

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal object OnlinePageJson {
    private fun JSONObject.string(key: String): String? = if (isNull(key)) null else getString(key)
    private fun <T> JSONObject.list(key: String, decode: (JSONObject) -> T): List<T> =
        optJSONArray(key)?.let { items -> List(items.length()) { decode(items.getJSONObject(it)) } }.orEmpty()
    private fun <T> array(items: List<T>, encode: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(encode(it)) } }

    fun tracks(items: List<Track>) = array(items) { track ->
        JSONObject().put("id", track.id.value).put("title", track.title).put("artist", track.artist)
            .put("album", track.album).put("duration", track.durationMs).put("cover", track.artworkUri)
            .put("albumId", YouTubeMusic.albumOf(track))
    }

    fun tracks(value: JSONObject, key: String): List<Track> = value.list(key) {
        val id = it.getString("id")
        val video = id.removePrefix("yt:")
        require(id.startsWith("yt:") && video.isNotBlank())
        it.string("albumId")?.let { album -> YouTubeMusic.rememberAlbum(video, album) }
        Track(TrackId(id), "ytm://$video", it.getString("title"), it.getString("artist"),
            album = it.string("album"), durationMs = it.getLong("duration"), artworkUri = it.string("cover"))
    }

    fun album(value: YouTubeMusic.OnlineAlbum): JSONObject = JSONObject()
        .put("id", value.id).put("title", value.title).put("subtitle", value.subtitle).put("cover", value.cover)
    fun album(value: JSONObject) = YouTubeMusic.OnlineAlbum(value.getString("id"), value.getString("title"), value.string("subtitle"), value.string("cover"))

    fun cards(items: List<YouTubeMusic.Card>) = array(items) {
        JSONObject().put("id", it.id).put("title", it.title).put("subtitle", it.subtitle).put("cover", it.cover).put("kind", it.kind.name)
    }
    fun cards(value: JSONObject, key: String) = value.list(key) {
        YouTubeMusic.Card(it.getString("id"), it.getString("title"), it.string("subtitle"), it.string("cover"), YouTubeMusic.Card.Kind.valueOf(it.getString("kind")))
    }

    fun artist(value: YouTubeMusic.OnlineArtist): JSONObject = JSONObject()
        .put("id", value.id).put("name", value.name).put("photo", value.photo).put("about", value.about)
        .put("songs", tracks(value.songs)).put("albums", array(value.albums, ::album)).put("videos", tracks(value.videos))
        .put("singles", array(value.singles, ::album)).put("related", cards(value.related)).put("listeners", value.listeners)

    fun artist(value: JSONObject) = YouTubeMusic.OnlineArtist(
        value.getString("id"), value.getString("name"), value.string("photo"), value.string("about"), tracks(value, "songs"),
        value.list("albums", ::album), tracks(value, "videos"), value.list("singles", ::album), cards(value, "related"), value.string("listeners"),
    )
}
