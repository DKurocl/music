package ua.olex.music.soulseek

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread

class SoulseekClientTest {
    @Test fun messagesAreLittleEndianWithLengthFirst() {
        assertArrayEquals(byteArrayOf(14, 0, 0, 0, 26, 0, 0, 0, 7, 0, 0, 0, 2, 0, 0, 0, 'h'.code.toByte(), 'i'.code.toByte()),
            Out.message(26) { u32(7); str("hi") })
        assertArrayEquals(byteArrayOf(5, 0, 0, 0, 0, 9, 0, 0, 0), Out.init(0) { u32(9) })
        assertEquals("127.0.0.1", ipv4(0x7F000001))
    }

    @Test fun sizeAndReadBoundsRejectInvalidMetadataWithoutRetryingTheInput() {
        val frame = Out().u32((8 shl 20) + 1).toByteArray().inputStream()
        assertThrows(IllegalArgumentException::class.java) { frame.readFrame() }
        val counts = Out().str("peer").u32(1).u32(5_001).toByteArray()
        assertNull(SoulseekClient.parseResults(counts))
        val stopped = object : InputStream() {
            override fun read() = -1
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        }
        assertThrows(IOException::class.java) { stopped.readExactly(4) }
    }

    @Test fun cancellingSignInClosesAPeerThatNeverSendsAReply() = runBlocking {
        ServerSocket(0).use { server ->
            val waiting = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Boolean>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream()
                        check(input.readFrame()?.first == 1)
                        waiting.complete(Unit)
                        closed.complete(input.read() == -1)
                    }
                } catch (failure: Exception) {
                    waiting.completeExceptionally(failure)
                    closed.completeExceptionally(failure)
                }
            }
            val client = SoulseekClient(host = "127.0.0.1", port = server.localPort)
            try {
                withTimeout(4_000) {
                    val login = launch { client.login("fixture", "fixture") }
                    waiting.await()
                    login.cancelAndJoin()
                    assertTrue(closed.await())
                    assertEquals(SoulseekClient.State.Offline, client.state.value)
                }
            } finally {
                client.disconnect()
            }
        }
    }

    @Test fun replacingAConnectionClosesItsOldReaderAndKeepsTheNewSessionOnline() = runBlocking {
        ServerSocket(0).use { server ->
            val oldClosed = CompletableDeferred<Unit>()
            val newClosed = CompletableDeferred<Unit>()
            thread(isDaemon = true) {
                try {
                    fun accept(user: String) = server.accept().apply {
                        soTimeout = 4_000
                        val frame = requireNotNull(getInputStream().readFrame())
                        check(frame.first == 1 && In(frame.second).str() == user)
                        getOutputStream().apply { write(Out.message(1) { bool(true) }); flush() }
                    }
                    accept("first").use { old ->
                        while (old.getInputStream().readFrame() != null) Unit
                        oldClosed.complete(Unit)
                    }
                    accept("next").use { next ->
                        while (next.getInputStream().readFrame() != null) Unit
                        newClosed.complete(Unit)
                    }
                } catch (failure: Exception) {
                    oldClosed.completeExceptionally(failure)
                    newClosed.completeExceptionally(failure)
                }
            }
            val client = SoulseekClient(host = "127.0.0.1", port = server.localPort)
            try {
                withTimeout(5_000) {
                    client.login("first", "fixture")
                    client.login("next", "fixture")
                    oldClosed.await()
                    assertEquals(SoulseekClient.State.Online("next"), client.state.value)
                    client.disconnect()
                    newClosed.await()
                    assertEquals(SoulseekClient.State.Offline, client.state.value)
                }
            } finally {
                client.disconnect()
            }
        }
    }

    private fun results(user: String, token: Long, files: List<Pair<String, Long>>): ByteArray {
        val plain = Out().str(user).u32(token).u32(files.size).apply {
            files.forEach { (path, size) -> u8(1); str(path); u64(size); str(""); u32(2); u32(0); u32(1411); u32(1); u32(200) }
        }.bool(true).u32(5_000_000).u32(0).u32(0).toByteArray()
        val zipped = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(plain) } }.toByteArray()
        return Out.message(9) { raw(zipped) }
    }

    /**
     * The whole path a firewalled client takes, against a fake server and a fake peer on this
     * machine: login, a search answered through ConnectToPeer + PierceFireWall, then a download
     * queued, offered, accepted and sent over a file connection the server asked us to open.
     */
    @Test fun searchesAndDownloadsThroughTheServersConnectRequests() = runBlocking {
        val server = ServerSocket(0); val peer = ServerSocket(0)
        val song = ByteArray(300_000) { (it % 251).toByte() }
        val path = "Music\\Tame Impala\\Currents\\05 - Eventually.flac"
        thread(isDaemon = true) {
            val s = server.accept(); val input = s.getInputStream(); val out = s.getOutputStream()
            val (code, body) = input.readFrame()!!
            check(code == 1 && In(body).str() == "olex")
            out.write(Out.message(1) { bool(true); str("hello"); u32(0x7F000001); str("x"); bool(false) }); out.flush()
            while (true) {
                val (c, b) = input.readFrame() ?: break
                when (c) {
                    26 -> { val token = In(b).u32(); out.write(Out.message(18) { str("bob"); str("P"); u32(0x7F000001); u32(peer.localPort); u32(77); bool(false); u32(0); u32(0) }); out.flush()
                        thread(isDaemon = true) {
                            val p = peer.accept(); val (ic, ib) = p.getInputStream().readFrame(init = true)!!
                            check(ic == 0 && In(ib).u32() == 77L)
                            p.getOutputStream().apply { write(results("bob", token, listOf(path to song.size.toLong()))); flush() }
                        } }
                    3 -> { out.write(Out.message(3) { str("bob"); u32(0x7F000001); u32(peer.localPort); u32(0); u32(0) }); out.flush()
                        thread(isDaemon = true) {
                            val p = peer.accept(); val pin = p.getInputStream(); val pout = p.getOutputStream()
                            val (ic, ib) = pin.readFrame(init = true)!!; check(ic == 1 && In(ib).str() == "olex")
                            val (qc, qb) = pin.readFrame()!!; check(qc == 43 && In(qb).str() == path)
                            pout.write(Out.message(40) { u32(1); u32(5); str(path); u64(song.size.toLong()) }); pout.flush()
                            val (rc, rb) = pin.readFrame()!!; val r = In(rb); check(rc == 41 && r.u32() == 5L && r.bool())
                            // The peer can't reach us: it asks the server, which asks us to connect for the file.
                            val files = ServerSocket(0)
                            out.write(Out.message(18) { str("bob"); str("F"); u32(0x7F000001); u32(files.localPort); u32(9); bool(false); u32(0); u32(0) }); out.flush()
                            val f = files.accept(); val (fc, fb) = f.getInputStream().readFrame(init = true)!!; check(fc == 0 && In(fb).u32() == 9L)
                            f.getOutputStream().apply { write(Out().u32(5).toByteArray()); flush() }
                            check(In(f.getInputStream().readExactly(8)!!).u64() == 0L)
                            f.getOutputStream().apply { write(song); flush() }; f.close()
                        } }
                }
            }
        }
        val client = SoulseekClient(host = "127.0.0.1", port = server.localPort)
        client.login("olex", "secret")
        val found = client.search("tame impala eventually", seconds = 2)
        assertEquals(1, found.size)
        assertEquals("05 - Eventually.flac", found[0].name); assertEquals(1411, found[0].bitrate); assertEquals(200, found[0].seconds)
        val saved = ByteArrayOutputStream(); var last = 0L
        client.download(found[0], saved, { got, _ -> last = got }, waitMs = 10_000)
        assertArrayEquals(song, saved.toByteArray()); assertEquals(song.size.toLong(), last)
        client.disconnect()
        assertEquals(SoulseekClient.State.Offline, client.state.value)
        server.close()
        peer.close()
    }
}
