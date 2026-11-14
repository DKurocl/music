package ua.olex.music.tags

/** MP3 audio: the first frame header says what the stream is; the Xing/Info/LAME header says how long. */
internal object Mpeg {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BITRATES_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATES = intArrayOf(44_100, 48_000, 32_000)

    private class Frame(val version: Int, val layer: Int, val kbps: Int, val rate: Int, val channels: Int, val length: Int, val samples: Int)

    private fun header(h: Long): Frame? {
        if (h and 0xFFE00000L != 0xFFE00000L) return null
        val versionBits = (h shr 19 and 3).toInt() // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val layerBits = (h shr 17 and 3).toInt()   // 3 = Layer I, 2 = II, 1 = III
        val bitrateIndex = (h shr 12 and 15).toInt()
        val rateIndex = (h shr 10 and 3).toInt()
        if (versionBits == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val layer = 4 - layerBits
        val v1 = versionBits == 3
        val kbps = when {
            v1 && layer == 1 -> BITRATES_V1_L1; v1 && layer == 2 -> BITRATES_V1_L2; v1 -> BITRATES_V1_L3
            layer == 1 -> BITRATES_V2_L1; else -> BITRATES_V2_L23
        }[bitrateIndex]
        val rate = RATES[rateIndex] shr when (versionBits) { 3 -> 0; 2 -> 1; else -> 2 }
        val padding = (h shr 9 and 1).toInt()
        val samples = when (layer) { 1 -> 384; 2 -> 1152; else -> if (v1) 1152 else 576 }
        val length = if (layer == 1) (12 * kbps * 1000 / rate + padding) * 4 else samples / 8 * kbps * 1000 / rate + padding
        val channels = if ((h shr 6 and 3).toInt() == 3) 1 else 2
        return Frame(if (v1) 1 else 2, layer, kbps, rate, channels, length, samples)
    }

    /** [audioStart] is after any ID3v2 tag; [audioEnd] is before an ID3v1 tag. */
    fun read(r: Reader, audioStart: Long, audioEnd: Long, fields: Fields, pictures: List<Picture>): AudioTags? {
        val window = runCatching { r.bytes(audioStart, minOf(128 * 1024L, audioEnd - audioStart).toInt()) }.getOrNull() ?: return null
        var at = -1
        var frame: Frame? = null
        // A sync word alone is common in other data: a real frame is followed by another one.
        for (i in 0 until window.size - 4) {
            if (window[i] != 0xFF.toByte()) continue
            val f = header(window.u32be(i)) ?: continue
            val next = i + f.length
            if (f.length < 24) continue
            if (next + 4 <= window.size && header(window.u32be(next))?.rate != f.rate) continue
            at = i; frame = f; break
        }
        val f = frame ?: return null
        val first = audioStart + at
        val audioBytes = audioEnd - first
        var frames: Long? = null
        var delay: Int? = null
        var padding: Int? = null
        // The Xing/Info header sits in the first frame, after the side information.
        val side = if (f.version == 1) (if (f.channels == 1) 17 else 32) else (if (f.channels == 1) 9 else 17)
        val x = at + 4 + side
        if (f.layer == 3 && x + 16 <= window.size) {
            val tag = String(window, x, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = window.u32be(x + 4).toInt()
                var p = x + 8
                if (flags and 1 != 0) { frames = window.u32be(p); p += 4 }
                if (flags and 2 != 0) p += 4
                if (flags and 4 != 0) p += 100
                if (flags and 8 != 0) p += 4
                // LAME extension: 9 bytes of version, then 12 more; delay and padding are 12 bits each at +21.
                if (p + 24 <= window.size && window[p].toInt().toChar().isLetter()) {
                    val d = (window[p + 21].toInt() and 0xFF shl 4) or (window[p + 22].toInt() and 0xF0 shr 4)
                    val e = (window[p + 22].toInt() and 0x0F shl 8) or (window[p + 23].toInt() and 0xFF)
                    if (d in 1..4000) { delay = d; padding = e }
                }
            }
        }
        val duration = when {
            frames != null && frames > 0 -> (frames * f.samples - (delay ?: 0) - (padding ?: 0)).coerceAtLeast(0) * 1000 / f.rate
            f.kbps > 0 -> audioBytes * 8 / f.kbps
            else -> null
        }
        return AudioTags(
            "mp3", if (f.layer == 3) "mp3" else "mp${f.layer}", f.rate, null, f.channels, duration,
            bitrateKbps = if (frames != null && duration != null && duration > 0) (audioBytes * 8 / duration).toInt() else f.kbps,
            fields = fields.build(), pictures = pictures, encoderDelay = delay, encoderPadding = padding,
        )
    }
}
