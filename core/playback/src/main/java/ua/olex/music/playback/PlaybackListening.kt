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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.olex.music.model.Track

/** Measures wall time only while Media3 reports audible playback for the current queue occurrence. */
internal class PlaybackListening(private val owner: PlaybackHistoryOwner) {
    private val writes = SupervisorJob()
    private val scope = CoroutineScope(writes + Dispatchers.Main.immediate)
    private var event: Deferred<Long?>? = null
    private var started: Long? = null
    private var elapsed = 0L
    private var checkpoint: Job? = null

    fun begin(track: Track) {
        finish()
        val timestamp = System.currentTimeMillis()
        event = scope.async { store { owner.recordPlayback(track, timestamp) } }
        resume()
    }

    fun resume() {
        if (event == null || started != null) return
        started = SystemClock.elapsedRealtime()
        checkpoint = scope.launch {
            delay(5_000)
            flush()
            while (isActive) {
                delay(60_000)
                flush()
            }
        }
    }

    fun pause() {
        val since = started ?: return
        elapsed += (SystemClock.elapsedRealtime() - since).coerceAtLeast(0)
        started = null
        checkpoint?.cancel()
        checkpoint = null
        flush()
    }

    fun finish() {
        pause()
        event = null
        elapsed = 0
    }

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
