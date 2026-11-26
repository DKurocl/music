package ua.olex.music.playback

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastSupportTest {
    @Test fun boundedAndSuffixByteRangesRespectFileSize() {
        assertEquals(0L..0L, castByteRange("bytes=0-0", 100))
        assertEquals(7L..99L, castByteRange("bytes=7-", 100))
        assertEquals(96L..99L, castByteRange("bytes=-4", 100))
        assertEquals(0L..99L, castByteRange("bytes=-110", 100))
        assertEquals(90L..99L, castByteRange("bytes=90-200", 100))
        listOf("bytes=100-", "bytes=3-2", "bytes=-0", "bytes=+1-3", "bytes=0-1,3-4",
            "bytes=9223372036854775808-", "items=1-2").forEach {
            assertNull(it, castByteRange(it, 100))
        }
        assertNull(castByteRange("bytes=0-", 0))
    }

    @Test(timeout = 10_000) fun serverReturnsPartialBytesAndRotatesPublishedPathsOnStop() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.cacheDir.resolve("cast.mp3").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5)) }
        val media = Uri.fromFile(file)
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = LanMediaServer(context) { loopback }
        fun request(uri: Uri, method: String = "GET", range: String? = null, path: String = uri.path!!): Pair<String, ByteArray> =
            Socket(loopback, uri.port).use { client ->
                client.soTimeout = 2_000
                val headers = range?.let { "Range: $it\r\n" }.orEmpty()
                client.getOutputStream().write("$method $path HTTP/1.1\r\nHost: localhost\r\n${headers}\r\n".toByteArray())
                val input = client.getInputStream()
                val reply = input.readBytes()
                val split = reply.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                assertTrue(split >= 0)
                reply.copyOfRange(0, split).toString(Charsets.ISO_8859_1) to reply.copyOfRange(split + 4, reply.size)
            }

        try {
            assertNull(server.publish(Uri.parse("ytm://audio")))
            val first = requireNotNull(server.publish(media))
            assertEquals(first, server.publish(media))
            val (headers, bytes) = request(first, range = "bytes=-2")
            assertTrue(headers.startsWith("HTTP/1.1 206"))
            assertTrue(headers.contains("Content-Range: bytes 4-5/6"))
            assertArrayEquals(byteArrayOf(4, 5), bytes)
            val (head, noBody) = request(first, method = "HEAD", range = "bytes=1-2")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.contains("Content-Length: 6"))
            assertEquals(0, noBody.size)
            val (unsatisfied, _) = request(first, range = "bytes=9-")
            assertTrue(unsatisfied.startsWith("HTTP/1.1 416"))
            assertTrue(unsatisfied.contains("Content-Range: bytes */6"))

            server.stop()
            val next = requireNotNull(server.publish(media))
            assertNotEquals(first.path, next.path)
            assertTrue(request(next, path = first.path!!).first.startsWith("HTTP/1.1 404"))
            assertArrayEquals(file.readBytes(), request(next).second)
        } finally { server.stop() }
    }

    @Test(timeout = 10_000) fun stoppingTheRouteClosesAnInFlightFileResponse() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.cacheDir.resolve("cast-long.mp3")
        val fileSize = 32L * 1024 * 1024
        java.io.RandomAccessFile(file, "rw").use { it.setLength(fileSize) }
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = LanMediaServer(context) { loopback }
        try {
            val uri = requireNotNull(server.publish(Uri.fromFile(file)))
            Socket().use { client ->
                client.receiveBufferSize = 16 * 1024
                client.soTimeout = 2_000
                client.connect(java.net.InetSocketAddress(loopback, uri.port))
                client.getOutputStream().write("GET ${uri.path} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                val input = client.getInputStream()
                assertEquals('H'.code, input.read())
                server.stop()
                val buffer = ByteArray(8 * 1024)
                var received = 1L
                while (received < fileSize) {
                    val count = try { input.read(buffer) } catch (_: java.net.SocketException) { break }
                    if (count < 0) break
                    received += count
                }
                assertTrue(received < fileSize)
            }
        } finally { server.stop() }
    }
}
