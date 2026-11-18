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
    private var timerFade: Job? = null
    private val enabled get() = wanted()
    /** True during the short fade before a pause: the player already reads as paused to everyone. */
    private var pausing = false
    private val wrappers = HashMap<Player.Listener, Player.Listener>()

    // A pause is answered at once: controls flip immediately while the sound fades underneath.
    override fun getPlayWhenReady(): Boolean = if (pausing) false else super.getPlayWhenReady()
    override fun isPlaying(): Boolean = if (pausing) false else super.isPlaying()

    // The fade's volume steps stay inside: without this, each 16 ms step sent the whole player
    // state to the app (and the old "playing" with it), which made pause and play feel slow.
    override fun addListener(listener: Player.Listener) {
        val wrapper = object : Player.Listener by listener {
            override fun onVolumeChanged(volume: Float) { if (fade?.isActive != true) listener.onVolumeChanged(volume) }
            override fun onEvents(player: Player, events: Player.Events) {
                if (fade?.isActive == true && events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
                listener.onEvents(player, events)
            }
        }
        wrappers[listener] = wrapper
        super.addListener(wrapper)
    }
    override fun removeListener(listener: Player.Listener) { super.removeListener(wrappers.remove(listener) ?: listener) }

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        fade?.cancel()
        pausing = false
        if (!enabled) { real.volume = 1f; super.setPlayWhenReady(playWhenReady); return }
        if (playWhenReady) {
            if (!real.playWhenReady) real.volume = 0f
            super.setPlayWhenReady(true)
            val from = real.volume
            fade = scope.launch { ramp(from, 1f, FADE_IN_MS) }
        } else if (real.isPlaying) {
            val from = real.volume
            pausing = true
            fade = scope.launch {
                try {
                    ramp(from, 0f, FADE_OUT_MS)
                    pausing = false
                    super.setPlayWhenReady(false)
                    real.volume = 1f
                } finally { pausing = false }
            }
        } else { super.setPlayWhenReady(false); real.volume = 1f }
    }

    /** The last half minute of a sleep timer: a slow fade to silence instead of a sudden stop. */
    internal fun sleepFade(durationMs: Long) {
        fade?.cancel()
        val from = real.volume
        val job = scope.launch { ramp(from, 0f, durationMs) }
        timerFade = job
        fade = job
    }

    /** Timer changes must not interrupt a manual pause or play fade. */
    internal fun cancelSleepFade() {
        val owned = timerFade ?: return
        timerFade = null
        if (fade !== owned) return
        owned.cancel()
        fade = null
        real.volume = 1f
    }

    // Runs inside the fade's own job, so cancelling the fade (a new play or pause, a cancelled sleep
    // timer) stops the volume at once; a separate job here used to keep lowering it after a cancel.
    private suspend fun ramp(from: Float, to: Float, ms: Long) {
        val steps = (ms / 16).coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // Equal-power curve: the loudness change sounds even, not front-loaded.
            real.volume = from + (to - from) * (if (to > from) kotlin.math.sin(t * Math.PI / 2).toFloat() else 1 - kotlin.math.cos(t * Math.PI / 2).toFloat())
            delay(i * ms / steps - (i - 1) * ms / steps)
        }
        real.volume = to
    }

    companion object {
        const val FADE_OUT_MS = 150L; const val FADE_IN_MS = 250L
        const val PREFS = "playback"; const val KEY = "fades"
    }
}
