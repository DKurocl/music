package ua.olex.music.ui

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound decorative network work independently of audio resolution and its HTTP connections. */
internal object AuxiliaryLoads {
    private val gate = Semaphore(2)
    suspend fun <T> run(load: suspend () -> T): T = gate.withPermit {
        ua.olex.music.playback.AudioLoadPriority.awaitAudioReady()
        load()
    }

    /** Speculation may use a free permit and drops immediately when visible work has filled both. */
    suspend fun <T> tryRun(load: suspend () -> T): T? {
        if (!gate.tryAcquire()) return null
        try {
            ua.olex.music.playback.AudioLoadPriority.awaitAudioReady()
            return load()
        } finally {
            gate.release()
        }
    }
}
