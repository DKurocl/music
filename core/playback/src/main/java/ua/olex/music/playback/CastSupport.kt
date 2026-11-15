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
internal class LanMediaServer(private val context: Context) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val files = ConcurrentHashMap<String, Uri>()
    private var socket: ServerSocket? = null

    @Synchronized fun publish(uri: Uri): Uri? {
        val address = wifiAddress() ?: return null
        val server = socket ?: ServerSocket(0, 16, address).also { s -> socket = s; Thread({ accept(s) }, "cast-server").apply { isDaemon = true }.start() }
        val id = Integer.toHexString(uri.toString().hashCode()) + Integer.toHexString(files.size)
        val existing = files.entries.firstOrNull { it.value == uri }?.key
        val key = existing ?: id.also { files[it] = uri }
        return Uri.parse("http://${address.hostAddress}:${server.localPort}/$token/$key")
    }

    @Synchronized fun stop() { runCatching { socket?.close() }; socket = null; files.clear() }

    private fun wifiAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            Thread({ runCatching { serve(client) }; runCatching { client.close() } }, "cast-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val lines = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
        val request = lines.firstOrNull()?.split(' ') ?: return
        val method = request.getOrNull(0); val path = request.getOrNull(1).orEmpty()
        val out = client.getOutputStream()
        val parts = path.trimStart('/').split('/')
        val uri = if (parts.size == 2 && parts[0] == token) files[parts[1]] else null
        if (uri == null || (method != "GET" && method != "HEAD")) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter('=')?.split('-')
            val start = range?.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = (range?.getOrNull(1)?.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
            val length = (end - start + 1).coerceAtLeast(0)
            val status = if (range != null) "206 Partial Content" else "200 OK"
            val head = buildString {
                append("HTTP/1.1 $status\r\nContent-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            if (method == "GET") java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
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

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }
}
