package ua.olex.music.tags

/**
 * ID3v2.2, 2.3 and 2.4 (MP3, and inside WAV), plus the 128-byte ID3v1 at the end of old files.
 * Frames are mapped to the common tag names; pictures are kept as byte ranges.
 */
internal object Id3 {
    private val NAMES = mapOf(
        "TIT2" to "TITLE", "TPE1" to "ARTIST", "TPE2" to "ALBUMARTIST", "TALB" to "ALBUM", "TRCK" to "TRACKNUMBER", "TPOS" to "DISCNUMBER",
        "TDRC" to "DATE", "TYER" to "DATE", "TDOR" to "ORIGINALDATE", "TORY" to "ORIGINALDATE", "TCON" to "GENRE", "TCOM" to "COMPOSER",
        "TSRC" to "ISRC", "TCMP" to "COMPILATION", "TBPM" to "BPM", "TPUB" to "LABEL", "TIT1" to "GROUPING", "TIT3" to "SUBTITLE",
        "TSOP" to "ARTISTSORT", "TSOA" to "ALBUMSORT", "TSO2" to "ALBUMARTISTSORT", "TSOT" to "TITLESORT",
        // ID3v2.2 three-letter frames
        "TT2" to "TITLE", "TP1" to "ARTIST", "TP2" to "ALBUMARTIST", "TAL" to "ALBUM", "TRK" to "TRACKNUMBER", "TPA" to "DISCNUMBER",
        "TYE" to "DATE", "TCO" to "GENRE", "TCM" to "COMPOSER", "TCP" to "COMPILATION",
    )
    private const val MAX_TEXT = 1024 * 1024

    /** The size of an ID3v2 tag at [at], header included, or 0 if there is none. */
    fun sizeAt(r: Reader, at: Long): Long {
        if (at + 10 > r.size || r.ascii(at, 3) != "ID3") return 0
        val h = r.bytes(at, 10)
        if (h[3].toInt() and 0xFF > 4 || (6..9).any { h[it] < 0 }) return 0
        return 10L + syncsafe(h, 6) + if (h[5].toInt() and 0x10 != 0) 10 else 0
    }

    fun read(r: Reader, at: Long, fields: Fields, pictures: MutableList<Picture>) {
        val total = sizeAt(r, at)
        if (total == 0L) return
        val h = r.bytes(at, 10)
        val major = h[3].toInt() and 0xFF
        val flags = h[5].toInt() and 0xFF
        val end = minOf(at + total - if (flags and 0x10 != 0) 10 else 0, r.size)
        var pos = at + 10
        if (major < 4 && flags and 0x80 != 0) {
            // The whole tag is unsynchronised: undo it in memory. Picture ranges would be wrong, so none are kept.
            val body = deUnsync(r.bytes(pos, (end - pos).toInt().coerceAtMost(8 * 1024 * 1024)))
            frames(Reader(ByteArraySource(body)), 0, body.size.toLong(), major, flags, fields, null)
            return
        }
        frames(r, pos, end, major, flags, fields, pictures)
    }

    private fun frames(r: Reader, from: Long, end: Long, major: Int, flags: Int, fields: Fields, pictures: MutableList<Picture>?) {
        var pos = from
        try {
            if (major >= 3 && flags and 0x40 != 0) pos += if (major == 4) syncsafe(r.bytes(pos, 4), 0) else r.u32be(pos) + 4 // extended header
            val idLength = if (major == 2) 3 else 4
            val headLength = if (major == 2) 6 else 10
            while (pos + headLength <= end) {
                val id = r.ascii(pos, idLength)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val sizeBytes = r.bytes(pos + idLength, if (major == 2) 3 else 4)
                var size = when {
                    major == 2 -> r.u24be(pos + 3).toLong()
                    // 2.4 sizes are syncsafe, but some writers (old iTunes) wrote plain ones.
                    major == 4 && sizeBytes.none { it < 0 } -> syncsafe(sizeBytes, 0)
                    else -> sizeBytes.u32be(0)
                }
                var body = pos + headLength
                val next = body + size
                if (size <= 0 || next > end) break
                val format = if (major >= 3) r.u8(pos + 9) else 0
                val compressed = if (major == 4) format and 0x0C != 0 else format and 0xC0 != 0
                val unsync = major == 4 && format and 0x02 != 0
                if (major == 4 && format and 0x01 != 0) { body += 4; size -= 4 } // data length indicator
                if (major == 3 && format and 0x20 != 0) { body += 1; size -= 1 } // grouping byte
                if (!compressed && size > 0) frame(r, id, body, size, unsync, fields, pictures)
                pos = next
            }
        } catch (_: Truncated) {
        }
    }

    private fun frame(r: Reader, id: String, body: Long, size: Long, unsync: Boolean, fields: Fields, pictures: MutableList<Picture>?) {
        if (id == "APIC" || id == "PIC") {
            if (unsync || pictures == null) return
            val head = r.bytes(body, minOf(size, 512).toInt())
            val enc = head[0].toInt()
            var i = 1
            val mime: String
            if (id == "PIC") { mime = "image/" + String(head, 1, 3, Charsets.ISO_8859_1).lowercase().replace("jpg", "jpeg"); i = 4 }
            else { val z = head.indexOfZero(i, 1); mime = String(head, i, z - i, Charsets.ISO_8859_1); i = z + 1 }
            val kind = head[i].toInt() and 0xFF
            i += 1
            val wide = enc == 1 || enc == 2
            i = head.indexOfZero(i, if (wide) 2 else 1) + if (wide) 2 else 1
            if (i < size) pictures += Picture(body + i, (size - i).toInt(), mime.ifEmpty { null }, kind)
            return
        }
        if (size > MAX_TEXT) return
        val b = r.bytes(body, size.toInt()).let { if (unsync) deUnsync(it) else it }
        when {
            id == "TXXX" || id == "TXX" -> {
                val (description, at) = terminated(b, 1, b[0].toInt())
                text(b, at, b[0].toInt()).forEach { fields.add(description, it) }
            }
            id == "USLT" || id == "ULT" -> if (b.size > 4) {
                val (_, at) = terminated(b, 4, b[0].toInt())
                if (!fields.has("LYRICS")) fields.add("LYRICS", text(b, at, b[0].toInt()).joinToString("\n"))
            }
            id[0] == 'T' -> {
                val name = NAMES[id] ?: return
                val values = text(b, 1, b[0].toInt())
                if (name == "GENRE") values.flatMap(::genres).forEach { fields.add(name, it) } else values.forEach { fields.add(name, it) }
            }
        }
    }

    /** The values of a text frame from [from]: ID3v2.4 separates several with NUL. */
    private fun text(b: ByteArray, from: Int, enc: Int): List<String> {
        if (from >= b.size) return emptyList()
        val whole = when (enc) {
            1 -> utf16(b, from, b.size)
            2 -> String(b, from, b.size - from, Charsets.UTF_16BE)
            3 -> String(b, from, b.size - from, Charsets.UTF_8)
            else -> legacyText(b, from, b.size)
        }
        return whole.split('\u0000').map { it.trim('﻿', ' ') }.filter { it.isNotEmpty() }
    }

    private fun utf16(b: ByteArray, from: Int, to: Int): String {
        if (to - from >= 2) {
            val bom = b.u16be(from)
            if (bom == 0xFFFE) return String(b, from + 2, to - from - 2, Charsets.UTF_16LE)
            if (bom == 0xFEFF) return String(b, from + 2, to - from - 2, Charsets.UTF_16BE)
        }
        return String(b, from, to - from, Charsets.UTF_16LE)
    }

    /** A NUL-terminated string at [from] and the index after its terminator. */
    private fun terminated(b: ByteArray, from: Int, enc: Int): Pair<String, Int> {
        val wide = enc == 1 || enc == 2
        val z = b.indexOfZero(from, if (wide) 2 else 1)
        val s = when (enc) {
            1 -> utf16(b, from, z); 2 -> String(b, from, z - from, Charsets.UTF_16BE)
            3 -> String(b, from, z - from, Charsets.UTF_8); else -> legacyText(b, from, z)
        }
        return s to minOf(b.size, z + if (wide) 2 else 1)
    }

    /** "(17)", "17", "(17)Rock" and "Rock" all mean Rock; "(RX)" is Remix, "(CR)" Cover. */
    private fun genres(value: String): List<String> {
        val out = ArrayList<String>()
        var rest = value
        while (rest.startsWith("(") && !rest.startsWith("((")) {
            val close = rest.indexOf(')')
            if (close < 0) break
            out += named(rest.substring(1, close))
            rest = rest.substring(close + 1)
        }
        rest = rest.removePrefix("(")
        if (rest.isNotBlank()) out += named(rest.trim())
        return out
    }

    private fun named(ref: String): String = when (ref) {
        "RX" -> "Remix"; "CR" -> "Cover"
        else -> ref.toIntOrNull()?.let(GENRES::getOrNull) ?: ref
    }

    /** ID3v1: fixed 30-byte fields at the very end. Only fills what ID3v2 did not give. */
    fun readV1(r: Reader, fields: Fields) {
        if (r.size < 128 || r.ascii(r.size - 128, 3) != "TAG") return
        val b = r.bytes(r.size - 128, 128)
        fun field(name: String, from: Int, length: Int) {
            if (fields.has(name)) return
            var end = from
            while (end < from + length && b[end].toInt() != 0) end++
            fields.add(name, legacyText(b, from, end))
        }
        field("TITLE", 3, 30); field("ARTIST", 33, 30); field("ALBUM", 63, 30); field("DATE", 93, 4)
        if (b[125].toInt() == 0 && b[126].toInt() != 0 && !fields.has("TRACKNUMBER")) fields.add("TRACKNUMBER", (b[126].toInt() and 0xFF).toString())
        if (!fields.has("GENRE")) GENRES.getOrNull(b[127].toInt() and 0xFF)?.let { fields.add("GENRE", it) }
    }

    private fun syncsafe(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0x7F shl 21) or (b[i + 1].toLong() and 0x7F shl 14) or (b[i + 2].toLong() and 0x7F shl 7) or (b[i + 3].toLong() and 0x7F)

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** The index of a terminator of [width] zero bytes from [from] (aligned for 16-bit text), or the end. */
    private fun ByteArray.indexOfZero(from: Int, width: Int): Int {
        var i = from
        while (i + width <= size) {
            if (this[i].toInt() == 0 && (width == 1 || this[i + 1].toInt() == 0)) return i
            i += width
        }
        return size
    }
}
