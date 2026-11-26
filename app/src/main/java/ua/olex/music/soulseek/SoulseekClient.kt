package ua.olex.music.soulseek

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.InflaterInputStream
import kotlin.random.Random

/**
 * A small Soulseek client for downloads only, built the way Nicotine+ works behind a firewall: a
 * phone cannot take incoming connections, so every connection is opened from here. Peers that
 * want to send search results or a file ask the server, the server sends ConnectToPeer, and this
 * client connects out and answers with PierceFireWall.
 *
 * It shares nothing (and says so: zero folders, zero files) and never uploads.
 */
class SoulseekClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val host: String = HOST, private val port: Int = PORT,
) {

    sealed interface State {
        data object Offline : State
        data object Connecting : State
        data class Online(val username: String) : State
        data class Failed(val reason: String) : State
    }

    /** One file a peer offered for a search. */
    data class Result(
        val username: String, val path: String, val size: Long, val slotFree: Boolean, val speed: Long, val queue: Long,
        val bitrate: Int?, val seconds: Int?, val sampleRate: Int?, val bitDepth: Int?,
    ) {
        val name get() = path.substringAfterLast('\\').substringAfterLast('/')
        val folder get() = path.substringBeforeLast('\\', "").substringBeforeLast('/', path.substringBeforeLast('\\', ""))
        val extension get() = name.substringAfterLast('.', "").lowercase()
    }

    private val _state = MutableStateFlow<State>(State.Offline)
    val state: StateFlow<State> = _state

    /** One authenticated connection owns its readers, peer sockets and in-flight replies. */
    private class Connection(val username: String) {
        val server = Socket()
        var reader: Job? = null
        val searches = ConcurrentHashMap<Long, MutableList<Result>>()
        val addresses = ConcurrentHashMap<String, CompletableDeferred<Pair<String, Int>>>()
        val offers = ConcurrentHashMap<String, CompletableDeferred<Pair<Long, Long>>>()
        val files = ConcurrentHashMap<Pair<String, Long>, CompletableDeferred<Socket>>()
        val refusals = ConcurrentHashMap<String, CompletableDeferred<String>>()
        private val peers = HashSet<Socket>()
        private var closed = false

        fun peer(): Socket = synchronized(this) {
            if (closed) throw IOException("The Soulseek connection was closed")
            Socket().also(peers::add)
        }

        fun closePeer(peer: Socket) {
            synchronized(this) { peers.remove(peer) }
            runCatching { peer.close() }
        }

        fun close() {
            val sockets = synchronized(this) {
                if (closed) return
                closed = true
                peers.toList().also { peers.clear() }
            }
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
            reader?.cancel()
            val cause = IOException("The Soulseek connection was closed")
            addresses.values.forEach { it.completeExceptionally(cause) }
            offers.values.forEach { it.completeExceptionally(cause) }
            files.values.forEach { it.completeExceptionally(cause) }
            refusals.values.forEach { it.completeExceptionally(cause) }
            searches.clear()
        }
    }

    @Volatile private var connection: Connection? = null

    /** Logs in (a new name is registered by the server on first login with it). */
    suspend fun login(user: String, password: String) = withContext(Dispatchers.IO) {
        val current = Connection(user)
        val retired = synchronized(this@SoulseekClient) {
            connection.also {
                connection = current
                _state.value = State.Connecting
            }
        }
        retired?.close()
        try {
            coroutineScope {
                val transferred = java.util.concurrent.atomic.AtomicBoolean()
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { if (!transferred.get()) runCatching { current.server.close() } }
                }
                try {
                    val socket = current.server
                    socket.connect(InetSocketAddress(host, this@SoulseekClient.port), 15_000)
                    socket.keepAlive = true
                    socket.soTimeout = 20_000
                    val hash = MessageDigest.getInstance("MD5").digest((user + password).toByteArray()).joinToString("") { "%02x".format(it) }
                    send(current, Out.message(1) { str(user); str(password); u32(MAJOR); str(hash); u32(MINOR) })
                    val input = socket.getInputStream()
                    val (code, body) = input.readFrame() ?: throw IOException("The server closed the connection")
                    require(code == 1) { "Unexpected answer to login" }
                    val answer = In(body)
                    if (!answer.bool()) {
                        val reason = answer.str()
                        throw IOException(when (reason) {
                            "INVALIDPASS" -> "Wrong password for this name"
                            "INVALIDUSERNAME" -> "This name can't be used"
                            else -> reason
                        })
                    }
                    // Advertise no shares or distributed search.
                    send(current, Out.message(2) { u32(LISTEN_PORT) })
                    send(current, Out.message(28) { u32(2) })
                    send(current, Out.message(35) { u32(0); u32(0) })
                    send(current, Out.message(71) { bool(true) })
                    socket.soTimeout = 0
                    currentCoroutineContext().ensureActive()
                    synchronized(this@SoulseekClient) {
                        if (connection !== current) throw IOException("The Soulseek connection was replaced")
                        transferred.set(true)
                        _state.value = State.Online(user)
                        current.reader = scope.launch { serve(current, input) }
                    }
                } finally {
                    closer.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            retire(current, State.Offline)
            throw cancelled
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                retire(current, State.Offline)
                currentCoroutineContext().ensureActive()
            }
            retire(current, State.Failed(e.message ?: "Couldn't reach Soulseek"))
            throw e
        }
    }

    fun disconnect() = retire(null, State.Offline)

    private fun retire(expected: Connection?, state: State) {
        val retired = synchronized(this) {
            if (expected != null && connection !== expected) return
            connection.also {
                connection = null
                _state.value = state
            }
        }
        retired?.close()
    }

    private fun send(current: Connection, bytes: ByteArray) = synchronized(current.server) {
        current.server.getOutputStream().apply { write(bytes); flush() }
    }

    private suspend fun serve(current: Connection, input: InputStream) {
        try {
            while (true) {
                val (code, body) = input.readFrame() ?: break
                val m = In(body)
                when (code) {
                    3 -> { // GetPeerAddress
                        val user = m.str(); val ip = ipv4(m.u32()); val port = m.u32().toInt()
                        current.addresses.remove(user)?.complete(ip to port)
                    }
                    18 -> { // ConnectToPeer: a peer asks us to connect to it
                        val user = m.str(); val type = m.str(); val ip = ipv4(m.u32()); val port = m.u32().toInt(); val token = m.u32()
                        scope.launch { pierce(current, user, type, ip, port, token) }
                    }
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
        finally { retire(current, State.Failed("Disconnected from Soulseek")) }
    }

    /** Connects to a peer that asked for it and greets it with PierceFireWall, then serves the connection. */
    private suspend fun pierce(current: Connection, user: String, type: String, ip: String, port: Int, token: Long) {
        val socket = try { current.peer() } catch (_: IOException) { return }
        var handedOff = false
        try {
            socket.connect(InetSocketAddress(ip, port), 12_000)
            socket.getOutputStream().apply { write(Out.init(0) { u32(token) }); flush() }
            when (type) {
                "P" -> servePeer(current, user, socket)
                "F" -> handedOff = fileConnection(current, user, socket)
            }
        } catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
        finally {
            if (!handedOff) current.closePeer(socket)
        }
    }

    /** A file connection: the uploader names the transfer first; it is handed to the download that waits for it. */
    private fun fileConnection(current: Connection, user: String, socket: Socket): Boolean {
        socket.soTimeout = 30_000
        val token = socket.getInputStream().readExactly(4)?.let { In(it).u32() } ?: return false
        return current.files[user to token]?.complete(socket) == true
    }

    /** Reads peer messages on a P connection: search results and the transfer handshake. */
    private suspend fun servePeer(current: Connection, user: String, socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            while (true) {
                val (code, body) = input.readFrame() ?: break
                when (code) {
                    9 -> InflaterInputStream(body.inputStream()).use { compressed ->
                        val decoded = compressed.readNBytes(MAX_SEARCH_BYTES + 1)
                        if (decoded.size <= MAX_SEARCH_BYTES) parseResults(decoded)?.let { (token, results) ->
                            val matching = current.searches[token] ?: return@let
                            synchronized(matching) { matching.addAll(results.take((MAX_RESULTS - matching.size).coerceAtLeast(0))) }
                        }
                    }
                    40 -> { // TransferRequest
                        val m = In(body); val direction = m.u32(); val token = m.u32(); val path = m.str()
                        val size = if (direction == 1L && m.remaining >= 8) m.u64() else 0L
                        val waiting = current.offers.remove(key(user, path))
                        if (direction == 1L && waiting != null) {
                            // Ready for the file connection before saying yes: a quick peer may open it at once.
                            val fileKey = user to token
                            val transfer = current.files.getOrPut(fileKey) { CompletableDeferred() }
                            try {
                                socket.getOutputStream().apply { write(Out.message(41) { u32(token); bool(true) }); flush() }
                                waiting.complete(token to size)
                            } catch (failure: IOException) {
                                current.files.remove(fileKey, transfer)
                                transfer.completeExceptionally(failure)
                                waiting.completeExceptionally(failure)
                                throw failure
                            }
                        } else socket.getOutputStream().apply { write(Out.message(41) { u32(token); bool(false); str("Cancelled") }); flush() }
                    }
                    46 -> { val path = In(body).str(); current.refusals.remove(key(user, path))?.complete("The upload failed") }
                    50 -> { val m = In(body); val path = m.str(); current.refusals.remove(key(user, path))?.complete(m.str()) }
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
    }

    /** Searches the network and returns what peers offered within [seconds]. */
    suspend fun search(query: String, seconds: Int = 8): List<Result> {
        val current = connection ?: throw IOException("Not connected")
        val token = Random.nextLong(1, 0x7FFFFFFF)
        val found = java.util.Collections.synchronizedList(ArrayList<Result>())
        current.searches[token] = found
        try {
            withContext(Dispatchers.IO) { send(current, Out.message(26) { u32(token); str(query) }) }
            delay(seconds * 1000L)
            if (connection !== current) throw IOException("The Soulseek connection was closed")
            return synchronized(found) { found.toList() }
        } finally {
            current.searches.remove(token, found)
        }
    }

    /**
     * Downloads one file to [target], reporting progress. The peer is asked to queue the upload;
     * when its turn comes it offers the transfer, we accept, and it sends the file over a new
     * connection.
     */
    suspend fun download(result: Result, target: OutputStream, progress: (Long, Long) -> Unit, waitMs: Long = 10 * 60_000L, offset: Long = 0L) = withContext(Dispatchers.IO) {
        val current = connection ?: throw IOException("Not connected")
        val offer = CompletableDeferred<Pair<Long, Long>>()
        val refused = CompletableDeferred<String>()
        val k = key(result.username, result.path)
        current.offers[k] = offer
        current.refusals[k] = refused
        var control: Socket? = null
        try {
            val (ip, port) = address(current, result.username)
            val peer = current.peer().also { control = it }
            peer.connect(InetSocketAddress(ip, port), 12_000)
            peer.getOutputStream().apply {
                write(Out.init(1) { str(current.username); str("P"); u32(0) })
                write(Out.message(43) { str(result.path) })
                flush()
            }
            scope.launch {
                try { servePeer(current, result.username, peer) } finally { current.closePeer(peer) }
            }
            val accepted: Pair<Long, Long>? = withTimeoutOrNull(waitMs) {
                kotlinx.coroutines.selects.select<Pair<Long, Long>?> { offer.onAwait { it }; refused.onAwait { reason -> throw IOException(reason) } }
            }
            val (token, size) = accepted ?: throw IOException("The peer didn't start the upload in time")
            val data = awaitFile(current, result.username, token)
            try {
                // A blocked socket read won't see coroutine cancellation until the socket closes.
                kotlinx.coroutines.coroutineScope {
                    val closer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try { kotlinx.coroutines.awaitCancellation() }
                        finally { current.closePeer(data) }
                    }
                    try {
                        if (size != result.size || offset !in 0 until size) throw IOException("The peer's file size changed")
                        data.soTimeout = 30_000
                        data.getOutputStream().apply { write(Out().u64(offset).toByteArray()); flush() }
                        val input = data.getInputStream()
                        val buffer = ByteArray(64 * 1024)
                        var got = offset
                        while (got < size) {
                            currentCoroutineContext().ensureActive()
                            if (ua.olex.music.playback.AudioLoadPriority.isBuffering) {
                                // Bound each yield so a slow audio connection cannot abandon an active peer transfer.
                                withTimeoutOrNull(2_000) { ua.olex.music.playback.AudioLoadPriority.awaitAudioReady() }
                            }
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - got).toInt())
                            if (n <= 0) break
                            target.write(buffer, 0, n)
                            got += n
                            progress(got, size)
                        }
                        if (got < size) throw IOException("The transfer stopped at ${got * 100 / size.coerceAtLeast(1)}%")
                    } finally { closer.cancel() }
                }
            } finally {
                current.closePeer(data)
            }
        } finally {
            current.offers.remove(k, offer)
            current.refusals.remove(k, refused)
            control?.let(current::closePeer)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitFile(current: Connection, user: String, token: Long): Socket {
        val key = user to token
        val waiting = current.files.getOrPut(key) { CompletableDeferred() }
        var delivered: Socket? = null
        try {
            delivered = withTimeoutOrNull(120_000) { waiting.await() }
            return delivered ?: throw IOException("The peer didn't connect to send the file")
        } finally {
            current.files.remove(key, waiting)
            waiting.cancel()
            if (delivered == null && waiting.isCompleted && !waiting.isCancelled) current.closePeer(waiting.getCompleted())
        }
    }

    private suspend fun address(current: Connection, user: String): Pair<String, Int> {
        val waiting = CompletableDeferred<Pair<String, Int>>().also { current.addresses[user] = it }
        try {
            withContext(Dispatchers.IO) { send(current, Out.message(3) { str(user) }) }
            val (ip, port) = withTimeoutOrNull(15_000) { waiting.await() } ?: throw IOException("$user is not reachable")
            if (port == 0 || ip == "0.0.0.0") throw IOException("$user is offline")
            return ip to port
        } finally {
            current.addresses.remove(user, waiting)
        }
    }

    private fun key(user: String, path: String) = "$user\u0000$path"

    companion object {
        private const val MAX_SEARCH_BYTES = 8 * 1024 * 1024
        private const val MAX_RESULTS = 5_000
        const val HOST = "server.slsknet.org"
        const val PORT = 2242
        /** Nicotine+'s version numbers, which the server expects from clients built on its protocol notes. */
        const val MAJOR = 160
        const val MINOR = 3
        /** Announced so peers try us directly first and then fall back to asking the server. */
        const val LISTEN_PORT = 2234

        /** A FileSearchResponse body, already inflated: the search token and its files with their attributes. */
        fun parseResults(bytes: ByteArray): Pair<Long, List<Result>>? = runCatching {
            val m = In(bytes)
            val user = m.str(); val token = m.u32(); val count = m.u32().toInt()
            require(count in 0..MAX_RESULTS) { "Too many search results" }
            data class Raw(val path: String, val size: Long, val attrs: Map<Int, Int>)
            fun file(): Raw {
                m.u8(); val path = m.str(); val size = m.u64(); m.str()
                val attrCount = m.u32().toInt()
                require(attrCount in 0..64) { "Too many file attributes" }
                val attrs = HashMap<Int, Int>()
                repeat(attrCount) { attrs[m.u32().toInt()] = m.u32().toInt() }
                return Raw(path, size, attrs)
            }
            val raws = List(count) { file() }
            val slot = m.bool(); val speed = m.u32(); val queue = m.u32()
            token to raws.filter { it.size > 0 }.map { r ->
                Result(user, r.path, r.size, slot, speed, queue, r.attrs[0], r.attrs[1], r.attrs[4], r.attrs[5])
            }
        }.getOrNull()
    }
}
