package ua.olex.music.soulseek

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private var server: Socket? = null
    private var out: OutputStream? = null
    private var username = ""
    private var reader: Job? = null
    private val searches = ConcurrentHashMap<Long, MutableList<Result>>()
    private val addresses = ConcurrentHashMap<String, CompletableDeferred<Pair<String, Int>>>()
    /** Transfers a peer offered us (TransferRequest), by user and path, waiting for our answer. */
    private val offers = ConcurrentHashMap<String, CompletableDeferred<Pair<Long, Long>>>()
    /** File connections by transfer token, waiting to carry the data. */
    private val files = ConcurrentHashMap<Long, CompletableDeferred<Socket>>()
    private val refusals = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Logs in (a new name is registered by the server on first login with it). */
    suspend fun login(user: String, password: String) = withContext(Dispatchers.IO) {
        disconnect()
        _state.value = State.Connecting
        try {
            val socket = Socket().apply { connect(InetSocketAddress(host, this@SoulseekClient.port), 15_000); keepAlive = true; soTimeout = 0 }
            server = socket; out = socket.getOutputStream(); username = user
            val hash = MessageDigest.getInstance("MD5").digest((user + password).toByteArray()).joinToString("") { "%02x".format(it) }
            send(Out.message(1) { str(user); str(password); u32(MAJOR); str(hash); u32(MINOR) })
            val input = socket.getInputStream()
            val (code, body) = withTimeout(20_000) { input.readFrame() } ?: throw IOException("The server closed the connection")
            require(code == 1) { "Unexpected answer to login" }
            val answer = In(body)
            if (!answer.bool()) {
                val reason = answer.str()
                throw IOException(when (reason) { "INVALIDPASS" -> "Wrong password for this name"; "INVALIDUSERNAME" -> "This name can't be used"; else -> reason })
            }
            // Online, sharing nothing, no distributed parent (searches are not relayed through us).
            send(Out.message(2) { u32(LISTEN_PORT) })
            send(Out.message(28) { u32(2) })
            send(Out.message(35) { u32(0); u32(0) })
            send(Out.message(71) { bool(true) })
            _state.value = State.Online(user)
            reader = scope.launch { serve(input) }
        } catch (e: Exception) {
            disconnect(); _state.value = State.Failed(e.message ?: "Couldn't reach Soulseek")
            throw e
        }
    }

    fun disconnect() {
        reader?.cancel(); reader = null
        runCatching { server?.close() }; server = null; out = null
        if (_state.value is State.Online || _state.value is State.Connecting) _state.value = State.Offline
    }

    @Synchronized private fun send(bytes: ByteArray) { (out ?: throw IOException("Not connected")).apply { write(bytes); flush() } }

    private suspend fun serve(input: InputStream) {
        try {
            while (true) {
                val (code, body) = input.readFrame() ?: break
                val m = In(body)
                when (code) {
                    3 -> { // GetPeerAddress
                        val user = m.str(); val ip = ipv4(m.u32()); val port = m.u32().toInt()
                        addresses.remove(user)?.complete(ip to port)
                    }
                    18 -> { // ConnectToPeer: a peer asks us to connect to it
                        val user = m.str(); val type = m.str(); val ip = ipv4(m.u32()); val port = m.u32().toInt(); val token = m.u32()
                        scope.launch { pierce(user, type, ip, port, token) }
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) { }
        _state.value = State.Failed("Disconnected from Soulseek")
    }

    /** Connects to a peer that asked for it and greets it with PierceFireWall, then serves the connection. */
    private suspend fun pierce(user: String, type: String, ip: String, port: Int, token: Long) {
        val socket = runCatching { Socket().apply { connect(InetSocketAddress(ip, port), 12_000) } }.getOrNull() ?: return
        runCatching { socket.getOutputStream().apply { write(Out.init(0) { u32(token) }); flush() } }.onFailure { socket.close(); return }
        when (type) {
            "P" -> servePeer(user, socket)
            "F" -> fileConnection(socket)
            else -> socket.close()
        }
    }

    /** A file connection: the uploader names the transfer first; it is handed to the download that waits for it. */
    private suspend fun fileConnection(socket: Socket) {
        val token = runCatching { withTimeout(30_000) { socket.getInputStream().readExactly(4) } }.getOrNull()?.let { In(it).u32() }
        val waiting = token?.let { files[it] }
        if (waiting == null || waiting.isCompleted) socket.close() else waiting.complete(socket)
    }

    /** Reads peer messages on a P connection: search results and the transfer handshake. */
    private suspend fun servePeer(user: String, socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            while (true) {
                val (code, body) = input.readFrame() ?: break
                when (code) {
                    9 -> parseResults(InflaterInputStream(body.inputStream()).readBytes())?.let { (token, results) -> searches[token]?.addAll(results) }
                    40 -> { // TransferRequest
                        val m = In(body); val direction = m.u32(); val token = m.u32(); val path = m.str()
                        val size = if (direction == 1L && m.remaining >= 8) m.u64() else 0L
                        val waiting = offers.remove(key(user, path))
                        if (direction == 1L && waiting != null) {
                            // Ready for the file connection before saying yes: a quick peer may open it at once.
                            files.getOrPut(token) { CompletableDeferred() }
                            socket.getOutputStream().apply { write(Out.message(41) { u32(token); bool(true) }); flush() }
                            waiting.complete(token to size)
                        } else socket.getOutputStream().apply { write(Out.message(41) { u32(token); bool(false); str("Cancelled") }); flush() }
                    }
                    46 -> { val path = In(body).str(); refusals.remove(key(user, path))?.complete("The upload failed") }
                    50 -> { val m = In(body); val path = m.str(); refusals.remove(key(user, path))?.complete(m.str()) }
                    else -> Unit
                }
            }
        } catch (_: Exception) { } finally { runCatching { socket.close() } }
    }

    /** Searches the network and returns what peers offered within [seconds]. */
    suspend fun search(query: String, seconds: Int = 8): List<Result> {
        val token = Random.nextLong(1, 0x7FFFFFFF)
        val found = java.util.Collections.synchronizedList(ArrayList<Result>())
        searches[token] = found
        withContext(Dispatchers.IO) { send(Out.message(26) { u32(token); str(query) }) }
        delay(seconds * 1000L)
        searches.remove(token)
        return synchronized(found) { found.toList() }
    }

    /**
     * Downloads one file to [target], reporting progress. The peer is asked to queue the upload;
     * when its turn comes it offers the transfer, we accept, and it sends the file over a new
     * connection.
     */
    suspend fun download(result: Result, target: OutputStream, progress: (Long, Long) -> Unit, waitMs: Long = 10 * 60_000L) = withContext(Dispatchers.IO) {
        val offer = CompletableDeferred<Pair<Long, Long>>()
        val refused = CompletableDeferred<String>()
        val k = key(result.username, result.path)
        offers[k] = offer; refusals[k] = refused
        try {
            val (ip, port) = address(result.username)
            val peer = Socket().apply { connect(InetSocketAddress(ip, port), 12_000) }
            peer.getOutputStream().apply {
                write(Out.init(1) { str(username); str("P"); u32(0) })
                write(Out.message(43) { str(result.path) })
                flush()
            }
            scope.launch { servePeer(result.username, peer) }
            val accepted: Pair<Long, Long>? = withTimeoutOrNull(waitMs) {
                kotlinx.coroutines.selects.select<Pair<Long, Long>?> { offer.onAwait { it }; refused.onAwait { reason -> throw IOException(reason) } }
            }
            val (token, size) = accepted ?: throw IOException("The peer didn't start the upload in time")
            val fileSocket = files.getOrPut(token) { CompletableDeferred() }
            val data = withTimeoutOrNull(120_000) { fileSocket.await() }.also { files.remove(token) } ?: throw IOException("The peer didn't connect to send the file")
            data.use { s ->
                s.getOutputStream().apply { write(Out().u64(0).toByteArray()); flush() }
                val input = s.getInputStream(); val buffer = ByteArray(64 * 1024); var got = 0L
                while (got < size) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - got).toInt())
                    if (n < 0) break
                    target.write(buffer, 0, n); got += n; progress(got, size)
                }
                if (got < size) throw IOException("The transfer stopped at ${got * 100 / size.coerceAtLeast(1)}%")
            }
        } finally { offers.remove(k); refusals.remove(k) }
    }

    private suspend fun address(user: String): Pair<String, Int> {
        val waiting = CompletableDeferred<Pair<String, Int>>().also { addresses[user] = it }
        withContext(Dispatchers.IO) { send(Out.message(3) { str(user) }) }
        val (ip, port) = withTimeoutOrNull(15_000) { waiting.await() } ?: throw IOException("$user is not reachable")
        if (port == 0 || ip == "0.0.0.0") throw IOException("$user is offline")
        return ip to port
    }

    private fun key(user: String, path: String) = "$user\u0000$path"

    companion object {
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
            data class Raw(val path: String, val size: Long, val attrs: Map<Int, Int>)
            fun file(): Raw {
                m.u8(); val path = m.str(); val size = m.u64(); m.str()
                val attrs = HashMap<Int, Int>(); repeat(m.u32().toInt()) { attrs[m.u32().toInt()] = m.u32().toInt() }
                return Raw(path, size, attrs)
            }
            val raws = (0 until count.coerceAtMost(5_000)).map { file() }
            val slot = m.bool(); val speed = m.u32(); val queue = m.u32()
            token to raws.map { r ->
                Result(user, r.path, r.size, slot, speed, queue, r.attrs[0], r.attrs[1], r.attrs[4], r.attrs[5])
            }
        }.getOrNull()
    }
}
