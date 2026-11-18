package ua.olex.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Lets optional network work wait behind an audible buffering or source-recovery request. */
object AudioLoadPriority {
    private val buffering = MutableStateFlow(false)

    internal fun update(waiting: Boolean) { buffering.value = waiting }

    suspend fun awaitAudioReady() {
        buffering.first { !it }
    }
}
