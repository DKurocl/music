package ua.olex.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmEqualizerTest {
    private fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply {
        values.forEach { putFloat(it) }; flip()
    }
    @Test fun neutralBypassReturnsTheSameBufferWithoutChangingIt() {
        val input = floats(0.25f, -0.5f)
        val processor = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { EqualizerSettings() }
        assertSame(input, processor.render(input))
        assertEquals(0, input.position())
    }
    @Test fun partialWritesReuseTheOutputAndNeverProcessTheInputTwice() {
        val input = floats(0.5f, 0.25f, -0.5f, -0.25f)
        val processor = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { EqualizerSettings(true, -6.0) }
        val output = processor.render(input)
        assertEquals(0, input.position())
        output.position(8); input.position(8)
        assertSame(output, processor.render(input))
        assertEquals(8, output.position())
        output.position(output.limit()); input.position(input.limit()); processor.consumed()
        assertEquals(0.5f, input.getFloat(0))
    }
    @Test fun flushReleasesAnUnconsumedBufferAndAllFilterState() {
        val processor = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 1) { EqualizerSettings(true, -6.0) }
        processor.render(floats(1f))
        processor.flush()
        assertEquals(0f, processor.render(floats(0f)).getFloat(0))
    }
    @Test fun attenuationSettlesWithoutChangingPrecisionOrFrameCount() {
        val input = ByteBuffer.allocateDirect(4 * 960).order(ByteOrder.nativeOrder()).apply { repeat(960) { putFloat(0.5f) }; flip() }
        val processor = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 1) { EqualizerSettings(true, -6.0) }
        val output = processor.render(input)
        assertEquals(input.remaining(), output.remaining())
        assertEquals(0.250594f, output.getFloat(output.limit() - 4), 0.00001f)
        assertTrue(output.isDirect)
        assertEquals(ByteOrder.nativeOrder(), output.order())
    }
    @Test fun int16PreservesFrameSizeAndSettledAttenuation() {
        val input = ByteBuffer.allocateDirect(960 * 2).order(ByteOrder.nativeOrder()).apply { repeat(960) { putShort(16_384) }; flip() }
        val processor = PcmEqualizer(PcmEncoding.INT16, 48_000, 1) { EqualizerSettings(true, -6.0) }
        val output = processor.render(input)
        assertEquals(input.remaining(), output.remaining())
        assertEquals(8_211, output.getShort(output.limit() - 2).toInt())
    }
    @Test fun disabledNonflatSettingsStillBypassWithoutTouchingBytes() {
        val input = floats(0.5f, -0f)
        val processor = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { SonyCorrection.settings.copy(enabled = false) }
        assertSame(input, processor.render(input))
    }
    @Test fun incompleteInterleavedFrameIsRejected() {
        val processor = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { EqualizerSettings(true) }
        assertThrows(IllegalArgumentException::class.java) { processor.render(floats(0.5f)) }
    }
}

class ChannelMixTest {
    @org.junit.Test fun neutralLeavesTheFrameAlone() {
        val f = floatArrayOf(0.25f, -0.5f); ChannelMix().apply(f)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0.25f, -0.5f), f, 0f)
    }
    @org.junit.Test fun monoSwapAndBalance() {
        val f = floatArrayOf(1f, 0f); ChannelMix(mono = true).apply(f)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0.5f, 0.5f), f, 1e-6f)
        val s = floatArrayOf(1f, 0.2f); ChannelMix(swap = true).apply(s)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0.2f, 1f), s, 1e-6f)
        val l = floatArrayOf(1f, 1f); ChannelMix(balance = -1f).apply(l)
        org.junit.Assert.assertEquals(1f, l[0], 1e-6f); org.junit.Assert.assertEquals(0f, l[1], 1e-6f)
        val r = floatArrayOf(1f, 1f); ChannelMix(balance = 0.5f).apply(r)
        org.junit.Assert.assertTrue(r[0] < 1f && r[1] == 1f)
    }
    @org.junit.Test fun monoWorksWithTheEqualizerOff() {
        val eq = PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { EqualizerSettings(channels = ChannelMix(mono = true)) }
        val input = java.nio.ByteBuffer.allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder())
        repeat(4) { input.putFloat(1f); input.putFloat(0f) }; input.flip()
        val out = eq.render(input).duplicate().order(java.nio.ByteOrder.nativeOrder())
        org.junit.Assert.assertNotSame(input, out)
        // Fading in from stereo: the left side already moves toward the mono level, the right up from silence.
        val left = out.float; val right = out.float
        org.junit.Assert.assertTrue(left < 1f && right > 0f)
    }
}
