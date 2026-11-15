package ua.olex.music.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns 1-bit DSD into 32-bit float PCM at 88.2 kHz (DSD64, 128 and 256 alike), the way DSD is
 * played when a DAC cannot take it natively. A windowed-sinc low-pass (cut at 30 kHz, where DSD's
 * shaped noise starts to rise) is applied with per-byte lookup tables, then every n-th value kept.
 * One instance per stream; [reset] after a seek.
 */
class DsdToPcm(private val channels: Int, dsdRateHz: Int, private val lsbFirst: Boolean = true) {
    /** DSD bytes per output sample per channel: 4 for DSD64. */
    private val step = (dsdRateHz / 8 / OUTPUT_RATE).coerceAtLeast(1)
    /** Filter window in bytes (8 taps per byte). */
    private val window = step * 8
    private val tables: Array<FloatArray>
    private val history = Array(channels) { ByteArray(window) { 0x69 } } // 0x69: DSD silence pattern
    private val at = IntArray(channels)
    private val pending = IntArray(channels)

    init {
        require(channels in 1..8 && dsdRateHz >= 2_822_400)
        val taps = window * 8
        val cutoff = 30_000.0 / dsdRateHz
        val h = DoubleArray(taps) { n ->
            val m = n - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            val blackman = 0.42 - 0.5 * cos(2 * PI * n / (taps - 1)) + 0.08 * cos(4 * PI * n / (taps - 1))
            sinc * blackman
        }
        val sum = h.sum()
        tables = Array(window) { k ->
            FloatArray(256) { byte ->
                var acc = 0.0
                for (b in 0 until 8) {
                    val bit = if (lsbFirst) (byte shr b) and 1 else (byte shr (7 - b)) and 1
                    acc += h[k * 8 + b] / sum * (if (bit == 1) 1.0 else -1.0)
                }
                acc.toFloat()
            }
        }
    }

    fun reset() {
        for (c in 0 until channels) { history[c].fill(0x69); at[c] = 0; pending[c] = 0 }
    }

    /**
     * Converts [bytes] DSD bytes of one channel starting at [offset] in [input]; writes each PCM
     * sample to out[outOffset + i * channels]. Returns how many samples were written.
     */
    fun channel(c: Int, input: ByteArray, offset: Int, bytes: Int, out: FloatArray, outOffset: Int): Int {
        val ring = history[c]
        var pos = at[c]
        var count = pending[c]
        var written = 0
        for (i in 0 until bytes) {
            ring[pos] = input[offset + i]
            pos = (pos + 1) % window
            if (++count == step) {
                count = 0
                var acc = 0f
                var k = 0
                var p = pos // oldest byte first
                while (k < window) {
                    acc += tables[k][ring[p].toInt() and 0xFF]
                    p++; if (p == window) p = 0
                    k++
                }
                out[outOffset + written * channels] = acc
                written++
            }
        }
        at[c] = pos; pending[c] = count
        return written
    }

    companion object { const val OUTPUT_RATE = 88_200 }
}
