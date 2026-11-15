package ua.olex.music.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ua.olex.music.lyrics.Json
import ua.olex.music.model.TextFold
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Artist photos from Deezer's public catalogue (no account, no key), fetched once per artist the
 * first time the artist is shown, kept on disk. Only the artist's name is sent. A miss is
 * remembered for a month so the network is not asked again and again.
 */
class ArtistImages(context: Context, private val http: (String) -> ByteArray? = ::get) {
    private val dir = File(context.filesDir, "artists").apply { mkdirs() }
    private val gate = Semaphore(3)

    /** Stores a photo as if fetched (tests and previews). */
    internal fun put(name: String, bytes: ByteArray) { File(dir, key(name) + ".jpg").writeBytes(bytes) }

    fun cached(name: String): File? = if (!enabled) null else cachedFile(name)
    private fun cachedFile(name: String): File? = File(dir, key(name) + ".jpg").takeIf { it.isFile }

    /** The owner can turn photos off in Settings; cached photos stay until then cleared. */
    var enabled: Boolean
        get() = prefs.getBoolean("artist_photos", true)
        set(value) { prefs.edit().putBoolean("artist_photos", value).apply() }
    private val prefs = context.getSharedPreferences("artist_images", Context.MODE_PRIVATE)

    suspend fun image(name: String): File? {
        if (!enabled) return null
        if (name.isBlank() || TextFold.fold(name) in NOT_ARTISTS) return null
        cachedFile(name)?.let { return it }
        val miss = File(dir, key(name) + ".none")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < 30L * 24 * 3600 * 1000) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cachedFile(name) ?: fetch(name)?.let { bytes ->
                    val file = File(dir, key(name) + ".jpg"); val tmp = File(dir, file.name + ".tmp")
                    tmp.writeBytes(bytes); tmp.renameTo(file); file
                } ?: run { miss.writeText(""); null }
            }
        }
    }

    /** The first result whose name matches (ignoring case and accents); Deezer's empty placeholder is skipped. */
    internal fun fetch(name: String): ByteArray? = runCatching {
        val primary = name.split(';', ',', '&').first().trim().removeSuffix(" feat.").trim()
        val body = http("https://api.deezer.com/search/artist?limit=5&q=" + URLEncoder.encode(primary, "UTF-8")) ?: return null
        val rows = ((Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *>)?.get("data") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val wanted = TextFold.fold(primary)
        val hit = rows.firstOrNull { TextFold.fold(it["name"]?.toString().orEmpty()) == wanted } ?: return null
        val url = (hit["picture_xl"] ?: hit["picture_big"])?.toString() ?: return null
        if ("/artist//" in url) return null
        http(url)?.takeIf { it.size in 1_000..(3 shl 20) }
    }.getOrNull()

    private fun key(name: String) = MessageDigest.getInstance("SHA-256").digest(TextFold.fold(name).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    companion object {
        private val NOT_ARTISTS = setOf("unknown", "unknown artist", "various artists", "various", "<unknown>")
        fun get(url: String): ByteArray? = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Music for Android (personal player)")
            try { if (c.responseCode in 200..299) c.inputStream.use { it.readNBytes(4 shl 20) } else null } finally { c.disconnect() }
        }.getOrNull()
    }
}

/** A round artist photo, or the artist's initial on a tonal circle while there is none. */
@Composable
internal fun ArtistImage(name: String, modifier: Modifier, size: Dp, shape: androidx.compose.ui.graphics.Shape = CircleShape) {
    val context = LocalContext.current
    val images = (context.applicationContext as ua.olex.music.MusicApp).graph.artistImages
    val file by produceState(images.cached(name), name) { if (value == null) value = images.image(name) }
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(shape).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = cs.onTertiaryContainer, fontSize = (size.value * 0.4f).sp,
            style = MaterialTheme.typography.titleLarge)
        file?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
