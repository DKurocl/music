package ua.olex.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import org.junit.Assert.*
import org.junit.Test

class ReplayGainTest {
    private val track = ReplayGainSettings(ReplayGainMode.TRACK)
    @Test fun trackAndAlbumModesUseTheirOwnGainAndPeakPair() {
        val tags = ReplayGainTags(-6.0, 0.8, -3.0, 0.95)
        assertEquals(-6.0, 20 * log10(tags.multiplier(track)), 1e-9)
        assertEquals(-3.0, 20 * log10(tags.multiplier(track.copy(mode = ReplayGainMode.ALBUM))), 1e-9)
        assertEquals(tags.multiplier(track), tags.copy(albumGainDb = null).multiplier(track.copy(mode = ReplayGainMode.ALBUM)), 0.0)
    }
    @Test fun peakGuardIncludesPreampAndDoesNotBorrowTheOtherModesPeak() {
        val tags = ReplayGainTags(6.0, 0.9, 6.0, 0.25)
        assertEquals(1 / 0.9, tags.multiplier(track.copy(preampDb = 12.0)), 1e-9)
        assertEquals(4.0, tags.multiplier(track.copy(mode = ReplayGainMode.ALBUM, preampDb = 12.0)), 1e-9)
        assertEquals(1.0, tags.copy(albumPeak = null).multiplier(track.copy(mode = ReplayGainMode.ALBUM)), 0.0)
        assertTrue(tags.multiplier(track.copy(preventClipping = false)) > 1 / 0.9)
    }
    @Test fun untaggedOffAndMalformedDataStayNeutral() {
        assertEquals(1.0, ReplayGainTags().multiplier(track.copy(preampDb = 12.0)), 0.0)
        assertEquals(1.0, ReplayGainTags(12.0).multiplier(ReplayGainSettings()), 0.0)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1000.0)) {
            assertEquals(1.0, ReplayGainTags(invalid).multiplier(track), 0.0)

        }
        for (peak in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0))
            assertEquals(1.0, ReplayGainTags(6.0, peak).multiplier(track), 0.0)
        assertThrows(IllegalArgumentException::class.java) { ReplayGainSettings(preampDb = Double.NaN) }
    }
    @Test fun bypassKeepsIdentityUntilPartialBufferIsConsumedDespiteASettingsChange() {
        var settings = ReplayGainSettings()
        val processor = PcmReplayGain(GainPcmEncoding.INT16, 48_000, 1, ReplayGainTags(-6.0)) { settings }
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1000).putShort(2000).flip()
        assertSame(input, processor.render(input))
        input.position(2); settings = track
        assertSame(input, processor.render(input))
        input.position(4); processor.consumed()
    }
    @Test fun floatGainIsPerStreamEvenWhenANewStreamWasConfiguredBeforeOldSamplesPlay() {
        fun processor(db: Double) = PcmReplayGain(GainPcmEncoding.FLOAT32, 48_000, 1, ReplayGainTags(db)) { track }
        val inputA = floats(0.5f, -0.5f).asReadOnlyBuffer()
        val bufferedA = processor(-6.020599913279624).render(inputA)
        val bufferedB = processor(-12.041199826559248).render(floats(0.5f, -0.5f))
        assertEquals(0.25f, bufferedA.getFloat(0), 1e-7f)
        assertEquals(-0.25f, bufferedA.getFloat(4), 1e-7f)
        assertEquals(0.125f, bufferedB.getFloat(0), 1e-7f)
        assertEquals(0, inputA.position())
    }
    @Test fun allIntegerWidthsAndByteOrdersPreserveFrameSizeAndExpectedSamples() {
        for (encoding in GainPcmEncoding.entries.filter { it != GainPcmEncoding.FLOAT32 }) {
            val input = ByteBuffer.allocateDirect(encoding.bytes * 2).order(encoding.order)
            val original = when (encoding.bytes) { 1 -> 64; 2 -> 16384; 3 -> 4194304; else -> 1073741824 }
            for (value in listOf(original, -original)) {
                when (encoding.bytes) {
                    1 -> input.put((value + 128).toByte())
                    2 -> input.putShort(value.toShort())
                    3 -> if (encoding.order == ByteOrder.LITTLE_ENDIAN) { input.put(value.toByte()); input.put((value shr 8).toByte()); input.put((value shr 16).toByte()) }
                        else { input.put((value shr 16).toByte()); input.put((value shr 8).toByte()); input.put(value.toByte()) }
                    4 -> input.putInt(value)
                }
            }
            input.flip()
            val output = PcmReplayGain(encoding, 96_000, 2, ReplayGainTags(-6.020599913279624)) { track }.render(input)
            assertEquals(input.remaining(), output.remaining())
            assertEquals(0, input.position())
            val expected = input.duplicate().order(encoding.order)
            expected.clear()
            for (value in listOf(original / 2, -original / 2)) {
                when (encoding.bytes) {
                    1 -> expected.put((value + 128).toByte())
                    2 -> expected.putShort(value.toShort())
                    3 -> if (encoding.order == ByteOrder.LITTLE_ENDIAN) { expected.put(value.toByte()); expected.put((value shr 8).toByte()); expected.put((value shr 16).toByte()) }
                        else { expected.put((value shr 16).toByte()); expected.put((value shr 8).toByte()); expected.put(value.toByte()) }
                    4 -> expected.putInt(value)
                }
            }
            expected.flip()
            assertEquals(encoding.name, expected, output)
        }
    }
    @Test fun pendingStorageIsStableAndFlushDiscardsIt() {
        val processor = PcmReplayGain(GainPcmEncoding.FLOAT32, 48_000, 1, ReplayGainTags(-6.0)) { track }
        val input = floats(0.5f, 0.25f)
        val output = processor.render(input)
        assertTrue(output.isDirect)
        output.position(4); input.position(4)
        assertSame(output, processor.render(input))
        assertThrows(IllegalArgumentException::class.java) { processor.render(floats(0.1f)) }
        processor.flush()
        assertEquals(4, processor.render(floats(0.2f)).remaining())
    }
    @Test fun settingChangesRampWithEqualGainForBothChannelsAndMalformedFloatIsContained() {
        var settings = ReplayGainSettings()
        val processor = PcmReplayGain(GainPcmEncoding.FLOAT32, 8_000, 2, ReplayGainTags(-6.020599913279624)) { settings }
        val initial = floats(0.5f, 0.5f)
        processor.render(initial).position(initial.limit()); processor.consumed()
        settings = track
        val output = processor.render(floats(*FloatArray(160) { 0.5f }))
        assertTrue(output.getFloat(0) > 0.25f)
        for (frame in 0 until 80) assertEquals(output.getFloat(frame * 8), output.getFloat(frame * 8 + 4), 0f)
        assertEquals(0.25f, output.getFloat(159 * 4), 1e-7f)
        processor.flush()
        val bad = processor.render(floats(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(0f, bad.float, 0f); assertEquals(0f, bad.float, 0f)
    }
    private fun floats(vararg values: Float): ByteBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat); flip() }
}
