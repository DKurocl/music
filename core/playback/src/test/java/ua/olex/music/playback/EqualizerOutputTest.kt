package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import ua.olex.music.dsp.*

@OptIn(UnstableApi::class)
class EqualizerOutputTest {
    private fun floats() = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply { repeat(4) { putFloat(0.25f) }; flip() }

    @Test fun partialWritesAdvanceTheOriginalByExactlyTheConsumedBytesAndKeepTimestamps() {
        var first: ByteBuffer? = null
        var writes = 0
        val sink = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    val output = args[0] as ByteBuffer
                    if (first == null) first = output else assertSame(first, output)
                    assertEquals(1, args[1]); assertEquals(42_000L, args[2])
                    output.position((output.position() + 8).coerceAtMost(output.limit()))
                    writes++; !output.hasRemaining()
                }
                else -> error("Unexpected call ${method.name}")
            }
        } as AudioOutput
        val wrapper = EqualizerAudioOutput(sink, PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { EqualizerSettings(true, -6.0) })
        val input = floats()
        assertFalse(wrapper.write(input, 1, 42_000))
        assertEquals(8, input.position())
        assertTrue(wrapper.write(input, 1, 42_000))
        assertEquals(16, input.position())
        assertEquals(2, writes)
        assertEquals(0.25f, input.getFloat(0))
    }
    @Test fun bypassHandsTheOriginalBufferToThePlatform() {
        val input = floats()
        val sink = Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
            require(method.name == "write")
            assertSame(input, args[0]); input.position(input.limit()); true
        } as AudioOutput
        assertTrue(EqualizerAudioOutput(sink, PcmEqualizer(PcmEncoding.FLOAT32, 48_000, 2) { EqualizerSettings() }).write(input, 1, 0))
        assertEquals(input.limit(), input.position())
    }
}
