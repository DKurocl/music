package ua.olex.music.tags

/** MP4 / M4A (AAC and ALAC): atoms within atoms. Audio is described in `stsd`, tags live in `ilst`. */
internal object Mp4 {
    private val NAMES = mapOf(
        "©nam" to "TITLE", "©ART" to "ARTIST", "aART" to "ALBUMARTIST", "©alb" to "ALBUM", "©day" to "DATE",
        "©gen" to "GENRE", "©wrt" to "COMPOSER", "©lyr" to "LYRICS", "©grp" to "GROUPING",
        "soar" to "ARTISTSORT", "soal" to "ALBUMSORT", "soaa" to "ALBUMARTISTSORT", "sonm" to "TITLESORT",
    )

    private class State {
        val fields = Fields()
        val pictures = ArrayList<Picture>()
        var codec: String? = null; var rate: Int? = null; var bits: Int? = null; var channels: Int? = null
        var duration: Long? = null; var delay: Int? = null; var padding: Int? = null
    }

    fun read(r: Reader): AudioTags? {
        val s = State()
        try { walk(r, 0, r.size, "", s, 0) } catch (_: Truncated) { }
        if (s.codec == null && s.fields.build().isEmpty()) return null
        return AudioTags(
            "mp4", s.codec, s.rate, s.bits, s.channels, s.duration,
            bitrateKbps = s.duration?.takeIf { it > 0 }?.let { (r.size * 8 / it).toInt() },
            fields = s.fields.build(), pictures = s.pictures, encoderDelay = s.delay, encoderPadding = s.padding,
        )
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst")

    private fun walk(r: Reader, from: Long, end: Long, parent: String, s: State, depth: Int) {
        var at = from
        while (at + 8 <= end) {
            var size = r.u32be(at)
            val type = r.ascii(at + 4, 4)
            var body = at + 8
            if (size == 1L) { size = r.u64be(at + 8); body += 8 } else if (size == 0L) size = end - at
            if (size < 8 || at + size > end) return
            val next = at + size
            when {
                parent == "ilst" -> item(r, type, body, next, s)
                type == "mvhd" -> {
                    val v1 = r.u8(body) == 1
                    val scale = r.u32be(body + if (v1) 20 else 12)
                    val length = if (v1) r.u64be(body + 24) else r.u32be(body + 16)
                    if (scale > 0 && length > 0) s.duration = length * 1000 / scale
                }
                type == "stsd" && s.codec == null -> sampleEntry(r, body + 8, next, s)
                // `meta` is a full box: four bytes of version and flags come before its children.
                type == "meta" && depth < 8 -> walk(r, body + 4, next, type, s, depth + 1)
                type in CONTAINERS && depth < 8 -> walk(r, body, next, type, s, depth + 1)
            }
            at = next
        }
    }

    /** The first sample description: `mp4a` (AAC) or `alac` with its own precise header. */
    private fun sampleEntry(r: Reader, at: Long, end: Long, s: State) {
        if (at + 36 > end) return
        val format = r.ascii(at + 4, 4)
        val channels = r.u16be(at + 24)
        val sampleSize = r.u16be(at + 26)
        val rate = (r.u32be(at + 32) shr 16).toInt()
        when (format) {
            "alac" -> {
                s.codec = "alac"; s.channels = channels; s.bits = sampleSize; s.rate = rate
                // The inner `alac` atom has the real values (the outer rate field cannot hold 96 kHz and up).
                val entry = r.bytes(at, minOf(end - at, 128).toInt())
                val inner = (36 until entry.size - 28).firstOrNull { entry[it] == 'a'.code.toByte() && String(entry, it, 4, Charsets.ISO_8859_1) == "alac" }
                if (inner != null) {
                    val c = inner + 8 // after the type and the version/flags
                    s.bits = entry[c + 5].toInt() and 0xFF
                    s.channels = entry[c + 9].toInt() and 0xFF
                    s.rate = entry.u32be(c + 20).toInt()
                }
            }
            "mp4a" -> { s.codec = "aac"; s.channels = channels; s.rate = rate.takeIf { it > 0 } }
            "fLaC" -> { s.codec = "flac"; s.channels = channels; s.bits = sampleSize; s.rate = rate.takeIf { it > 0 } }
            "Opus" -> { s.codec = "opus"; s.channels = channels; s.rate = 48_000 }
            else -> s.codec = format.trim().lowercase()
        }
    }

    private fun item(r: Reader, type: String, from: Long, end: Long, s: State) {
        var name: String? = null
        var at = from
        while (at + 8 <= end) {
            val size = r.u32be(at)
            if (size < 8 || at + size > end) return
            val child = r.ascii(at + 4, 4)
            when (child) {
                "name" -> name = String(r.bytes(at + 12, (size - 12).toInt().coerceIn(0, 255)), Charsets.UTF_8)
                "data" -> if (size >= 16) data(r, type, name, r.u32be(at + 8).toInt() and 0xFFFFFF, at + 16, (size - 16).toInt(), s)
            }
            at += size
        }
    }

    private fun data(r: Reader, type: String, freeform: String?, kind: Int, at: Long, length: Int, s: State) {
        fun text() = if (length <= 1024 * 1024) String(r.bytes(at, length), Charsets.UTF_8) else ""
        when (type) {
            "covr" -> if (length > 0) s.pictures += Picture(at, length, when (kind) { 13 -> "image/jpeg"; 14 -> "image/png"; else -> null }, 3)
            "trkn", "disk" -> if (length >= 6) {
                val number = r.u16be(at + 2); val total = r.u16be(at + 4)
                if (number > 0) s.fields.add(if (type == "trkn") "TRACKNUMBER" else "DISCNUMBER", if (total > 0) "$number/$total" else "$number")
            }
            "gnre" -> if (length >= 2) GENRES.getOrNull(r.u16be(at) - 1)?.let { s.fields.add("GENRE", it) }
            "cpil" -> if (length >= 1 && r.u8(at) != 0) s.fields.add("COMPILATION", "1")
            "----" -> {
                val value = text()
                if (freeform == "iTunSMPB") { // " 00000000 00000840 000001C0 ...": delay and padding in hex
                    val parts = value.trim().split(' ')
                    s.delay = parts.getOrNull(1)?.toIntOrNull(16); s.padding = parts.getOrNull(2)?.toIntOrNull(16)
                } else if (freeform != null && !freeform.startsWith("iTun")) s.fields.add(freeform, value)
            }
            else -> NAMES[type]?.let { s.fields.add(it, text()) }
        }
    }
}

/** WAV: `fmt ` says what the PCM is, `data` how much; tags are a `LIST INFO` chunk or an embedded ID3. */
internal object Wav {
    private val INFO = mapOf("INAM" to "TITLE", "IART" to "ARTIST", "IPRD" to "ALBUM", "ICRD" to "DATE", "IGNR" to "GENRE", "ITRK" to "TRACKNUMBER", "IPRT" to "TRACKNUMBER")

    fun read(r: Reader): AudioTags? {
        if (r.ascii(0, 4) != "RIFF" || r.ascii(8, 4) != "WAVE") return null
        val fields = Fields()
        val pictures = ArrayList<Picture>()
        var rate: Int? = null; var bits: Int? = null; var channels: Int? = null; var byteRate = 0L; var dataBytes = 0L; var float = false
        var at = 12L
        try {
            while (at + 8 <= r.size) {
                val id = r.ascii(at, 4)
                val size = r.u32le(at + 4)
                val body = at + 8
                when (id.lowercase()) {
                    "fmt " -> {
                        val b = r.bytes(body, 16)
                        float = b.u16le(0) == 3
                        channels = b.u16le(2); rate = b.u32le(4).toInt(); byteRate = b.u32le(8); bits = b.u16le(14)
                    }
                    "data" -> dataBytes = minOf(size, r.size - body)
                    "list" -> if (r.ascii(body, 4) == "INFO") {
                        var p = body + 4
                        while (p + 8 <= body + size) {
                            val key = r.ascii(p, 4)
                            val n = r.u32le(p + 4)
                            if (n in 1..65_536) INFO[key]?.let { name ->
                                val text = r.bytes(p + 8, n.toInt())
                                fields.add(name, legacyText(text, 0, text.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) text.size else it }))
                            }
                            p += 8 + n + (n and 1)
                        }
                    }
                    "id3 " -> Id3.read(r, body, fields, pictures)
                }
                at = body + size + (size and 1)
            }
        } catch (_: Truncated) {
        }
        if (rate == null) return null
        return AudioTags(
            "wav", if (float) "pcm_float" else "pcm", rate, bits, channels,
            durationMs = if (byteRate > 0) dataBytes * 1000 / byteRate else null,
            bitrateKbps = (byteRate * 8 / 1000).toInt().takeIf { it > 0 },
            fields = fields.build(), pictures = pictures,
        )
    }
}
