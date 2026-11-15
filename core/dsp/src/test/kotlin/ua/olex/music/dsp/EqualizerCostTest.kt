package ua.olex.music.dsp

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the always-on equalizer costs (the owner's WF-1000XM5 correction: ten bands, stereo, float,
 * 44.1 kHz, 4096-frame buffers as the output gets them), as a share of one core in real time.
 * Prints; the numbers are for comparing changes on the same machine, not absolute phone figures.
 */
class EqualizerCostTest {
    @Test fun cost() {
        val bands = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
            .mapIndexed { i, f -> EqualizerBand(f, if (i % 2 == 0) 3.0 else -2.5, 1.2) }
        val settings = EqualizerSettings(enabled = true, preampDb = -4.0, bands = bands)
        val eq = PcmEqualizer(PcmEncoding.FLOAT32, 44_100, 2) { settings }
        val frames = 4_096
        val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { input.putFloat(kotlin.math.sin(it * 0.01).toFloat() * 0.5f) }
        fun run(buffers: Int): Long {
            val start = System.nanoTime()
            repeat(buffers) { input.rewind(); val out = eq.render(input); out.position(out.limit()); eq.consumed() }
            return System.nanoTime() - start
        }
        run(2_000) // warm up the JIT
        val buffers = 44_100 * 60 / frames // one minute of music
        val ns = run(buffers)
        val share = ns / 60e9 * 100
        println("EQ cost: %.1f ns per frame, %.3f%% of one core in real time".format(ns.toDouble() / (buffers * frames), share))
        // The filters alone, without the buffer reading and writing around them.
        val engine = EqualizerEngine(settings, 44_100, 2)
        val samples = FloatArray(frames * 2) { kotlin.math.sin(it * 0.01).toFloat() * 0.5f }
        var sink = 0f
        fun filters(blocks: Int): Long { val t = System.nanoTime(); repeat(blocks) { for (j in samples.indices) sink += engine.sample(samples[j], j and 1) }; return System.nanoTime() - t }
        filters(2_000)
        val fns = filters(buffers)
        println("filters alone: %.1f ns per frame (%s)".format(fns.toDouble() / (buffers * frames), if (sink.isNaN()) "" else "ok"))
    }
}
