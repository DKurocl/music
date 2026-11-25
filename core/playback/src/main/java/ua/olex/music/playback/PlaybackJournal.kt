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
    private val pending = ArrayDeque<Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    init {
        scope.launch {
            try {
                for (signal in wake) {
                    while (true) {
                        val request = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                        when (request) {
                            is Request.Write -> try { store.write(request.state) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                Log.w("MusicPlayback", "Could not save playback: ${failure.javaClass.simpleName}")
                            }
                            is Request.Read -> if (request.result.isActive) {
                                try { request.result.complete(store.read()) }
                                catch (cancelled: CancellationException) { request.result.cancel(cancelled); throw cancelled }
                                catch (failure: Exception) { request.result.completeExceptionally(failure) }
                            }
                        }
                    }
                }
            } finally { scope.cancel() }
        }
    }

    private fun post(request: Request): Boolean = synchronized(pending) {
        if (closed) return@synchronized false
        // Coalesce before enqueueing so a stalled disk cannot accumulate whole queue snapshots.
        // A Read remains a barrier and always sees the latest Write before it.
        if (request is Request.Write && pending.lastOrNull() is Request.Write) pending.removeLast()
        pending.addLast(request)
        wake.trySend(Unit)
        true
    }

    fun write(state: PlaybackCheckpoint) { post(Request.Write(state)) }

    suspend fun read(): PlaybackCheckpoint? {
        val result = CompletableDeferred<PlaybackCheckpoint?>()
        check(post(Request.Read(result))) { "Playback journal is closed" }
        return try { result.await() } finally { if (!result.isCompleted) result.cancel() }
    }

    fun close() {
        synchronized(pending) {
            closed = true
            wake.close()
        }
    }

    companion object {
        @Volatile private var instance: PlaybackJournal? = null
        fun get(directory: File): PlaybackJournal = instance ?: synchronized(this) {
            instance ?: PlaybackJournal(PlaybackStore(directory)).also { instance = it }
        }
    }
}
