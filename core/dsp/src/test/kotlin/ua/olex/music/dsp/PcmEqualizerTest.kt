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
