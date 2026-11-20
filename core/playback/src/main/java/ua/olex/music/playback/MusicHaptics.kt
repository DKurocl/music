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
 * Finds the kick drum in PCM, the way a drummer's foot is felt rather than every bass note:
 *  - a band-pass on the kick's body (about 45-110 Hz, a 2nd-order Butterworth), mono;
 *  - energy in 5 ms hops; an onset is a jump of the log energy (a ratio, so loudness doesn't
 *    matter) above a threshold that follows the last half second of the song;
 *  - each candidate is judged 80 ms later, which the output can afford (it is written well ahead of
 *    being heard): a kick has dropped well below its peak by then, a bass note still rings and is
 *    left out; the stronger of two candidates within 110 ms wins;
 *  - the beat is placed at the start of the attack, not the middle of a window.
 */
internal class BeatFinder(private val sampleRate: Int) {
    data class Beat(val frame: Long, val strength: Float, val sharp: Boolean)

    // RBJ band-pass (constant 0 dB peak), centre 70 Hz, Q 0.8.
    private val b0: Float; private val b2: Float; private val a1: Float; private val a2: Float
    init {
        val w = 2 * Math.PI * 70.0 / sampleRate
        val alpha = kotlin.math.sin(w) / (2 * 0.8)
        val a0 = 1 + alpha
        b0 = (alpha / a0).toFloat(); b2 = (-alpha / a0).toFloat()
        a1 = (-2 * kotlin.math.cos(w) / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
    }
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    private val hop = (sampleRate / 200).coerceAtLeast(1)
    private var sum = 0f
    private var count = 0
    /** Frames fed since the last [reset]. */
    var frame = 0L; private set
    private var hops = 0L
    private var lastLog = -20f
    private var peakEnergy = 1e-6f
    // Recent rises of the log energy, for the adaptive threshold (half a second of hops).
    private val rises = FloatArray(100)
    private var risesFilled = 0
    // Energy per hop for the last 120 ms, to judge a candidate after the fact.
    private val history = FloatArray(32)
    // Raw energy of each 5 ms hop (for the attack's exact start), and how many make the smoothed one.
    private val raws = FloatArray(32)
    private val smooth = 5
    private var candidate: Candidate? = null
    private var lastBeat = -1_000_000L
    private val minGapHops = 22L // 110 ms
    private val judgeAfterHops = 16L // 80 ms

    private class Candidate(val hop: Long, val frame: Long, val rise: Float, var peak: Float, val floor: Float)

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f; sum = 0f; count = 0; frame = 0; hops = 0; lastLog = -20f
        peakEnergy = 1e-6f; risesFilled = 0; candidate = null; lastBeat = -1_000_000L; history.fill(0f); raws.fill(0f)
    }

    /** Feeds mono samples (-1..1); returns the beats found, by frame since the last [reset]. */
    fun feed(samples: FloatArray, n: Int, out: MutableList<Beat>) {
        for (i in 0 until n) {
            val x = samples[i]
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            sum += y * y; count++; frame++
            if (count < hop) continue
            hop(sum / count, out)
            sum = 0f; count = 0
        }
    }

    private fun hop(raw: Float, out: MutableList<Beat>) {
        val start = frame - hop
        hops++
        raws[(hops % raws.size).toInt()] = raw
        // Energy over the last 25 ms, at least a period of the lowest bass (40 Hz): a shorter window
        // ripples with the wave's own phase and reads every cycle as a hit.
        var energy = 0f
        val span = minOf(hops, smooth.toLong()).toInt()
        for (k in 0 until span) energy += raws[ring(hops - k, raws.size)]
        energy /= span
        history[(hops % history.size).toInt()] = energy
        peakEnergy = maxOf(energy, peakEnergy * 0.9993f) // the song's recent loudest, falling slowly
        val log = kotlin.math.ln(energy + 1e-9f)
        val rise = (log - lastLog).coerceAtLeast(0f)
        lastLog = log

        // Adaptive threshold: well above the typical rise of the last half second.
        var mean = 0f
        val filled = minOf(risesFilled, rises.size)
        for (k in 0 until filled) mean += rises[k]
        mean = if (filled > 0) mean / filled else 0f
        rises[(risesFilled % rises.size)] = rise; risesFilled++
        val threshold = maxOf(0.5f, mean * 2.5f)

        candidate?.let { c ->
            if (energy > c.peak) c.peak = energy
            if (hops - c.hop >= judgeAfterHops) {
                // A kick has fallen most of the way back to what was there before it 80 ms on; a bass
                // note still rings. And it stood well clear of that floor, so the beating of a
                // kick's tail against a held bass note is not a new hit (5 dB).
                val percussive = energy < c.floor + (c.peak - c.floor) * 0.45f && c.peak > c.floor * 3f
                if (percussive && c.frame - lastBeat >= minGapHops * hop) {
                    lastBeat = c.frame
                    val loud = (c.peak / peakEnergy).coerceIn(0f, 1f)
                    val strength = (0.35f + 0.65f * kotlin.math.sqrt(loud)).coerceIn(0f, 1f)
                    out += Beat(c.frame, strength, c.rise > 2f)
                }
                candidate = null
            }
        }
        // Loud enough to matter in this song, a sharp jump, and not inside the last beat's tail.
        val present = energy > peakEnergy * 0.04f
        if (present && rise > threshold && start - lastBeat >= minGapHops * hop) {
            val current = candidate
            if (current == null) floorBefore().let { floor -> candidate = Candidate(hops, attackStart(start, floor), rise, energy, floor) }
            else if (rise > current.rise && hops - current.hop <= 2) {
                // The same attack seen a hop later, steeper: keep the earlier start, the larger rise.
                candidate = Candidate(current.hop, current.frame, rise, maxOf(current.peak, energy), current.floor)
            }
        }
    }

    private fun ring(index: Long, size: Int) = (((index % size) + size) % size).toInt()

    // The quietest smoothed energy of the 40 ms before the smoothing window: the song before the hit.
    private fun floorBefore(): Float {
        var floor = Float.MAX_VALUE
        for (k in smooth..smooth + 7) floor = minOf(floor, history[ring(hops - k, history.size)])
        return floor.coerceAtLeast(1e-9f)
    }

    // The smoothed jump shows up to 25 ms late; the attack began at the first 5 ms hop of that span
    // that rose clear of the floor.
    private fun attackStart(start: Long, floor: Float): Long {
        // Clear of the floor and a real part of the hit (a hi-hat's leak through the band is neither).
        val now = raws[ring(hops, raws.size)]
        for (k in smooth - 1 downTo 0) {
            val e = raws[ring(hops - k, raws.size)]
            if (e > floor * 3f && e > now * 0.15f) return start - k * hop + hop / 2
        }
        return start
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
    // How much later the output is heard than played (headphones), set per output by ear.
    @Volatile private var outputDelayUs = 0L

    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    private val power = context.getSystemService(PowerManager::class.java)
    private val thud = vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD) == true
    private val click = vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val now = System.currentTimeMillis()
        // Settings are read at most once a second, from the audio thread, cheaply.
        if (now - checked > 1_000) {
            checked = now; on = MusicHaptics.enabled(context) && power?.isPowerSaveMode != true; strength = MusicHaptics.strength(context)
            outputDelayUs = OutputDelay.current(context) * 1_000L
        }
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
                // Due when the hit is heard (the output's delay after the player's clock), started
                // early by the motor's own lag: about 8 ms to respond, then a thud's 15 ms to swell
                // to its peak (a click's 5), so the peak lands on the hit, not after it.
                val lead = 8_000L + if (next.second.sharp && thud) 15_000L else 5_000L
                val wait = next.first + outputDelayUs - position - lead
                if (wait > 1_000) {
                    // Wake a little early and look again: the position, not the clock, decides.
                    try { Thread.sleep((wait / 1_000).coerceAtMost(250) * 3 / 4 + 1) } catch (_: InterruptedException) { break }
                    continue
                }
                pending.pollFirst()
                // A beat long past (a stall, a pause) is dropped, not played late.
                if (position - (next.first + outputDelayUs) < 60_000 && playing) pulse(next.second)
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
