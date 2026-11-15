package ua.olex.music.tags

import java.io.ByteArrayOutputStream

/**
 * M4A tags (AAC and ALAC): the `ilst` inside `moov/udta/meta` is rebuilt; other atoms are kept as
 * they are. When `moov` comes before the audio and grows, the chunk offsets (stco/co64) are moved
 * with the audio, unless a `free` atom right after `moov` absorbs the change (then only `moov` and
 * that padding are rewritten).
 */
internal object Mp4Writer {
    private val ATOMS = mapOf(
        "TITLE" to "©nam", "ARTIST" to "©ART", "ALBUMARTIST" to "aART", "ALBUM" to "©alb", "DATE" to "©day",
        "GENRE" to "©gen", "COMPOSER" to "©wrt", "LYRICS" to "©lyr", "GROUPING" to "©grp",
    )
    private const val PADDING = 4096

    private class Atom(val type: String, val start: Long, val size: Long, val header: Int) { val end get() = start + size }

    private fun atoms(r: Reader, from: Long, end: Long): List<Atom> {
        val out = ArrayList<Atom>()
        var at = from
        while (at + 8 <= end) {
            var size = r.u32be(at); var header = 8
            if (size == 1L) { size = r.u64be(at + 8); header = 16 } else if (size == 0L) size = end - at
            if (size < header || at + size > end) break
            out += Atom(r.ascii(at + 4, 4), at, size, header)
            at += size
        }
        return out
    }

    fun plan(r: Reader, edits: Map<String, List<String>>, cover: CoverArt?): TagRewrite? {
        val top = atoms(r, 0, r.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val mdat = top.firstOrNull { it.type == "mdat" } ?: return null
        if (moov.size > 64L shl 20 || moov.header != 8) return null
        val bytes = r.bytes(moov.start, moov.size.toInt())
        val newMoov = rebuildMoov(bytes, edits, cover) ?: return null
        val before = moov.start < mdat.start
        if (!before) return TagRewrite(newMoov, moov.end, start = moov.start) // moov at the end: nothing else moves
        val free = top.firstOrNull { it.start == moov.end && (it.type == "free" || it.type == "skip") }
        val room = moov.size + (free?.size ?: 0)
        // Fits with the old padding: moov plus a smaller free atom fill exactly the old space.
        if (free != null && (newMoov.size.toLong() == room || newMoov.size + 8L <= room)) {
            val pad = room - newMoov.size
            val head = if (pad == 0L) newMoov else newMoov + freeAtom(pad.toInt())
            return TagRewrite(head, free.end, start = moov.start)
        }
        // Grows: audio moves by delta, so every chunk offset moves with it. Leave padding for next time.
        val padded = newMoov.size + PADDING
        val delta = padded - room
        val shifted = shiftOffsets(newMoov, delta) ?: return null
        return TagRewrite(shifted + freeAtom(PADDING), free?.end ?: moov.end, start = moov.start)
    }

    private fun freeAtom(size: Int) = ByteArray(size).also { be32(it, 0, size); "free".toByteArray().copyInto(it, 4) }

    /** moov with udta/meta/ilst replaced (created when missing). */
    private fun rebuildMoov(moov: ByteArray, edits: Map<String, List<String>>, cover: CoverArt?): ByteArray? {
        val r = Reader(ByteArraySource(moov))
        val children = atoms(r, 8, moov.size.toLong())
        val udta = children.firstOrNull { it.type == "udta" }
        val udtaChildren = udta?.let { atoms(r, it.start + 8, it.end) }.orEmpty()
        val meta = udtaChildren.firstOrNull { it.type == "meta" }
        val metaChildren = meta?.let { atoms(r, it.start + 12, it.end) }.orEmpty()
        val ilst = metaChildren.firstOrNull { it.type == "ilst" }
        val newIlst = atom("ilst", buildItems(r, ilst, edits, cover))
        val newMeta = atom("meta", byteArrayOf(0, 0, 0, 0) + (if (meta == null) hdlr() else ByteArray(0)) +
            concat(metaChildren.filter { it.type != "ilst" }.map { r.bytes(it.start, it.size.toInt()) }) + newIlst)
        val newUdta = atom("udta", concat(udtaChildren.filter { it.type != "meta" }.map { r.bytes(it.start, it.size.toInt()) }) + newMeta)
        return atom("moov", concat(children.filter { it.type != "udta" }.map { r.bytes(it.start, it.size.toInt()) }) + newUdta)
    }

    private fun hdlr() = atom("hdlr", ByteArray(8) + "mdirappl".toByteArray() + ByteArray(9))

    private fun buildItems(r: Reader, ilst: Atom?, edits: Map<String, List<String>>, cover: CoverArt?): ByteArray {
        val edited = edits.keys.map { Fields.canonical(it) }.toSet()
        val replaced = edited.mapNotNull { ATOMS[it] }.toSet() +
            (if ("TRACKNUMBER" in edited) setOf("trkn") else emptySet()) + (if ("DISCNUMBER" in edited) setOf("disk") else emptySet()) +
            (if ("GENRE" in edited) setOf("gnre") else emptySet()) + (if (cover != null) setOf("covr") else emptySet())
        val out = ByteArrayOutputStream()
        ilst?.let { list ->
            for (item in atoms(r, list.start + 8, list.end)) {
                if (item.type in replaced) continue
                if (item.type == "----") {
                    val name = atoms(r, item.start + 8, item.end).firstOrNull { it.type == "name" }?.let { String(r.bytes(it.start + 12, (it.size - 12).toInt()), Charsets.UTF_8) }
                    if (name != null && Fields.canonical(name) in edited) continue
                }
                out.write(r.bytes(item.start, item.size.toInt()))
            }
        }
        for ((name, values) in edits) {
            val key = Fields.canonical(name)
            val list = values.map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) continue
            when {
                key == "TRACKNUMBER" || key == "DISCNUMBER" -> {
                    val (n, total) = list.first().split('/').map { it.trim().toIntOrNull() ?: 0 }.let { it.getOrElse(0) { 0 } to it.getOrElse(1) { 0 } }
                    val payload = if (key == "TRACKNUMBER") byteArrayOf(0, 0, (n shr 8).toByte(), n.toByte(), (total shr 8).toByte(), total.toByte(), 0, 0)
                        else byteArrayOf(0, 0, (n shr 8).toByte(), n.toByte(), (total shr 8).toByte(), total.toByte())
                    out.write(atom(if (key == "TRACKNUMBER") "trkn" else "disk", data(0, payload)))
                }
                ATOMS[key] != null -> out.write(atom(ATOMS.getValue(key), data(1, list.joinToString("; ").toByteArray())))
                else -> out.write(atom("----", atom("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()) +
                    atom("name", ByteArray(4) + key.toByteArray()) + data(1, list.joinToString("; ").toByteArray())))
            }
        }
        cover?.let { out.write(atom("covr", data(if (it.mime == "image/png") 14 else 13, it.bytes))) }
        return out.toByteArray()
    }

    private fun data(type: Int, payload: ByteArray) = atom("data", byteArrayOf(0, 0, 0, type.toByte(), 0, 0, 0, 0) + payload)

    private fun atom(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        be32(out, 0, out.size); type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4); body.copyInto(out, 8)
        return out
    }

    private fun concat(parts: List<ByteArray>) = ByteArrayOutputStream().apply { parts.forEach(::write) }.toByteArray()

    /** Adds [delta] to every stco/co64 entry in the moov (the audio moved by that much). */
    private fun shiftOffsets(moov: ByteArray, delta: Long): ByteArray? {
        val out = moov.copyOf()
        val r = Reader(ByteArraySource(out))
        fun walk(from: Long, end: Long, depth: Int): Boolean {
            for (a in atoms(r, from, end)) {
                when (a.type) {
                    "trak", "mdia", "minf", "stbl" -> if (depth < 8 && !walk(a.start + a.header, a.end, depth + 1)) return false
                    "stco" -> {
                        val count = r.u32be(a.start + 12).toInt()
                        for (i in 0 until count) {
                            val p = (a.start + 16 + i * 4L).toInt()
                            val v = out.u32be(p) + delta
                            if (v > 0xFFFFFFFFL) return false
                            be32(out, p, v.toInt())
                        }
                    }
                    "co64" -> {
                        val count = r.u32be(a.start + 12).toInt()
                        for (i in 0 until count) {
                            val p = (a.start + 16 + i * 8L).toInt()
                            var v = 0L; for (k in 0 until 8) v = (v shl 8) or (out[p + k].toLong() and 0xFF)
                            v += delta
                            for (k in 0 until 8) out[p + k] = (v shr (56 - 8 * k)).toByte()
                        }
                    }
                }
            }
            return true
        }
        return if (walk(8, out.size.toLong(), 0)) out else null
    }

    private fun be32(b: ByteArray, at: Int, v: Int) { b[at] = (v shr 24).toByte(); b[at + 1] = (v shr 16).toByte(); b[at + 2] = (v shr 8).toByte(); b[at + 3] = v.toByte() }
}
