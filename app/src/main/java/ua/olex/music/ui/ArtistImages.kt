package ua.olex.music.ui

import android.content.Context
import android.util.LruCache
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ua.olex.music.OptionalResources
import ua.olex.music.lyrics.Json
import ua.olex.music.model.OptionalWork
import ua.olex.music.model.TextFold
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Artist photos from Deezer's public catalogue (no account, no key), fetched once per artist the
 * first time the artist is shown, kept on disk. Only the artist's name is sent. A miss is
 * remembered for a month so the network is not asked again and again.
 */
class ArtistImages internal constructor(
    context: Context,
    private val resources: OptionalResources? = null,
    private val http: suspend (String) -> ByteArray? = { MetadataHttp.get(it) },
) {
    private val app = context.applicationContext
    private val dir = File(context.filesDir, "artists")

    /** Stores a photo as if fetched (tests and previews). */
    internal fun put(name: String, bytes: ByteArray) { persist(File(dir, key(name) + ".jpg"), bytes) }

    fun cached(name: String): File? = if (!enabled) null else cachedFile(name).also { photos.put(name, Optional(it)) }

    /** What [cached] last found for this artist, without touching the disk (for drawing a screen). */
    fun known(name: String): File? = if (!enabled) null else photos[name]?.value
    /** What [cachedBio] last read, without touching the disk. */
    fun knownBio(name: String): String? = if (!enabled) null else bios[name]?.value

    private class Optional<T>(val value: T?)
    private val photos = LruCache<String, Optional<File>>(512)
    private val bios = object : LruCache<String, Optional<String>>(256 * 1024) {
        override fun sizeOf(key: String, value: Optional<String>) = 64 + (key.length + value.value.orEmpty().length) * 2
    }
    private fun cachedFile(name: String): File? = File(dir, key(name) + ".jpg").takeIf { it.isFile }

    /** Space the photos and notes take. */
    fun bytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Removes every photo and note; they come back as artists are shown again. */
    fun clear() { dir.listFiles()?.forEach { it.delete() }; photos.evictAll(); bios.evictAll() }

    /** Artist photos and biographies share the image budget, including negative-result markers. */
    fun trim() {
        val files = dir.listFiles().orEmpty().filter { it.extension != "tmp" }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length().coerceAtLeast(1) }
        val limit = ua.olex.music.playback.CacheBudget.artistBytes(app)
        var evicted = false
        for (file in files) {
            if (size <= limit) break
            val bytes = file.length().coerceAtLeast(1)
            if (file.delete()) { size -= bytes; evicted = true }
        }
        if (evicted) { photos.evictAll(); bios.evictAll() }
    }


    /** The owner can turn photos off in Settings; cached photos stay until then cleared. */
    var enabled: Boolean
        get() = prefs.getBoolean("artist_photos", true)
        set(value) { prefs.edit().putBoolean("artist_photos", value).apply() }
    private val prefs = context.getSharedPreferences("artist_images", Context.MODE_PRIVATE)

    suspend fun image(name: String): File? = withContext(Dispatchers.IO) {
        if (!enabled || name.isBlank() || TextFold.fold(name) in NOT_ARTISTS) return@withContext null
        cachedFile(name)?.let { return@withContext it }
        val miss = File(dir, key(name) + ".none")
        if (recentMiss(miss)) return@withContext null
        admitted { allowed ->
            cachedFile(name) ?: run {
                val bytes = fetch(name)
                currentCoroutineContext().ensureActive()
                if (!allowed()) return@admitted null
                if (bytes == null) { persistMiss(miss); return@admitted null }
                val file = File(dir, key(name) + ".jpg")
                persist(file, bytes)
                file.takeIf { it.isFile }.also { photos.put(name, Optional(it)) }
            }
        }
    }

    fun cachedBio(name: String): String? = if (!enabled) null else File(dir, key(name) + ".bio").takeIf { it.isFile }?.readText()?.let(::tidyBio)?.ifBlank { null }
        .also { bios.put(name, Optional(it)) }

    /**
     * A short "about" for the artist page: Wikipedia's summary (no account, no key; only the name is
     * sent), taken only when Wikipedia describes a musician or a band, so a namesake never shows.
     * Kept on disk; a miss is remembered for a month.
     */
    suspend fun bio(name: String): String? = withContext(Dispatchers.IO) {
        if (!enabled || name.isBlank() || TextFold.fold(name) in NOT_ARTISTS) return@withContext null
        cachedBio(name)?.let { return@withContext it }
        val miss = File(dir, key(name) + ".nobio")
        if (recentMiss(miss)) return@withContext null
        admitted { allowed ->
            cachedBio(name) ?: run {
                val text = fetchBio(name)
                currentCoroutineContext().ensureActive()
                if (!allowed()) return@admitted null
                if (text == null) { persistMiss(miss); return@admitted null }
                persist(File(dir, key(name) + ".bio"), text.toByteArray(Charsets.UTF_8))
                bios.put(name, Optional(text))
                text
            }
        }
    }

    private fun recentMiss(file: File) = file.isFile && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3600 * 1000

    private suspend fun <T> admitted(load: suspend (() -> Boolean) -> T?): T? = try {
        resources?.runWithPermit(OptionalWork.PREFETCH) { permit ->
            AuxiliaryLoads.run {
                load { enabled && resources.permitsPublication(OptionalWork.PREFETCH, permit) }
            }
        }
    } catch (_: IOException) {
        // Retry an unavailable server on a later visible request. Only an authoritative miss is cached.
        null
    }

    private fun persist(file: File, bytes: ByteArray) {
        ensureDirectory()
        val tmp = File.createTempFile(file.name, ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) throw IOException("Couldn't write the artist cache")
            trim()
        } finally {
            tmp.delete()
        }
    }

    private fun persistMiss(file: File) {
        ensureDirectory()
        file.writeBytes(ByteArray(0))
        trim()
    }

    private fun ensureDirectory() {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Couldn't create the artist cache")
    }

    internal suspend fun fetchBio(name: String): String? {
        val primary = name.split(';', ',', '&').first().trim()
        val music = Regex("""\b(band|singer|rapper|musician|duo|trio|group|producer|dj|songwriter|composer|vocalist|guitarist|pianist|orchestra|artist|record)\b""", RegexOption.IGNORE_CASE)
        return listOf(primary, "$primary (band)", "$primary (musician)", "$primary (rapper)", "$primary (singer)").firstNotNullOfOrNull { title ->
            val body = http("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "_")) ?: return@firstNotNullOfOrNull null
            val page = Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *> ?: throw IOException("Invalid artist biography response")
            val type = page["type"] as? String ?: throw IOException("Invalid artist biography type")
            if (type != "standard") return@firstNotNullOfOrNull null
            val description = page["description"]?.toString().orEmpty()
            val extract = page["extract"]?.toString().orEmpty().trim().let(::tidyBio)
            extract.takeIf { it.length > 40 && (music.containsMatchIn(description) || music.containsMatchIn(extract.take(200))) }
        }
    }

    /** The first result whose name matches (ignoring case and accents); Deezer's empty placeholder is skipped. */
    internal suspend fun fetch(name: String): ByteArray? {
        val primary = name.split(';', ',', '&').first().trim().removeSuffix(" feat.").trim()
        val body = http("https://api.deezer.com/search/artist?limit=5&q=" + URLEncoder.encode(primary, "UTF-8")) ?: return null
        val rows = (Json.parse(String(body, Charsets.UTF_8)) as? Map<*, *>)?.get("data") as? List<*>
            ?: throw IOException("Invalid artist search response")
        val wanted = TextFold.fold(primary)
        val hit = rows.asSequence().mapNotNull { it as? Map<*, *> }
            .firstOrNull { TextFold.fold(it["name"]?.toString().orEmpty()) == wanted } ?: return null
        val url = (hit["picture_xl"] ?: hit["picture_big"])?.toString() ?: return null
        if (!url.startsWith("https://") || "/artist//" in url) return null
        return http(url)?.takeIf { it.size in 1_000..(3 shl 20) }
    }

    private fun key(name: String) = MessageDigest.getInstance("SHA-256").digest(TextFold.fold(name).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    companion object {
        private val NOT_ARTISTS = setOf("unknown", "unknown artist", "various artists", "various", "<unknown>")

    }
}

/** A round artist photo, or the artist's initial on a tonal circle while there is none. */
@Composable
internal fun ArtistImage(name: String, modifier: Modifier, size: Dp, shape: androidx.compose.ui.graphics.Shape = CircleShape, fallback: String? = null) {
    val context = LocalContext.current
    val images = (context.applicationContext as ua.olex.music.MusicApp).graph.artistImages
    // The disk is checked off the main thread; a photo seen before in this process shows at once.
    val file by produceState(images.known(name), name) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { images.cached(name) } ?: images.image(name)
    }
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(shape).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
        // No photo yet: one of the artist's covers when known, else the initial.
        if (fallback != null && file == null) AsyncImage(model = fallback, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = cs.onTertiaryContainer, fontSize = (size.value * 0.4f).sp,
            style = MaterialTheme.typography.titleLarge)
        file?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Wikipedia's plain extract keeps the spaces left by stripped pronunciations and notes: "Yorke ; brothers", "( )". */
internal fun tidyBio(text: String) = text.replace(Regex("""\(\s*[;,]?\s*\)"""), "").replace(Regex("""\s+([;,.:)])"""), "$1")
    .replace(Regex("""\(\s+"""), "(").replace(Regex(""" {2,}"""), " ").trim()
