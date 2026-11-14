package ua.olex.music.tags

/** Vorbis comments, the tag format of FLAC, Ogg Vorbis and Opus: `NAME=value` pairs in UTF-8. */
internal object VorbisComments {
    /** A value longer than this is an embedded picture or junk, not text: it is skipped, not loaded. */
    private const val MAX_TEXT = 256 * 1024

    /**
     * Reads a comment block from memory. A block cut short (a huge picture comment was not
     * loaded) gives the comments before the cut.
     */
    fun parse(b: ByteArray, start: Int, into: Fields) {
        var at = start
        fun u32(): Long { if (at + 4 > b.size) throw Truncated(); return b.u32le(at).also { at += 4 } }
        try {
            val vendor = u32().toInt() // read first: the length field itself moves the position
            at += vendor
            val count = u32()
            repeat(minOf(count, 10_000L).toInt()) {
                val length = u32()
                if (length < 0 || at + length > b.size) throw Truncated()
                if (length <= MAX_TEXT) {
                    val text = String(b, at, length.toInt(), Charsets.UTF_8)
                    val eq = text.indexOf('=')
                    if (eq > 0) {
                        val name = text.substring(0, eq)
                        if (!name.equals("METADATA_BLOCK_PICTURE", ignoreCase = true) && !name.equals("COVERART", ignoreCase = true))
                            into.add(name, text.substring(eq + 1))
                    }
                }
                at += length.toInt()
            }
        } catch (_: Truncated) {
        } catch (_: IndexOutOfBoundsException) {
        }
    }
}

internal object Flac {
    fun read(r: Reader, start: Long): AudioTags? {
        if (r.ascii(start, 4) != "fLaC") return null
        val fields = Fields()
        val pictures = ArrayList<Picture>()
        var rate: Int? = null; var bits: Int? = null; var channels: Int? = null; var duration: Long? = null
        var at = start + 4
        try {
            while (true) {
                val head = r.u8(at)
                val length = r.u24be(at + 1)
                val body = at + 4
                when (head and 0x7F) {
                    0 -> { // STREAMINFO: 20 bits rate, 3 bits channels - 1, 5 bits depth - 1, 36 bits total samples
                        val s = r.bytes(body + 10, 8)
                        val sampleRate = (s[0].toInt() and 0xFF shl 12) or (s[1].toInt() and 0xFF shl 4) or (s[2].toInt() and 0xF0 shr 4)
                        rate = sampleRate.takeIf { it > 0 }
                        channels = ((s[2].toInt() and 0x0E) shr 1) + 1
                        bits = (((s[2].toInt() and 0x01) shl 4) or ((s[3].toInt() and 0xF0) shr 4)) + 1
                        val samples = ((s[3].toLong() and 0x0F) shl 32) or s.u32be(4)
                        if (sampleRate > 0 && samples > 0) duration = samples * 1000 / sampleRate
                    }
                    4 -> if (length <= 4 * 1024 * 1024) VorbisComments.parse(r.bytes(body, length), 0, fields)
                    6 -> runCatching { // PICTURE: type, mime, description, 4 dimensions, data
                        val kind = r.u32be(body).toInt()
                        val mimeLength = r.u32be(body + 4).toInt()
                        val mime = r.ascii(body + 8, mimeLength.coerceIn(0, 255))
                        val descLength = r.u32be(body + 8 + mimeLength)
                        val dataLengthAt = body + 8 + mimeLength + 4 + descLength + 16
                        val dataLength = r.u32be(dataLengthAt)
                        if (dataLength > 0 && dataLengthAt + 4 + dataLength <= r.size)
                            pictures += Picture(dataLengthAt + 4, dataLength.toInt(), mime.ifEmpty { null }, kind)
                    }
                }
                at = body + length
                if (head and 0x80 != 0) break
            }
        } catch (_: Truncated) {
            if (rate == null) return null
        }
        val audioBytes = r.size - at
        return AudioTags(
            "flac", "flac", rate, bits, channels, duration,
            bitrateKbps = duration?.takeIf { it > 0 }?.let { (audioBytes * 8 / it).toInt() },
            fields = fields.build(), pictures = pictures,
        )
    }
}

/** Ogg Vorbis and Opus: the first packet describes the audio, the second holds the comments. */
internal object Ogg {
    private const val MAX_COMMENT_PACKET = 1024 * 1024

    fun read(r: Reader): AudioTags? {
        val packets = firstPackets(r, 2)
        val head = packets.getOrNull(0) ?: return null
        val fields = Fields()
        val codec: String
        val rate: Int
        val channels: Int
        var preSkip = 0
        var nominalKbps: Int? = null
        when {
            head.size >= 19 && String(head, 0, 8, Charsets.ISO_8859_1) == "OpusHead" -> {
                codec = "opus"; channels = head[9].toInt() and 0xFF; preSkip = head.u16le(10)
                rate = 48_000 // Opus always decodes at 48 kHz, whatever the input rate was
                packets.getOrNull(1)?.takeIf { it.size > 8 && String(it, 0, 8, Charsets.ISO_8859_1) == "OpusTags" }?.let { VorbisComments.parse(it, 8, fields) }
            }
            head.size >= 30 && head[0].toInt() == 1 && String(head, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> {
                codec = "vorbis"; channels = head[11].toInt() and 0xFF; rate = head.u32le(12).toInt()
                nominalKbps = (head.u32le(20) / 1000).toInt().takeIf { it > 0 }
                packets.getOrNull(1)?.takeIf { it.size > 7 && it[0].toInt() == 3 }?.let { VorbisComments.parse(it, 7, fields) }
            }
            else -> return null
        }
        val samples = lastGranule(r)?.minus(preSkip)?.takeIf { it > 0 }
        val duration = samples?.let { it * 1000 / rate }
        return AudioTags(
            "ogg", codec, rate, null, channels, duration,
            bitrateKbps = duration?.takeIf { it > 0 }?.let { (r.size * 8 / it).toInt() } ?: nominalKbps,
            fields = fields.build(),
        )
    }

    /** Puts the first [count] packets together from their pages (a packet may span several). */
    private fun firstPackets(r: Reader, count: Int): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var skipping = false
        var at = 0L
        try {
            while (packets.size < count) {
                if (r.ascii(at, 4) != "OggS") break
                val segments = r.u8(at + 26)
                val lacing = r.bytes(at + 27, segments)
                var data = at + 27 + segments
                for (l in lacing) {
                    val n = l.toInt() and 0xFF
                    if (!skipping) {
                        if (current.size() + n > MAX_COMMENT_PACKET) skipping = true // keep what fits: the comments before a huge picture
                        else current.write(r.bytes(data, n))
                    }
                    data += n
                    if (n < 255) {
                        packets += current.toByteArray(); current = java.io.ByteArrayOutputStream(); skipping = false
                        if (packets.size == count) break
                    }
                }
                at = data
            }
        } catch (_: Truncated) {
        }
        return packets
    }

    /** The position of the last sample: the granule of the last page, found from the end of the file. */
    private fun lastGranule(r: Reader): Long? {
        for (span in longArrayOf(64 * 1024, 512 * 1024)) {
            val from = maxOf(0, r.size - span)
            val tail = runCatching { r.bytes(from, (r.size - from).toInt()) }.getOrNull() ?: return null
            for (i in tail.size - 27 downTo 0) {
                if (tail[i] == 'O'.code.toByte() && tail[i + 1] == 'g'.code.toByte() && tail[i + 2] == 'g'.code.toByte() && tail[i + 3] == 'S'.code.toByte()) {
                    val granule = tail.u32le(i + 6) or (tail.u32le(i + 10) shl 32)
                    if (granule >= 0) return granule
                }
            }
            if (from == 0L) break
        }
        return null
    }
}
