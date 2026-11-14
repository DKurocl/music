package ua.olex.music.playback

import android.util.Log
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One process-owned writer orders service teardown, recreation and restore without concurrent AtomicFile writes. */
internal class PlaybackJournal(private val store: PlaybackStore) {
    private sealed interface Request {
        data class Write(val state: PlaybackCheckpoint) : Request
        data class Read(val result: CompletableDeferred<PlaybackCheckpoint?>) : Request
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                var pending: Request? = null
                while (true) {
                    val request = pending ?: requests.receiveCatching().getOrNull() ?: break
                    pending = null
                    when (request) {
                        is Request.Write -> {
                            var latest = request.state
                            // Coalesce a burst of timeline/position events, stopping at the next read barrier.
                            while (true) {
                                val next = requests.tryReceive().getOrNull() ?: break
                                if (next is Request.Write) latest = next.state else { pending = next; break }
                            }
                            try { store.write(latest) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { Log.w("MusicPlayback", "Could not save playback: ${failure.javaClass.simpleName}") }
                        }
                        is Request.Read -> {
                            try { request.result.complete(store.read()) }
                            catch (cancelled: CancellationException) { request.result.cancel(cancelled); throw cancelled }
                            catch (failure: Exception) { request.result.completeExceptionally(failure) }
                        }
                    }
                }
            } finally { scope.cancel() }
        }
    }

    fun write(state: PlaybackCheckpoint) { requests.trySend(Request.Write(state)) }
    suspend fun read(): PlaybackCheckpoint? {
        val result = CompletableDeferred<PlaybackCheckpoint?>()
        requests.send(Request.Read(result))
        return result.await()
    }
    fun close() { requests.close() }

    companion object {
        @Volatile private var instance: PlaybackJournal? = null
        fun get(directory: File): PlaybackJournal = instance ?: synchronized(this) {
            instance ?: PlaybackJournal(PlaybackStore(directory)).also { instance = it }
        }
    }
}
