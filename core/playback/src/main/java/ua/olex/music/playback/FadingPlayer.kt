package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pause and play without a click: the volume glides down for a fifth of a second before pausing
 * and back up after playing, as Google's players do. Only the player's own volume moves; the file,
 * EQ and ReplayGain are untouched, and seeking or skipping is never delayed.
 */
@OptIn(UnstableApi::class)
class FadingPlayer internal constructor(private val real: Player, private val scope: CoroutineScope, private val wanted: () -> Boolean = { true }) : ForwardingPlayer(real) {
    private var fade: Job? = null
    private val enabled get() = wanted()

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        fade?.cancel()
        if (!enabled) { real.volume = 1f; super.setPlayWhenReady(playWhenReady); return }
        if (playWhenReady) {
            if (!real.playWhenReady) real.volume = 0f
            super.setPlayWhenReady(true)
            fade = ramp(real.volume, 1f, FADE_IN_MS)
        } else if (real.isPlaying) {
            val from = real.volume
            fade = scope.launch {
                ramp(from, 0f, FADE_OUT_MS).join()
                super.setPlayWhenReady(false)
                real.volume = 1f
            }
        } else { super.setPlayWhenReady(false); real.volume = 1f }
    }

    /** The last half minute of a sleep timer: a slow fade to silence instead of a sudden stop. */
    internal fun sleepFade(durationMs: Long, then: () -> Unit): Job {
        fade?.cancel()
        return scope.launch { ramp(real.volume, 0f, durationMs).join(); then(); real.volume = 1f }.also { fade = it }
    }

    /** A changed or cancelled sleep timer stops its fade and restores full volume. */
    internal fun cancelFade() { fade?.cancel(); fade = null; real.volume = 1f }

    private fun ramp(from: Float, to: Float, ms: Long): Job = scope.launch {
        val steps = (ms / 16).coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Equal-power curve: the loudness change sounds even, not front-loaded.
            real.volume = from + (to - from) * (if (to > from) kotlin.math.sin(t * Math.PI / 2).toFloat() else 1 - kotlin.math.cos(t * Math.PI / 2).toFloat())
            delay(ms / steps)
        }
        real.volume = to
    }

    companion object {
        const val FADE_OUT_MS = 200L; const val FADE_IN_MS = 300L
        const val PREFS = "playback"; const val KEY = "fades"
    }
}
