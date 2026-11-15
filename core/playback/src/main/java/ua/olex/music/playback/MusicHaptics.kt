package ua.olex.music.playback

import android.content.Context
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * "Feel the music": the phone taps along with the kick drum and the bass, as Pixel's ringtones
 * vibrate with their sound. Android's HapticGenerator only works for audio that carries haptic
 * channels, which songs do not, so the beat is found here, in the PCM on its way out.
 *
 * Timing is the whole point: audio is written hundreds of milliseconds before it is heard. Each
 * beat is placed at its frame in the output, and a pulse fires when the output's own position
 * (what is playing now) reaches it, so the tap lands with the sound, after seeks and between songs.
 */
object MusicHaptics {
    enum class Strength(val label: String, val scale: Float) { LIGHT("Light", 0.45f), MEDIUM("Medium", 0.75f), STRONG("Strong", 1f) }

    private const val PREFS = "music_haptics"
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
    fun strength(context: Context) = Strength.entries.getOrNull(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("strength", 1)) ?: Strength.MEDIUM
    fun set(context: Context, on: Boolean, strength: Strength) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).putInt("strength", strength.ordinal).apply()
    }
}

/**
 * Finds kicks and bass hits in PCM: mono, low-passed below about 120 Hz, energy in 10 ms windows; a
 * hit is a jump of the fast envelope well above the slow one, loud next to the recent peak (so the
 * song's loudness does not matter), at most one every 90 ms.
 */
internal class BeatFinder(private val sampleRate: Int) {
    data class Beat(val frame: Long, val strength: Float, val sharp: Boolean)

    private val alpha = (2 * Math.PI * 120.0 / sampleRate).let { (it / (it + 1)).toFloat() }
    private val window = (sampleRate / 100).coerceAtLeast(1)
    private var low = 0f
    private var sum = 0f
    private var count = 0
    private var fast = 0f
    private var slow = 0f
    private var peak = 0.02f
    /** Frames fed since the last [reset]. */
    var frame = 0L; private set
    private var lastBeat = -1_000_000L
    // Hysteresis: after a hit the finder waits for the energy to settle before the next one, so a
    // kick's own tail (still well above the slow envelope) never counts twice.
    private var armed = true
    private var previous = 0f
    private val refractory = sampleRate * 90L / 1000

    fun reset() { low = 0f; sum = 0f; count = 0; fast = 0f; slow = 0f; peak = 0.02f; frame = 0; lastBeat = -1_000_000L; armed = true; previous = 0f }

    /** Feeds mono samples (-1..1); returns the beats found, by frame since the last [reset]. */
    fun feed(samples: FloatArray, n: Int, out: MutableList<Beat>) {
        for (i in 0 until n) {
            low += alpha * (samples[i] - low)
            sum += low * low; count++; frame++
            if (count < window) continue
            val rms = kotlin.math.sqrt(sum / count); sum = 0f; count = 0
            fast = if (rms > fast) fast * 0.45f + rms * 0.55f else fast * 0.85f + rms * 0.15f
            slow = slow * 0.96f + rms * 0.04f
            peak = maxOf(fast, peak * 0.9995f).coerceAtLeast(0.02f)
            val norm = (fast / peak).coerceIn(0f, 1f)
            val ratio = fast / maxOf(slow, 0.004f)
            val rising = fast > previous
            previous = fast
            if (!armed && ratio < 1.1f) armed = true
            if (armed && rising && fast > 0.006f && ratio > 1.35f && norm > 0.45f && frame - lastBeat >= refractory) {
                lastBeat = frame; armed = false
                out += Beat(frame - window / 2, norm, ratio > 1.6f)
            }
        }
    }
}

/**
 * The output wrapper: analyses what is written (exactly the part the output took) and taps when the
 * output plays it. Wraps the equalizer's output, so it sees every song, whatever its format.
 */
@OptIn(UnstableApi::class)
internal class MusicHapticsOutput(
    private val delegate: AudioOutput,
    private val context: Context,
    private val float: Boolean,
    private val channels: Int,
    private val sampleRate: Int,
) : AudioOutput by delegate {
    private val finder = BeatFinder(sampleRate)
    private val pending = ConcurrentLinkedDeque<Pair<Long, BeatFinder.Beat>>()
    private var mono = FloatArray(4096)
    private val found = ArrayList<BeatFinder.Beat>()
    @Volatile private var written = 0L
    @Volatile private var playing = false
    private var pump: Thread? = null
    private var on = false
    private var strength = MusicHaptics.Strength.MEDIUM
    private var checked = 0L

    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    private val power = context.getSystemService(PowerManager::class.java)
    private val thud = vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) == true
    private val click = vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val now = System.currentTimeMillis()
        // Settings are read at most once a second, from the audio thread, cheaply.
        if (now - checked > 1_000) { checked = now; on = MusicHaptics.enabled(context) && power?.isPowerSaveMode != true; strength = MusicHaptics.strength(context) }
        val before = buffer.position()
        val handled = delegate.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        val bytes = buffer.position() - before
        if (bytes > 0) {
            val frameBytes = channels * if (float) 4 else 2
            val frames = bytes / frameBytes
            if (on && vibrator != null) analyse(buffer, before, frames)
            written += frames
        }
        return handled
    }

    private fun analyse(buffer: ByteBuffer, start: Int, frames: Int) {
        if (mono.size < frames) mono = FloatArray(frames)
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        view.position(start)
        for (f in 0 until frames) {
            var s = 0f
            repeat(channels) { s += if (float) view.float else view.short / 32768f }
            mono[f] = s / channels
        }
        found.clear()
        // Output frame of a beat = frames written before this buffer + its place within it.
        val base = written - finder.frame
        finder.feed(mono, frames, found)
        for (b in found) pending.add(((base + b.frame) * 1_000_000L / sampleRate) to b)
        if (found.isNotEmpty()) startPump()
    }

    /**
     * Fires pulses as the output plays their frames. It sleeps until the next beat is due (a couple
     * of wake-ups a second at 120 BPM, not hundreds), re-reading the output's position before each,
     * and ends itself when nothing is waiting.
     */
    private fun startPump() {
        if (pump?.isAlive == true) return
        pump = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val next = pending.peekFirst() ?: break
                val position = runCatching { delegate.positionUs }.getOrDefault(0L)
                val wait = next.first - position - 8_000 // the vibrator's own latency, about 8 ms
                if (wait > 1_000) {
                    // Wake a little early and look again: the position, not the clock, decides.
                    try { Thread.sleep((wait / 1_000).coerceAtMost(250) * 3 / 4 + 1) } catch (_: InterruptedException) { break }
                    continue
                }
                pending.pollFirst()
                // A beat long past (a stall, a pause) is dropped, not played late.
                if (position - next.first < 60_000 && playing) pulse(next.second)
            }
        }, "music-haptics").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
    }

    private fun pulse(beat: BeatFinder.Beat) {
        val v = vibrator ?: return
        val scale = (beat.strength * strength.scale).coerceIn(0.05f, 1f)
        val effect = when {
            beat.sharp && thud -> VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale).compose()
            click -> VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.8f).compose()
            else -> VibrationEffect.createOneShot(18, (scale * 255).toInt().coerceIn(1, 255))
        }
        runCatching { v.vibrate(effect) }
    }

    override fun play() { playing = true; delegate.play() }
    override fun pause() { playing = false; pending.clear(); delegate.pause() }
    override fun flush() { pending.clear(); finder.reset(); written = 0; delegate.flush() }
    override fun stop() { playing = false; delegate.stop() }
    override fun release() { playing = false; pending.clear(); pump?.interrupt(); delegate.release() }
}
