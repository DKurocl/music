package ua.olex.music.tags

import java.io.ByteArrayOutputStream

/**
 * Ogg Vorbis and Opus comments. The header packets are laid out again over exactly as many pages
 * as before, so every audio page keeps its number and checksum and only the start of the file
 * changes. Null when the new comments cannot fit those pages, or for FLAC-in-Ogg.
 */
internal object OggWriter {
    private class Page(val offset: Long, val flags: Int, val serial: Int, val sequence: Int, val lacing: IntArray, val bodyAt: Long) {
        val bodySize get() = lacing.sum()
        val end get() = bodyAt + bodySize
    }

    private fun page(r: Reader, at: Long): Page? {
        if (at + 27 > r.size || r.ascii(at, 4) != "OggS") return null
        val h = r.bytes(at, 27)
        val segments = h[26].toInt() and 0xFF
        val lacing = r.bytes(at + 27, segments).map { it.toInt() and 0xFF }.toIntArray()
        return Page(at, h[5].toInt() and 0xFF, h.u32le(14).toInt(), h.u32le(18).toInt(), lacing, at + 27 + segments)
    }

    fun plan(r: Reader, edits: Map<String, List<String>>): TagRewrite? {
        val first = page(r, 0) ?: return null
        val id = r.bytes(first.bodyAt, first.bodySize)
        val opus = String(id, 0, minOf(8, id.size), Charsets.ISO_8859_1) == "OpusHead"
        val vorbis = id.size > 7 && id[0].toInt() == 1 && String(id, 1, 6, Charsets.ISO_8859_1) == "vorbis"
        if (!opus && !vorbis) return null
        val wanted = if (opus) 1 else 2 // Opus: tags; Vorbis: comment and setup
        // Collect the header packets and the pages they occupy.
        val packets = ArrayList<ByteArray>()
        val current = ByteArrayOutputStream()
        val pages = ArrayList<Page>()
        var at = first.end
        while (packets.size < wanted) {
            val p = page(r, at) ?: return null
            if (p.serial != first.serial) return null
            pages += p
            var pos = p.bodyAt
            for (l in p.lacing) {
                current.write(r.bytes(pos, l)); pos += l
                if (l < 255) { packets += current.toByteArray(); current.reset() }
            }
            at = p.end
            if (pages.size > 64) return null
        }
        if (current.size() > 0) return null // audio must start on a fresh page
        val comment = packets[0]
        val prefix = if (opus) 8 else 7
        val vendor = TagWriter.vendorOf(comment, prefix) ?: "Music"
        val merged = TagWriter.merge(TagWriter.rawComments(comment, prefix), edits)
        val body = TagWriter.vorbisComments(vendor, merged)
        val newComment = (if (opus) "OpusTags".toByteArray() else byteArrayOf(3) + "vorbis".toByteArray()) + body + (if (vorbis) byteArrayOf(1) else ByteArray(0))
        val laid = paginate(listOf(newComment) + packets.drop(1), pages.size, first.serial, pages.first().sequence) ?: return null
        return TagRewrite(laid, at, start = first.end)
    }

    /** Lays [packets] over exactly [count] pages (granule 0, as header pages are). */
    private fun paginate(packets: List<ByteArray>, count: Int, serial: Int, firstSequence: Int): ByteArray? {
        val segments = ArrayList<Pair<Int, Int>>() // (packet index, length)
        for ((i, p) in packets.withIndex()) {
            var left = p.size
            while (left >= 255) { segments += i to 255; left -= 255 }
            segments += i to left
        }
        if (segments.size < count || segments.size > count * 255) return null
        val out = ByteArrayOutputStream()
        val offsets = IntArray(packets.size)
        var s = 0
        for (page in 0 until count) {
            val remainingPages = count - page
            val take = (segments.size - s + remainingPages - 1) / remainingPages
            val slice = segments.subList(s, s + take)
            val continued = s > 0 && segments[s - 1].second == 255
            val body = ByteArrayOutputStream()
            for ((i, len) in slice) { body.write(packets[i], offsets[i], len); offsets[i] += len }
            val header = ByteArrayOutputStream().apply {
                write("OggS".toByteArray()); write(0); write(if (continued) 1 else 0)
                repeat(8) { write(0) }
                le32(serial); le32(firstSequence + page); le32(0)
                write(slice.size); slice.forEach { write(it.second) }
            }.toByteArray()
            val pageBytes = header + body.toByteArray()
            val crc = crc(pageBytes)
            pageBytes[22] = crc.toByte(); pageBytes[23] = (crc ushr 8).toByte(); pageBytes[24] = (crc ushr 16).toByte(); pageBytes[25] = (crc ushr 24).toByte()
            out.write(pageBytes)
            s += take
        }
        return out.toByteArray()
    }

    private val TABLE = IntArray(256) { i -> var c = i shl 24; repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }; c }
    private fun crc(b: ByteArray): Int { var c = 0; for (x in b) c = (c shl 8) xor TABLE[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]; return c }
    private fun ByteArrayOutputStream.le32(n: Int) { write(n and 0xFF); write(n shr 8 and 0xFF); write(n shr 16 and 0xFF); write(n shr 24 and 0xFF) }
}
