package ua.olex.music.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.olex.music.lyrics.Json
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Apple Music's moving album covers, through the official Apple Music API with the owner's own
 * MusicKit key (Apple Developer account: Team ID, Key ID and the .p8 private key). The app signs its
 * own developer token on the phone; nothing else is sent but the album being looked up.
 *
 * Each cover is a short HLS video; it is fetched once and joined into one file for the album
 * ([MotionCovers]), so every song of the album plays it and nothing streams again.
 */
internal class AppleMotion(context: Context, private val get: (String, Map<String, String>) -> ByteArray? = ::fetch) {
    private val prefs = context.getSharedPreferences("apple_motion", Context.MODE_PRIVATE)

    data class Key(val teamId: String, val keyId: String, val p8: String)

    var key: Key?
        get() {
            val team = prefs.getString("team", null) ?: return null
            val id = prefs.getString("kid", null) ?: return null
            val p8 = prefs.getString("p8", null) ?: return null
            return Key(team, id, p8)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) { remove("team"); remove("kid"); remove("p8") }
                else { putString("team", value.teamId.trim()); putString("kid", value.keyId.trim()); putString("p8", value.p8) }
            }.apply()
            token = null
        }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    /** Cellular data is spared unless the owner allows it. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) { prefs.edit().putBoolean("wifi_only", value).apply() }

    private var token: Pair<String, Long>? = null

    private fun developerToken(): String? {
        val k = key ?: return null
        val now = System.currentTimeMillis() / 1000
        token?.let { (t, exp) -> if (now < exp - 3_600) return t }
        val signed = runCatching { sign(k, now) }.getOrNull() ?: return null
        token = signed to now + TOKEN_SECONDS
        return signed
    }

    /** Whether this album was looked up lately and had no moving cover (asked again after two weeks). */
    private fun missed(album: String): Boolean = System.currentTimeMillis() - prefs.getLong("miss:$album", 0) < 14L * 24 * 3600 * 1000
    private fun miss(album: String) { prefs.edit().putLong("miss:$album", System.currentTimeMillis()).apply() }

    /**
     * Finds the album's moving cover and returns it as one fragmented MP4 (video only), or null.
     * A miss is remembered so the same album is not asked again for two weeks.
     */
    suspend fun find(track: Track, storefront: String = "us"): ByteArray? = withContext(Dispatchers.IO) {
        val album = track.album?.takeIf { it.isNotBlank() } ?: return@withContext null
        val artist = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist
        val lookup = TextFold.fold("$artist\u0000$album")
        if (!enabled || missed(lookup)) return@withContext null
        val bearer = developerToken() ?: return@withContext null
        val headers = mapOf("Authorization" to "Bearer $bearer")
        val term = URLEncoder.encode("$artist $album", "UTF-8")
        val body = get("$API/v1/catalog/$storefront/search?types=albums&limit=10&extend=editorialVideo&term=$term", headers)
            ?: return@withContext null
        val video = pick(String(body, Charsets.UTF_8), album, artist) ?: run { miss(lookup); return@withContext null }
        joinHls(video) { url -> get(url, emptyMap()) } ?: run { miss(lookup); null }
    }

    companion object {
        const val API = "https://api.music.apple.com"
        /** Apple allows up to six months; a month keeps a leaked token short-lived. */
        const val TOKEN_SECONDS = 30L * 24 * 3600
        const val MAX_BYTES = MotionCovers.MAX_BYTES

        /** An ES256 developer token: {"alg":"ES256","kid":…} . {"iss":team,"iat":…,"exp":…}. */
        fun sign(key: Key, nowSeconds: Long): String {
            val b64 = Base64.getUrlEncoder().withoutPadding()
            val header = b64.encodeToString("""{"alg":"ES256","kid":"${key.keyId.trim()}"}""".toByteArray())
            val payload = b64.encodeToString("""{"iss":"${key.teamId.trim()}","iat":$nowSeconds,"exp":${nowSeconds + TOKEN_SECONDS}}""".toByteArray())
            val pem = key.p8.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
            val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(private); update("$header.$payload".toByteArray()); sign() }
            return "$header.$payload.${b64.encodeToString(derToJose(der))}"
        }

        /** JWT wants the raw r||s (64 bytes); Java signs in DER. */
        fun derToJose(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7F
            fun int(): ByteArray {
                require(der[i].toInt() == 0x02); val len = der[i + 1].toInt(); val start = i + 2; i = start + len
                val v = der.copyOfRange(start, start + len).dropWhile { it.toInt() == 0 }.toByteArray()
                return ByteArray(32 - v.size) + v
            }
            return int() + int()
        }

        /** The square moving cover of the album that matches by name and artist, from a search answer. */
        fun pick(json: String, album: String, artist: String): String? {
            val root = Json.parse(json) as? Map<*, *> ?: return null
            val rows = (((root["results"] as? Map<*, *>)?.get("albums") as? Map<*, *>)?.get("data") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            fun fold(s: String) = TextFold.fold(s).replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim()
            val wantAlbum = fold(album); val wantArtist = fold(artist)
            val ranked = rows.mapNotNull { row ->
                val attrs = row["attributes"] as? Map<*, *> ?: return@mapNotNull null
                val video = attrs["editorialVideo"] as? Map<*, *> ?: return@mapNotNull null
                val square = listOf("motionDetailSquare", "motionSquareVideo1x1").firstNotNullOfOrNull { (video[it] as? Map<*, *>)?.get("video")?.toString() }
                val url = square ?: (video["motionDetailTall"] as? Map<*, *>)?.get("video")?.toString() ?: return@mapNotNull null
                val rawName = TextFold.fold(attrs["name"]?.toString().orEmpty())
                val name = fold(attrs["name"]?.toString().orEmpty()); val by = fold(attrs["artistName"]?.toString().orEmpty())
                val artistOk = by == wantArtist || by.contains(wantArtist) || wantArtist.contains(by)
                if (!artistOk) return@mapNotNull null
                // The exact edition first, then the same name with extras ("Deluxe"), a square video before a tall one.
                val score = (if (rawName == TextFold.fold(album)) 4 else if (name == wantAlbum) 3 else if (name.contains(wantAlbum) || wantAlbum.contains(name)) 1 else 0) +
                    (if (square != null) 1 else 0)
                (score to url).takeIf { score >= 2 }
            }
            return ranked.maxByOrNull { it.first }?.second
        }

        /**
         * Joins an HLS video into one fragmented MP4: the master playlist's best H.264 variant up to
         * 1080 px (smallest above that otherwise), its init segment and every media segment, in
         * order. Encrypted streams are skipped. Null when anything is missing or it grows too big.
         */
        fun joinHls(master: String, get: (String) -> ByteArray?): ByteArray? {
            val masterText = get(master)?.toString(Charsets.UTF_8) ?: return null
            val variants = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n([^\n#]+)""").findAll(masterText).map { m ->
                val attrs = m.groupValues[1]
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val avc = attrs.contains("avc1")
                Triple(resolve(master, m.groupValues[2].trim()), height, avc)
            }.toList()
            val media = if (variants.isEmpty()) master else {
                val pool = variants.filter { it.third }.ifEmpty { variants }
                (pool.filter { it.second in 1..1080 }.maxByOrNull { it.second } ?: pool.minByOrNull { it.second })?.first ?: return null
            }
            val playlist = if (media == master) masterText else get(media)?.toString(Charsets.UTF_8) ?: return null
            if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) return null
            val out = ByteArrayOutputStream()
            Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(playlist)?.let { init -> out.write(get(resolve(media, init.groupValues[1])) ?: return null) }
            for (line in playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                out.write(get(resolve(media, line)) ?: return null)
                if (out.size() > MAX_BYTES) return null
            }
            return out.toByteArray().takeIf { it.size > 1_000 }
        }

        private fun resolve(base: String, ref: String): String = URI(base).resolve(ref).toString()

        fun fetch(url: String, headers: Map<String, String>): ByteArray? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 15_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try { if (c.responseCode in 200..299) c.inputStream.use { it.readNBytes(MAX_BYTES.toInt() + 1) } else null } finally { c.disconnect() }
        }.getOrNull()
    }
}
