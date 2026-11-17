package ua.olex.music.ui

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound decorative network work independently of audio resolution and its HTTP connections. */
internal object AuxiliaryLoads {
    val gate = Semaphore(2)
    suspend fun <T> run(load: suspend () -> T): T = gate.withPermit { load() }
}
