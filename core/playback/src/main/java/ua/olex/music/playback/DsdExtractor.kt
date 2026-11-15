package ua.olex.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.util.ParsableByteArray
import ua.olex.music.dsp.DsdToPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DSD files (Sony DSF and Philips DSDIFF/DFF) played as 88.2 kHz float PCM: Android has no DSD
 * extractor or decoder. Converted on the fly ([DsdToPcm]); seeking is exact to a block.
 */
@OptIn(UnstableApi::class)
internal class DsdExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var header: Header? = null
    private var converter: DsdToPcm? = null
    private var dataRead = 0L
    private var samplesOut = 0L
    private var buffer = ByteArray(0)
    private var pcm = FloatArray(0)

    /** [blockSize] bytes per channel per block (DSF 4096; DFF 1, bytes interleaved). */
    private class Header(val dff: Boolean, val channels: Int, val rate: Int, val lsbFirst: Boolean, val dataStart: Long,
        val dataBytes: Long, val blockSize: Int, val samples: Long)

    override fun sniff(input: ExtractorInput): Boolean {
        val b = ByteArray(16)
        if (!input.peekFully(b, 0, 16, true)) return false
        val head = String(b, 0, 4, Charsets.ISO_8859_1)
        return head == "DSD " || (head == "FRM8" && String(b, 12, 4, Charsets.ISO_8859_1) == "DSD ")
    }

    override fun init(output: ExtractorOutput) { this.output = output; track = output.track(0, C.TRACK_TYPE_AUDIO); output.endTracks() }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val h = header ?: return readHeader(input, seekPosition)
        val end = h.dataStart + h.dataBytes
        if (input.position >= end) return Extractor.RESULT_END_OF_INPUT
        val group = h.blockSize * h.channels * (if (h.dff) 4096 else 1)
        val want = minOf(group.toLong(), end - input.position).toInt()
        if (buffer.size < want) buffer = ByteArray(group)
        if (!input.readFully(buffer, 0, want, true)) return Extractor.RESULT_END_OF_INPUT
        val conv = converter!!
        val perChannel = want / h.channels
        if (pcm.size < (perChannel / 4 + 1) * h.channels * 4) pcm = FloatArray((perChannel / 4 + 1) * h.channels * 4)
        var produced = 0
        if (h.dff) {
            // DFF: one byte per channel in turn. De-interleave in place per channel.
            val ch = ByteArray(perChannel)
            for (c in 0 until h.channels) {
                for (i in 0 until perChannel) ch[i] = buffer[i * h.channels + c]
                produced = conv.channel(c, ch, 0, perChannel, pcm, c)
            }
        } else for (c in 0 until h.channels) produced = conv.channel(c, buffer, c * h.blockSize, minOf(h.blockSize, perChannel), pcm, c)
        // The last DSF block is padded: stop at the real length.
        val realLeft = (h.samples / (8 * stepBytes(h))) - samplesOut
        val frames = minOf(produced.toLong(), realLeft.coerceAtLeast(0)).toInt()
        if (frames > 0) {
            val bytes = ByteBuffer.allocate(frames * h.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * h.channels) bytes.putFloat(pcm[i])
            val data = ParsableByteArray(bytes.array())
            track.sampleData(data, data.limit())
            track.sampleMetadata(samplesOut * 1_000_000L / DsdToPcm.OUTPUT_RATE, C.BUFFER_FLAG_KEY_FRAME, data.limit(), 0, null)
            samplesOut += frames
        }
        dataRead += want
        return Extractor.RESULT_CONTINUE
    }

    private fun stepBytes(h: Header) = (h.rate / 8 / DsdToPcm.OUTPUT_RATE).coerceAtLeast(1)

    private fun readHeader(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val h = if (peek(input, 4) == "DSD ") dsf(input) else dff(input)
        header = h
        converter = DsdToPcm(h.channels, h.rate, h.lsbFirst)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .setChannelCount(h.channels).setSampleRate(DsdToPcm.OUTPUT_RATE).setCodecs(if (h.dff) "dff" else "dsf").build())
        val durationUs = h.samples * 1_000_000L / h.rate
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val group = (h.blockSize * h.channels).toLong()
                val bytesPerChannel = timeUs.coerceIn(0, durationUs) * h.rate / 8 / 1_000_000L
                val block = bytesPerChannel / h.blockSize
                val position = (h.dataStart + block * group).coerceAtMost(h.dataStart + h.dataBytes)
                return SeekMap.SeekPoints(SeekPoint(block * h.blockSize * 8 * 1_000_000L / h.rate, position))
            }
        })
        seekPosition.position = h.dataStart
        return Extractor.RESULT_SEEK
    }

    private fun peek(input: ExtractorInput, n: Int): String { val b = ByteArray(n); input.peekFully(b, 0, n); input.resetPeekPosition(); return String(b, Charsets.ISO_8859_1) }

    /** DSF: little-endian chunks "DSD ", "fmt ", "data"; channel blocks of 4096 bytes. */
    private fun dsf(input: ExtractorInput): Header {
        val head = ByteArray(28 + 52 + 12)
        input.readFully(head, 0, head.size)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val fmt = 28
        require(String(head, fmt, 4, Charsets.ISO_8859_1) == "fmt ")
        val channels = b.getInt(fmt + 24); val rate = b.getInt(fmt + 28); val bits = b.getInt(fmt + 32)
        val samples = b.getLong(fmt + 36); val block = b.getInt(fmt + 44)
        val data = fmt + 52
        require(String(head, data, 4, Charsets.ISO_8859_1) == "data")
        val dataBytes = b.getLong(data + 4) - 12
        return Header(false, channels, rate, bits == 1, (data + 12).toLong(), dataBytes, block, samples)
    }

    /** DSDIFF: big-endian IFF; PROP holds FS and CHNL; the "DSD " chunk holds byte-interleaved MSB-first data. */
    private fun dff(input: ExtractorInput): Header {
        val top = ByteArray(16); input.readFully(top, 0, 16)
        var rate = 2_822_400; var channels = 2
        while (true) {
            val ch = ByteArray(12); input.readFully(ch, 0, 12)
            val id = String(ch, 0, 4, Charsets.ISO_8859_1)
            val size = ByteBuffer.wrap(ch, 4, 8).order(ByteOrder.BIG_ENDIAN).long
            when (id) {
                "PROP" -> {
                    val body = ByteArray(size.toInt()); input.readFully(body, 0, body.size)
                    var p = 4 // "SND "
                    while (p + 12 <= body.size) {
                        val sid = String(body, p, 4, Charsets.ISO_8859_1)
                        val ssize = ByteBuffer.wrap(body, p + 4, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
                        val bb = ByteBuffer.wrap(body, p + 12, ssize).order(ByteOrder.BIG_ENDIAN)
                        when (sid) { "FS  " -> rate = bb.int; "CHNL" -> channels = bb.short.toInt(); "CMPR" -> require(String(body, p + 12, 4, Charsets.ISO_8859_1) == "DSD ") { "Compressed DST is not supported" } }
                        p += 12 + ssize + (ssize and 1)
                    }
                    if (size and 1L == 1L) input.skipFully(1)
                }
                "DSD " -> {
                    val start = input.position
                    return Header(true, channels, rate, false, start, size, 1, size / channels * 8)
                }
                else -> input.skipFully((size + (size and 1)).toInt())
            }
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        converter?.reset()
        val h = header ?: return
        samplesOut = timeUs * DsdToPcm.OUTPUT_RATE / 1_000_000L
        dataRead = (position - h.dataStart).coerceAtLeast(0)
    }

    override fun release() {}
}
