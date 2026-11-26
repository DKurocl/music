package ua.olex.music.playback

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.olex.music.model.Track

/** Measures wall time only while Media3 reports audible playback for the current queue occurrence. */
internal class PlaybackListening(private val owner: PlaybackHistoryOwner) {
    private val writes = SupervisorJob()
    private val scope = CoroutineScope(writes + Dispatchers.Main.immediate)
    private var event: Deferred<Long?>? = null
    private var started: Long? = null
    private var elapsed = 0L
    private var firstSample: Job? = null
    private var firstSampleTaken = false

    fun begin(track: Track) {
        finish()
        val timestamp = System.currentTimeMillis()
        event = scope.async { store { owner.recordPlayback(track, timestamp) } }
        resume()
    }

    fun resume() {
        if (event == null || started != null) return
        started = SystemClock.elapsedRealtime()
        if (!firstSampleTaken) {
            firstSample = scope.launch {
                delay((5_000 - elapsed).coerceAtLeast(0))
                firstSampleTaken = true
                flush()
            }
        }
    }

    fun pause() {
        val since = started ?: return
        elapsed += (SystemClock.elapsedRealtime() - since).coerceAtLeast(0)
        started = null
        firstSample?.cancel()
        firstSample = null
        flush()
    }

    fun finish(explicitEarlySkip: Boolean = false) {
        pause()
        if (explicitEarlySkip) {
            val recorded = event
            val total = elapsed
            if (recorded != null && total > 0) {
                scope.launch { recorded.await()?.let { id -> store { owner.recordExplicitEarlySkip(id, total) } } }
            }
        }
        event = null
        elapsed = 0
        firstSampleTaken = false
    }

    /** Share the active playback checkpoint tick with the queue journal. */
    fun checkpoint() { if (started != null) flush() }

    fun close() {
        finish()
        // Keep the final write alive across service shutdown, then let the scope complete.
        writes.complete()
    }

    private fun flush() {
        val recorded = event ?: return
        val total = elapsed + (started?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) } ?: 0L)
        scope.launch { recorded.await()?.let { id -> store { owner.updateListening(id, total) } } }
    }

    private suspend fun <T> store(write: suspend () -> T): T? = try {
        write()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w("MusicHistory", "Could not persist listening history: ${failure.javaClass.simpleName}")
        null
    }
}

/** A seek alone says nothing about intent. Match the exact occurrence pair of a recent Next command. */
internal class ExplicitNext {
    private data class Request(val from: String, val to: String, val atMs: Long)
    private var pending: Request? = null

    fun remember(from: String?, to: String?, atMs: Long, positionMs: Long, durationMs: Long) {
        pending = if (from != null && to != null && from != to && durationMs > 0 &&
            positionMs in 0 until minOf(30_000L, durationMs / 5)) Request(from, to, atMs) else null
    }

    fun consume(from: String?, to: String?, seek: Boolean, atMs: Long): Boolean {
        val request = pending
        clear()
        return request != null && seek && from == request.from && to == request.to &&
            atMs - request.atMs in 0..2_000L
    }

    fun clear() { pending = null }
}
