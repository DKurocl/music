package ua.olex.music.tags

import java.io.ByteArrayOutputStream

/**
 * How to write a file with new tags: put [head] at the start, then the original bytes from
 * [keepFrom] on. When [inPlace] is true, [head] is exactly [keepFrom] long (the old padding
 * absorbed the change), so only the start of the file is overwritten and the audio never moves.
 * Otherwise the caller writes [head] plus the original tail to a new copy and replaces the file.
 */
/** A front cover to embed: the image bytes as they are (JPEG or PNG), never re-encoded. */
class CoverArt(val bytes: ByteArray, val mime: String) { init { require(bytes.size in 1..(8 shl 20)) } }

class TagRewrite(val head: ByteArray, val keepFrom: Long, val start: Long = 0) {
    /** The new bytes replace [start, keepFrom) of the file; everything else stays byte for byte. */
    val inPlace: Boolean get() = head.size.toLong() == keepFrom - start
}

/**
 * Writes tags into FLAC (Vorbis comments) and MP3 (ID3v2.4), keeping every other block, frame and
 * the audio untouched. [edits] use the common names of [AudioTags] (TITLE, ARTIST, ALBUMARTIST,
 * TRACKNUMBER, DISCNUMBER, DATE, GENRE, COMPOSER, LYRICS, ...); a name with an empty list is removed;
 * names not in [edits] keep their old values. Null for formats it cannot write yet.
 */
object TagWriter {
    private const val PADDING = 8 * 1024

    /** [cover] replaces the embedded pictures with one front cover; null keeps the pictures as they are. */
    fun plan(source: ByteSource, edits: Map<String, List<String>>, cover: CoverArt? = null): TagRewrite? = try {
        val r = Reader(source)
        val id3 = Id3.sizeAt(r, 0)
        when {
            r.ascii(id3.coerceAtMost(r.size - 4), 4) == "fLaC" -> flac(r, id3, edits, cover)
            r.ascii(4, 4) == "ftyp" -> Mp4Writer.plan(r, edits, cover)
            r.ascii(0, 4) == "OggS" -> OggWriter.plan(r, edits)
            r.ascii(0, 4) != "RIFF" -> mp3(r, id3, edits, cover)
            else -> null
        }
    } catch (_: Truncated) { null }

    // ---- FLAC -----------------------------------------------------------------------------------

    private fun flac(r: Reader, start: Long, edits: Map<String, List<String>>, cover: CoverArt?): TagRewrite? {
        val kept = ArrayList<ByteArray>()     // whole blocks (header + body) to keep, STREAMINFO first
        var comments: List<Pair<String, String>> = emptyList()
        var vendor = "Music"
        var at = start + 4
        while (true) {
            val head = r.u8(at)
            val length = r.u24be(at + 1)
            val type = head and 0x7F
            when (type) {
                4 -> r.bytes(at + 4, length).let { b -> vendor = vendorOf(b) ?: vendor; comments = rawComments(b) }
                1 -> Unit // padding: rebuilt below
                6 -> if (cover == null || length < 4 || r.u32be(at + 4) != 3L) kept += r.bytes(at, 4 + length)
                else -> kept += r.bytes(at, 4 + length)
            }
            at += 4 + length
            if (head and 0x80 != 0) break
        }
        val audio = at
        val merged = merge(comments, edits)
        val vc = vorbisComments(vendor, merged)
        fun build(padding: Int): ByteArray = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            for (block in kept) { write((block[0].toInt() and 0x7F)); write(block, 1, block.size - 1) }
            write(4); be24(vc.size); write(vc)
            if (cover != null) {
                val mime = cover.mime.toByteArray()
                val pic = ByteArrayOutputStream().apply {
                    be32(3); be32(mime.size); write(mime); be32(0); be32(0); be32(0); be32(0); be32(0); be32(cover.bytes.size); write(cover.bytes)
                }.toByteArray()
                write(6); be24(pic.size); write(pic)
            }
            write(0x80 or 1); be24(padding); write(ByteArray(padding))
        }.toByteArray()
        val bare = build(0).size.toLong()
        // In place when the new blocks fit where the old ones were (one padding block needs its 4-byte header).
        return if (start == 0L && bare <= audio && audio - bare <= 0xFFFFFF) TagRewrite(build((audio - bare).toInt()), audio)
        else TagRewrite(build(PADDING), audio)
    }

    internal fun vendorOf(b: ByteArray, from: Int = 0): String? = runCatching { String(b, from + 4, b.u32le(from).toInt(), Charsets.UTF_8) }.getOrNull()

    /** A Vorbis comment block (vendor, count, NAME=value entries), as Vorbis, Opus and FLAC store it. */
    internal fun vorbisComments(vendor: String, comments: List<Pair<String, String>>): ByteArray = ByteArrayOutputStream().apply {
        val v = vendor.toByteArray(); le32(v.size); write(v); le32(comments.size)
        for ((k, value) in comments) { val b = "$k=$value".toByteArray(); le32(b.size); write(b) }
    }.toByteArray()

    internal fun rawComments(b: ByteArray, from: Int = 0): List<Pair<String, String>> = runCatching {
        var p = from + 4 + b.u32le(from).toInt()
        val n = b.u32le(p).toInt(); p += 4
        (0 until n).mapNotNull {
            val len = b.u32le(p).toInt(); p += 4
            val s = String(b, p, len, Charsets.UTF_8); p += len
            s.indexOf('=').takeIf { it > 0 }?.let { s.substring(0, it) to s.substring(it + 1) }
        }
    }.getOrDefault(emptyList())

    /** Old comments minus the edited names, then the edited values, in a stable order. */
    internal fun merge(old: List<Pair<String, String>>, edits: Map<String, List<String>>): List<Pair<String, String>> {
        val edited = edits.keys.map { Fields.canonical(it) }.toSet()
        val kept = old.filter { Fields.canonical(it.first) !in edited }
        val added = edits.flatMap { (name, values) -> values.filter { it.isNotBlank() }.map { Fields.canonical(name) to it.trim() } }
        return kept + added
    }

    // ---- MP3: ID3v2.4 -------------------------------------------------------------------------

    private val FRAMES = mapOf(
        "TITLE" to "TIT2", "ARTIST" to "TPE1", "ALBUMARTIST" to "TPE2", "ALBUM" to "TALB", "TRACKNUMBER" to "TRCK",
        "DISCNUMBER" to "TPOS", "DATE" to "TDRC", "ORIGINALDATE" to "TDOR", "GENRE" to "TCON", "COMPOSER" to "TCOM",
        "ISRC" to "TSRC", "COMPILATION" to "TCMP", "BPM" to "TBPM", "LABEL" to "TPUB", "GROUPING" to "TIT1",
    )

    private fun mp3(r: Reader, oldSize: Long, edits: Map<String, List<String>>, cover: CoverArt?): TagRewrite? {
        // Only a file that really is MPEG audio (or already has a tag) is written.
        if (oldSize == 0L && TagReader.read(object : ByteSource {
                override val size get() = r.size
                override fun read(position: Long, into: ByteArray, offset: Int, length: Int) = r.bytes(position, minOf(length.toLong(), r.size - position).toInt()).also { it.copyInto(into, offset) }.size
            })?.codec?.startsWith("mp") != true) return null
        val edited = edits.keys.map { Fields.canonical(it) }.toSet()
        val replacedIds = edited.mapNotNull { FRAMES[it] }.toSet() + (if ("LYRICS" in edited) setOf("USLT") else emptySet()) +
            (if ("RATING" in edited) setOf("POPM") else emptySet())
        val frames = ByteArrayOutputStream()
        if (oldSize > 0 && !keepOldFrames(r, oldSize, replacedIds, edited, cover != null, frames)) return null
        for ((name, values) in edits) {
            val canonical = Fields.canonical(name)
            val list = values.map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) continue
            when {
                canonical == "LYRICS" -> frame(frames, "USLT", byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + list.joinToString("\n").toByteArray())
                // Rating 0-100 as a popularimeter (the Windows Media Player scale most players read).
                canonical == "RATING" -> list.first().toIntOrNull()?.let { r ->
                    val byte = when { r <= 0 -> 0; r <= 20 -> 1; r <= 40 -> 64; r <= 60 -> 128; r <= 80 -> 196; else -> 255 }
                    frame(frames, "POPM", "Windows Media Player 9 Series".toByteArray() + byteArrayOf(0, byte.toByte()))
                }
                FRAMES[canonical] != null -> frame(frames, FRAMES.getValue(canonical), byteArrayOf(3) + list.joinToString("\u0000").toByteArray())
                else -> frame(frames, "TXXX", byteArrayOf(3) + canonical.toByteArray() + byteArrayOf(0) + list.joinToString("\u0000").toByteArray())
            }
        }
        if (cover != null) frame(frames, "APIC", byteArrayOf(0) + cover.mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + cover.bytes)
        val body = frames.toByteArray()
        fun build(padding: Int) = ByteArrayOutputStream().apply {
            write("ID3".toByteArray()); write(4); write(0); write(0); syncsafe(body.size + padding); write(body); write(ByteArray(padding))
        }.toByteArray()
        val bare = 10L + body.size
        return if (oldSize > 0 && bare <= oldSize) TagRewrite(build((oldSize - bare).toInt()), oldSize)
        else TagRewrite(build(PADDING), oldSize)
    }

    /** Copies frames of the old tag that are not being replaced, re-encoded as ID3v2.4 frames. */
    private fun keepOldFrames(r: Reader, size: Long, replaced: Set<String>, edited: Set<String>, replaceFront: Boolean,
        out: ByteArrayOutputStream): Boolean {
        val h = r.bytes(0, 10)
        val major = h[3].toInt()
        if (major !in 3..4 || h[5].toInt() and 0x80 != 0) return false
        var pos = 10L
        if (h[5].toInt() and 0x40 != 0) pos += if (major == 4) syncsafeOf(r.bytes(pos, 4)) else r.u32be(pos) + 4
        val end = minOf(size, r.size)
        while (pos + 10 <= end) {
            val id = r.ascii(pos, 4)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val len = if (major == 4) syncsafeOf(r.bytes(pos + 4, 4)) else r.u32be(pos + 4)
            if (len <= 0 || pos + 10 + len > end) break
            val flags = r.u8(pos + 9)
            val special = if (major == 4) flags and 0x4F != 0 else flags and 0xE0 != 0
            if (special && id !in replaced) return false
            val body = r.bytes(pos + 10, len.toInt())
            val drop = id in replaced ||
                (id == "APIC" && replaceFront && frontPicture(body)) ||
                (id == "TXXX" && txxxName(body)?.let { Fields.canonical(it) in edited } == true)
            if (!drop) frame(out, id, body)
            pos += 10 + len
        }
        return true
    }

    private fun frontPicture(body: ByteArray): Boolean {
        for (i in 1 until body.lastIndex) if (body[i] == 0.toByte()) return body[i + 1] == 3.toByte()
        return false
    }

    private fun txxxName(body: ByteArray): String? = runCatching {
        val enc = body[0].toInt()
        val wide = enc == 1 || enc == 2
        var i = 1
        while (i < body.size && !(body[i].toInt() == 0 && (!wide || body[i + 1].toInt() == 0))) i += if (wide) 2 else 1
        when (enc) { 1 -> String(body, 1, i - 1, Charsets.UTF_16); 2 -> String(body, 1, i - 1, Charsets.UTF_16BE); 3 -> String(body, 1, i - 1, Charsets.UTF_8); else -> String(body, 1, i - 1, Charsets.ISO_8859_1) }
    }.getOrNull()

    private fun frame(out: ByteArrayOutputStream, id: String, body: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1)); out.syncsafe(body.size); out.write(0); out.write(0); out.write(body)
    }

    private fun syncsafeOf(b: ByteArray): Long =
        (b[0].toLong() and 0x7F shl 21) or (b[1].toLong() and 0x7F shl 14) or (b[2].toLong() and 0x7F shl 7) or (b[3].toLong() and 0x7F)

    private fun ByteArrayOutputStream.syncsafe(n: Int) { write(n shr 21 and 0x7F); write(n shr 14 and 0x7F); write(n shr 7 and 0x7F); write(n and 0x7F) }
    private fun ByteArrayOutputStream.le32(n: Int) { write(n and 0xFF); write(n shr 8 and 0xFF); write(n shr 16 and 0xFF); write(n shr 24 and 0xFF) }
    private fun ByteArrayOutputStream.be32(n: Int) { write(n shr 24 and 0xFF); write(n shr 16 and 0xFF); write(n shr 8 and 0xFF); write(n and 0xFF) }
    private fun ByteArrayOutputStream.be24(n: Int) { write(n shr 16 and 0xFF); write(n shr 8 and 0xFF); write(n and 0xFF) }
}
