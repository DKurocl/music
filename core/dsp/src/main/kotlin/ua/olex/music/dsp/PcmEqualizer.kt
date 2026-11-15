package ua.olex.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodings the output adapter can preserve without introducing a conversion or changing frame sizes. */
enum class PcmEncoding(val bytesPerSample: Int) { INT16(2), FLOAT32(4) }

/**
 * Read-only input, reusable direct/native output, and stable partial-write identity. Call consumed only
 * after the returned buffer is fully written. Flush discards both pending output and filter state.
 */
class PcmEqualizer(private val encoding: PcmEncoding, private val sampleRateHz: Int, private val channels: Int,
    private val settings: () -> EqualizerSettings) {
    private var source: ByteBuffer? = null
    private var pending: ByteBuffer? = null
    private var storage = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var currentSettings = EqualizerSettings()
    private var current = EqualizerEngine(currentSettings, sampleRateHz, channels)
    private var previous: EqualizerEngine? = null
    private val fadeFrames = (sampleRateHz / 100).coerceAtLeast(1)
    private var fadeAt = fadeFrames
    private var frame = FloatArray(0)

    fun render(input: ByteBuffer): ByteBuffer {
        pending?.let { require(source === input) { "Previous PCM buffer not consumed" }; return it }
        val requested = settings()
        if (!requested.active && !currentSettings.active && previous == null) { currentSettings = requested; return input }
        if (requested != currentSettings && previous == null) {
            previous = current
            currentSettings = requested
            current = EqualizerEngine(requested, sampleRateHz, channels)
            fadeAt = 0
        }
        if (!currentSettings.active && previous == null) return input
        val bytes = input.remaining()
        require(bytes % (channels * encoding.bytesPerSample) == 0) { "Incomplete PCM frame" }
        if (storage.capacity() < bytes) storage = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        storage.clear(); storage.limit(bytes)
        val read = input.duplicate().order(ByteOrder.nativeOrder())
        if (frame.size != channels) frame = FloatArray(channels)
        val mixChannels = channels == 2 && (!currentSettings.channels.neutral || previous?.settings?.channels?.neutral == false)
        while (read.hasRemaining()) {
            val mix = if (previous != null) (fadeAt + 1).toDouble() / fadeFrames else 1.0
            for (channel in 0 until channels) {
                val sample = when (encoding) {
                    PcmEncoding.INT16 -> read.short / 32768f
                    PcmEncoding.FLOAT32 -> read.float
                }
                val processed = current.sample(sample, channel)
                frame[channel] = previous?.let { it.sample(sample, channel) * (1 - mix) + processed * mix }?.toFloat() ?: processed
            }
            if (mixChannels) {
                // The new mix fades in with the EQ change, so toggling mono or balance never clicks.
                val before = previous?.settings?.channels
                if (before == null || before == currentSettings.channels) currentSettings.channels.apply(frame)
                else {
                    val old = frame.copyOf(); before.apply(old); currentSettings.channels.apply(frame)
                    for (c in 0 until channels) frame[c] = (old[c] * (1 - mix) + frame[c] * mix).toFloat()
                }
            }
            for (channel in 0 until channels) {
                val value = frame[channel]
                when (encoding) {
                    PcmEncoding.INT16 -> storage.putShort(kotlin.math.round(value.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                    PcmEncoding.FLOAT32 -> storage.putFloat(value)
                }
            }
            if (previous != null && ++fadeAt >= fadeFrames) previous = null
        }
        storage.flip()
        source = input
        pending = storage
        return storage
    }

    fun consumed() { require(pending?.hasRemaining() != true); source = null; pending = null }
    fun flush() {
        source = null; pending = null; previous = null; current.reset(); fadeAt = fadeFrames
    }
}
