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
            assertTrue("beat $i at $ms ms", abs(ms - i * 500) <= 15)
        }
    }

    @Test fun loudnessDoesNotMatter() {
        assertEquals(beats(song(1f)).size, beats(song(0.1f)).size)
    }

    @Test fun aSteadyBassIsNotABeat() {
        val hum = FloatArray(rate * 4) { i -> (sin(2 * PI * 50 * i / rate) * 0.6).toFloat() }
        assertTrue(beats(hum).size <= 1)
    }
}
