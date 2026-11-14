package ua.olex.music.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

/** Decoder PCM encodings, including packed 24-bit. Output keeps the exact format and byte order. */
enum class GainPcmEncoding(val bytes: Int, val order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {
    UINT8(1), INT16(2), INT24(3), INT32(4), FLOAT32(4),
    INT16_BE(2, ByteOrder.BIG_ENDIAN), INT24_BE(3, ByteOrder.BIG_ENDIAN), INT32_BE(4, ByteOrder.BIG_ENDIAN)
}

/**
 * Applies one stream's tags before Media3 buffers or trims its PCM. A partial write keeps buffer
 * identity and gain fixed, including bypass. Settings changes ramp for 10 ms; stream changes do not.
 */
class PcmReplayGain(private val encoding: GainPcmEncoding, private val sampleRateHz: Int,
    private val channels: Int, private val tags: ReplayGainTags, private val settings: () -> ReplayGainSettings) {
    private var storage = ByteBuffer.allocateDirect(0)
    private var source: ByteBuffer? = null
    private var pending: ByteBuffer? = null
    private var applied: ReplayGainSettings? = null
    private var gain = 1.0
    private var target = 1.0
    private var step = 0.0
    private var rampRemaining = 0
    init { require(sampleRateHz > 0 && channels in 1..32) }

    fun render(input: ByteBuffer): ByteBuffer {
        pending?.let { require(source === input) { "Previous gain buffer not consumed" }; return it }
        val requested = settings()
        if (requested != applied) {
            target = tags.multiplier(requested)
            if (applied == null) gain = target
            else {
                rampRemaining = (sampleRateHz / 100).coerceAtLeast(1)
                step = (target - gain) / rampRemaining
            }
            applied = requested
        }
        val bytes = input.remaining()
        require(bytes % (encoding.bytes * channels) == 0) { "Incomplete PCM frame" }
        source = input
        if (gain == 1.0 && target == 1.0) { pending = input; return input }
        if (storage.capacity() < bytes) storage = ByteBuffer.allocateDirect(bytes)
        storage.clear(); storage.limit(bytes); storage.order(encoding.order)
        val read = input.duplicate().order(encoding.order)
        while (read.hasRemaining()) {
            if (rampRemaining > 0) { gain += step; if (--rampRemaining == 0) gain = target }
            repeat(channels) {
                val sample = readSample(read)
                val value = if (sample.isFinite()) (sample * gain).coerceIn(-1.0, 1.0) else 0.0
                writeSample(storage, value)
            }
        }
        storage.flip(); pending = storage
        return storage
    }

    fun consumed() { require(pending?.hasRemaining() != true); source = null; pending = null }
    fun flush() { source = null; pending = null; applied = null; rampRemaining = 0; gain = 1.0; target = 1.0 }

    private fun readSample(read: ByteBuffer): Double = when (encoding) {
        GainPcmEncoding.FLOAT32 -> read.float.toDouble()
        GainPcmEncoding.UINT8 -> ((read.get().toInt() and 255) - 128) / 128.0
        GainPcmEncoding.INT16, GainPcmEncoding.INT16_BE -> read.short / 32768.0
        GainPcmEncoding.INT32, GainPcmEncoding.INT32_BE -> read.int / 2147483648.0
        GainPcmEncoding.INT24, GainPcmEncoding.INT24_BE -> {
            val a = read.get().toInt() and 255; val b = read.get().toInt() and 255; val c = read.get().toInt() and 255
            val raw = if (encoding.order == ByteOrder.LITTLE_ENDIAN) a or (b shl 8) or (c shl 16) else c or (b shl 8) or (a shl 16)
            ((raw shl 8) shr 8) / 8388608.0
        }
    }
    private fun writeSample(out: ByteBuffer, value: Double) {
        fun integer(scale: Double) = round(value * scale).toLong().coerceIn(-scale.toLong(), scale.toLong() - 1).toInt()
        when (encoding) {
            GainPcmEncoding.FLOAT32 -> out.putFloat(value.toFloat())
            GainPcmEncoding.UINT8 -> out.put((integer(128.0) + 128).toByte())
            GainPcmEncoding.INT16, GainPcmEncoding.INT16_BE -> out.putShort(integer(32768.0).toShort())
            GainPcmEncoding.INT32, GainPcmEncoding.INT32_BE -> out.putInt(integer(2147483648.0))
            GainPcmEncoding.INT24, GainPcmEncoding.INT24_BE -> {
                val n = integer(8388608.0)
                if (encoding.order == ByteOrder.LITTLE_ENDIAN) { out.put(n.toByte()); out.put((n shr 8).toByte()); out.put((n shr 16).toByte()) }
                else { out.put((n shr 16).toByte()); out.put((n shr 8).toByte()); out.put(n.toByte()) }
            }
        }
    }
}
