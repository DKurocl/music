package ua.olex.music.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Chromecast and Nest speakers, through Google's Default Media Receiver (plays FLAC, MP3, AAC,
 * Opus, WAV). Chosen in Android's output switcher; playback moves there and back with the queue.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setRemoteToLocalEnabled(true)
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** The speaker cannot open the phone's content:// files, so each queued song gets a LAN address. */
@OptIn(UnstableApi::class)
internal class LanMediaItemConverter(private val server: LanMediaServer) : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val local = mediaItem.localConfiguration?.uri ?: mediaItem.requestMetadata.mediaUri
        val served = local?.let(server::publish)
        val art = mediaItem.mediaMetadata.artworkUri?.let { if (it.scheme == "content") server.publish(it) else it }
        val item = mediaItem.buildUpon()
            .apply { if (served != null) setUri(served) }
            .setMediaMetadata(mediaItem.mediaMetadata.buildUpon().setArtworkUri(art).build())
            .build()
        return delegate.toMediaQueueItem(item)
    }
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = delegate.toMediaItem(mediaQueueItem)
}

/**
 * A tiny HTTP server on the phone's Wi-Fi address, started only when something is cast. It serves
 * only files published to it, each under an unguessable path, read-only, with byte ranges for seeking.
 */
internal class LanMediaServer(private val context: Context, private val address: () -> InetAddress? = { wifiAddress(context) }) {
    private class Session(val server: ServerSocket) {
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val files = ConcurrentHashMap<String, Uri>()
        val keys = HashMap<Uri, String>()
        val clients = HashSet<Socket>()
    }
    private var session: Session? = null

    @Synchronized fun publish(uri: Uri): Uri? {
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val active = session ?: open() ?: return null
        val key = active.keys.getOrPut(uri) {
            active.keys.size.toString(16).also { active.files[it] = uri }
        }
        return Uri.parse("http://${active.server.inetAddress.hostAddress}:${active.server.localPort}/${active.token}/$key")
    }

    private fun open(): Session? {
        val server = try { ServerSocket(0, MAX_CLIENTS, address() ?: return null) }
        catch (_: java.io.IOException) { return null }
        catch (_: SecurityException) { return null }
        return Session(server).also { active ->
            session = active
            Thread({ accept(active) }, "cast-server").apply { isDaemon = true }.start()
        }
    }

    @Synchronized fun stop() {
        val active = session ?: return
        session = null
        runCatching { active.server.close() }
        active.clients.forEach { runCatching { it.close() } }
        active.clients.clear()
        active.files.clear()
        active.keys.clear()
    }

    private fun accept(active: Session) {
        while (!active.server.isClosed) {
            val client = try { active.server.accept() } catch (_: java.io.IOException) { break }
            val admitted = synchronized(this) {
                session === active && active.clients.size < MAX_CLIENTS && active.clients.add(client)
            }
            if (!admitted) {
                runCatching { client.close() }
                continue
            }
            Thread({
                try { client.use { serve(active, it) } }
                catch (_: java.io.IOException) { }
                catch (_: SecurityException) { }
                finally { synchronized(this) { active.clients.remove(client) } }
            }, "cast-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(active: Session, client: Socket) {
        client.soTimeout = HEADER_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val deadline = System.nanoTime() + HEADER_TIMEOUT_MS * 1_000_000L
        val request = readLine(input, deadline)?.split(' ', limit = 3) ?: return
        val method = request.getOrNull(0); val path = request.getOrNull(1).orEmpty()
        val out = client.getOutputStream()
        val parts = path.trimStart('/').split('/')
        val uri = if (parts.size == 2 && parts[0] == active.token) active.files[parts[1]] else null
        fun empty(status: String, extra: String = "") =
            out.write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        if (uri == null || (method != "GET" && method != "HEAD")) {
            empty("404 Not Found")
            return
        }
        var total = 0
        var range: String? = null
        while (true) {
            val line = readLine(input, deadline) ?: return
            if (line.isEmpty()) break
            total += line.length + 2
            if (total > MAX_HEADER_CHARS) return
            if (method == "GET" && line.substringBefore(':').equals("Range", ignoreCase = true)) {
                if (range != null) { empty("400 Bad Request"); return }
                range = line.substringAfter(':', "").trim()
            }
        }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val handle = try { resolver.openFileDescriptor(uri, "r") } catch (_: java.io.FileNotFoundException) { null }
        if (handle == null) { empty("404 Not Found"); return }
        handle.use { pfd ->
            val size = pfd.statSize
            if (size < 0) { empty("404 Not Found"); return }
            val requested = range?.let { castByteRange(it, size) }
            if (range != null && requested == null) {
                empty("416 Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
                return
            }
            val start = requested?.first ?: 0L
            val end = requested?.last ?: (size - 1)
            val length = end - start + 1
            val status = if (requested != null) "206 Partial Content" else "200 OK"
            val head = buildString {
                append("HTTP/1.1 $status\r\nContent-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n")
                if (requested != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            if (method == "GET" && length > 0) java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                var pos = start; val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                while (pos <= end) {
                    buf.clear(); buf.limit(minOf(buf.capacity().toLong(), end - pos + 1).toInt())
                    val n = ch.read(buf, pos); if (n <= 0) break
                    out.write(buf.array(), 0, n); pos += n
                }
            }
            out.flush()
        }
    }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE_CHARS && System.nanoTime() - deadline < 0L) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private companion object {
        const val MAX_CLIENTS = 8
        const val MAX_HEADER_CHARS = 16 * 1024
        const val MAX_LINE_CHARS = 8 * 1024
        const val HEADER_TIMEOUT_MS = 5_000

        fun wifiAddress(context: Context): InetAddress? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return null
            val props = cm.getLinkProperties(network) ?: return null
            return props.linkAddresses.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }?.address
        }
    }
}

/** Single HTTP byte range, including open-ended and suffix seeks. */
internal fun castByteRange(header: String, size: Long): LongRange? {
    if (size <= 0 || !header.startsWith("bytes=", ignoreCase = true)) return null
    val offsets = header.substringAfter('=').split('-', limit = 2)
    if (offsets.size != 2) return null
    fun offset(value: String) = value.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.toLongOrNull()
    val (first, last) = offsets
    if (first.isEmpty()) {
        val suffix = offset(last)?.takeIf { it > 0 } ?: return null
        return (size - suffix.coerceAtMost(size)) until size
    }
    val start = offset(first)?.takeIf { it < size } ?: return null
    val end = if (last.isEmpty()) size - 1 else offset(last)?.coerceAtMost(size - 1) ?: return null
    return (start..end).takeUnless { it.isEmpty() }
}
