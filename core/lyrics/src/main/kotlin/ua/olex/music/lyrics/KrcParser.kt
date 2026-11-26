package ua.olex.music.lyrics

import java.util.zip.Inflater

/**
 * Kugou's KRC, the word-timed lyrics LastWave and most Chinese players use. A .krc file is the
 * text "krc1", then bytes XOR-ed with a fixed 16-byte key, then zlib. Inside, each line is
 * `[startMs,durationMs]<offsetMs,durationMs,0>word<...>word`, word offsets relative to the line.
 * A `[language:...]` header may carry a Base64 JSON translation, one entry per line.
 */
object KrcParser {
    private val KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    private val LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    private const val MAX_COMPRESSED_BYTES = 1 shl 20
    private const val MAX_TEXT_BYTES = 4 shl 20

    /** The text of an encrypted .krc file, or null when it is not one. */
    fun decrypt(bytes: ByteArray): String? {
        if (bytes.size !in 5..MAX_COMPRESSED_BYTES || String(bytes, 0, 4, Charsets.ISO_8859_1) != "krc1") return null
        val body = ByteArray(bytes.size - 4) { i -> (bytes[i + 4].toInt() xor KEY[i % KEY.size]).toByte() }
        val inflater = Inflater()
        return try {
            inflater.setInput(body)
            val out = java.io.ByteArrayOutputStream(minOf(body.size, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val before = inflater.bytesRead
                val n = inflater.inflate(buffer)
                if (n == 0 && !inflater.finished() &&
                    (inflater.needsInput() || inflater.needsDictionary() || inflater.bytesRead == before)) return null
                if (n > MAX_TEXT_BYTES - out.size()) return null
                out.write(buffer, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8).trimStart('﻿')
        } catch (_: java.util.zip.DataFormatException) { null } finally { inflater.end() }
    }

    fun parse(text: String): Lyrics {
        val translations = translation(text)
        val lines = ArrayList<Line>()
        for (raw in text.lineSequence()) {
            val m = LINE.matchEntire(raw.trim()) ?: continue
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val length = m.groupValues[2].toLongOrNull()?.takeIf { it <= Long.MAX_VALUE - start } ?: continue
            val words = WORD.findAll(m.groupValues[3]).mapNotNull { w ->
                val text = w.groupValues[3]
                if (text.isEmpty()) return@mapNotNull null
                val offset = w.groupValues[1].toLongOrNull()?.takeIf { it <= Long.MAX_VALUE - start } ?: return@mapNotNull null
                val wordStart = start + offset
                val wordLength = w.groupValues[2].toLongOrNull()?.takeIf { it <= Long.MAX_VALUE - wordStart } ?: return@mapNotNull null
                Word(wordStart, wordStart + wordLength, text)
            }.toList()
            val lineText = words.joinToString("") { it.text }.trim()
            if (lineText.isEmpty()) continue
            lines += Line(start, start + length, lineText, words, translation = translations?.getOrNull(lines.size))
        }
        return if (lines.isEmpty()) Lyrics.EMPTY else Lyrics(lines, if (lines.any { it.words.isNotEmpty() }) Sync.WORD else Sync.LINE)
    }

    /** `[language:<base64 json>]`: {"content":[{"type":1,"lyricContent":[["line"],...]}]} with type 1 = translation. */
    private fun translation(text: String): List<String>? = runCatching {
        val b64 = Regex("""\[language:([^\]]+)]""").find(text)?.groupValues?.get(1) ?: return null
        val json = Json.parse(String(java.util.Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8)) as Map<*, *>
        val content = (json["content"] as List<*>).map { it as Map<*, *> }.firstOrNull { (it["type"] as? Double) == 1.0 } ?: return null
        (content["lyricContent"] as List<*>).map { row -> (row as List<*>).joinToString("") { it.toString() }.trim() }
    }.getOrNull()
}
