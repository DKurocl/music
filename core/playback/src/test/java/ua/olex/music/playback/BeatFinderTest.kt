package ua.olex.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class BeatFinderTest {
    private val rate = 44_100

    /** Four seconds: a 55 Hz kick every half second (120 BPM) over constant bright hi-hat noise. */
    private fun song(gain: Float): FloatArray {
        val random = java.util.Random(7)
        return FloatArray(rate * 4) { i ->
            val t = i.toDouble() / rate
            val sinceKick = t % 0.5
            val kick = sin(2 * PI * 55 * sinceKick) * exp(-sinceKick * 18)
            val hat = (random.nextFloat() - 0.5f) * 0.3f * (if (i % 2 == 0) 1 else -1)
            ((kick * 0.8 + hat) * gain).toFloat()
        }
    }

    private fun beats(samples: FloatArray): List<BeatFinder.Beat> {
        val finder = BeatFinder(rate)
        val out = ArrayList<BeatFinder.Beat>()
        // In player-sized chunks, as the output receives them.
        samples.toList().chunked(4_096).forEach { chunk -> finder.feed(chunk.toFloatArray(), chunk.size, out) }
        return out
    }

    @Test fun findsEachKickOnTime() {
        val found = beats(song(1f))
        assertEquals(8, found.size)
        found.forEachIndexed { i, b ->
            val ms = b.frame * 1000.0 / rate
            assertTrue("beat $i at $ms ms", abs(ms - i * 500) <= 8)
        }
    }

    @Test fun loudnessDoesNotMatter() {
        assertEquals(beats(song(1f)).size, beats(song(0.1f)).size)
    }

    @Test fun aSteadyBassIsNotABeat() {
        val hum = FloatArray(rate * 4) { i -> (sin(2 * PI * 50 * i / rate) * 0.6).toFloat() }
        assertTrue(beats(hum).size <= 1)
    }

    @Test fun bassNotesWithoutDrumsAreNotBeats() {
        // A bass line: a new sustained note every half second, gently attacked, ringing on.
        val notes = doubleArrayOf(55.0, 65.4, 73.4, 49.0)
        val line = FloatArray(rate * 4) { i ->
            val t = i.toDouble() / rate
            val since = t % 0.5
            val f = notes[((t / 0.5).toInt()) % notes.size]
            (sin(2 * PI * f * t) * 0.6 * minOf(1.0, since / 0.03)).toFloat()
        }
        assertTrue(beats(line).size <= 1)
    }

    @Test fun kicksUnderABassLineStillCount() {
        val bass = FloatArray(rate * 4) { i -> (sin(2 * PI * 41.0 * i / rate) * 0.25).toFloat() }
        val mixed = song(1f).also { s -> for (i in s.indices) s[i] += bass[i] }
        assertEquals(8, beats(mixed).size)
    }
}
