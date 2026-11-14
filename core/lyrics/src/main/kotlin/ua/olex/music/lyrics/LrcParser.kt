package ua.olex.music.lyrics

/**
 * LRC in the forms found in the wild:
 *  - line-timed: `[01:02.34]text`, also `[01:02]`, `[01:02.345]`, `[01:02:34]`, and several
 *    times on one line for a repeated line;
 *  - word-timed ("enhanced" LRC, the A2 extension): `[00:12.00]<00:12.00>Some <00:12.50>words<00:13.10>`,
 *    a closing tag giving the last word's end;
 *  - `[offset:+250]` (positive shows the lyrics earlier) and other `[key:value]` tags, ignored;
 *  - a voice prefix `F:`, `M:` or `D:` (Walaoke) becomes the line's agent;
 *  - no timing at all: plain lines.
 */
object LrcParser {
    private val LINE_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIME = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val TAG = Regex("""^\[([A-Za-z#][A-Za-z0-9_ ]*):(.*)]$""")
    private val VOICE = Regex("""^([FMD]):\s*""")

    private class Raw(val start: Long, val text: String, val words: List<Word>, val openEnd: Boolean, val agent: String?)

    fun parse(text: String): Lyrics {
        var offset = 0L
        val timed = ArrayList<Raw>()
        val plain = ArrayList<String>()
        for (source in text.lineSequence()) {
            val line = source.trim().trimStart('﻿')
            if (line.isEmpty()) continue
            val tag = TAG.matchEntire(line)
            if (tag != null) {
                if (tag.groupValues[1].trim().equals("offset", ignoreCase = true)) offset = tag.groupValues[2].trim().toLongOrNull() ?: 0L
                continue
            }

            val starts = ArrayList<Long>()
            var at = 0
            while (true) {
                val m = LINE_TIME.matchAt(line, at) ?: break
                starts += millis(m)
                at = m.range.last + 1
            }
            var body = line.substring(at).trim()
            if (starts.isEmpty()) { plain += body; continue }
            val agent = VOICE.find(body)?.let { body = body.substring(it.range.last + 1); it.groupValues[1] }
            val (words, openEnd, shown) = words(body, starts[0])
            for (start in starts) {
                val shift = start - starts[0]
                timed += Raw(start, shown, if (shift == 0L) words else words.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }, openEnd, agent)
            }
        }
        if (timed.isEmpty()) return Lyrics(plain.filter { it.isNotBlank() }.map { Line(-1, -1, it) }, Sync.NONE)

        timed.sortBy { it.start }
        val lines = ArrayList<Line>(timed.size)
        var anyWords = false
        for ((i, raw) in timed.withIndex()) {
            if (raw.text.isBlank()) continue // an empty timed line only ends the one before it
            val next = timed.getOrNull(i + 1)?.start
            var words = raw.words
            if (words.isNotEmpty()) {
                anyWords = true
                // A last word without a closing tag lasts until the next line, 1.5 s at most.
                if (raw.openEnd) {
                    val last = words.last()
                    val end = minOf(next ?: Long.MAX_VALUE, last.startMs + 1_500).coerceAtLeast(last.startMs + 1)
                    words = words.dropLast(1) + last.copy(endMs = end)
                }
            }
            val end = next ?: (words.lastOrNull()?.endMs ?: (raw.start + 5_000))
            lines += Line(
                (raw.start - offset).coerceAtLeast(0), (end - offset).coerceAtLeast(0), raw.text,
                words.map { it.copy(startMs = (it.startMs - offset).coerceAtLeast(0), endMs = (it.endMs - offset).coerceAtLeast(0)) },
                agent = raw.agent,
            )
        }
        return Lyrics(lines, if (anyWords) Sync.WORD else Sync.LINE)
    }

    /** The words of an enhanced line; [Triple.second] is true when the last word has no closing tag. */
    private fun words(body: String, lineStart: Long): Triple<List<Word>, Boolean, String> {
        val tags = WORD_TIME.findAll(body).toList()
        if (tags.isEmpty()) return Triple(emptyList(), false, body)
        val parts = ArrayList<Pair<Long, String>>()
        val lead = body.substring(0, tags[0].range.first)
        if (lead.isNotBlank()) parts += lineStart to lead
        for ((i, tag) in tags.withIndex()) {
            val until = tags.getOrNull(i + 1)?.range?.first ?: body.length
            parts += millis(tag) to body.substring(tag.range.last + 1, until)
        }
        val words = ArrayList<Word>()
        for ((i, part) in parts.withIndex()) {
            if (part.second.isBlank()) {
                // A tag followed by nothing: the end of the word before it (and a space, if any).
                if (part.second.isNotEmpty() && words.isNotEmpty() && !words.last().text.endsWith(' '))
                    words[words.lastIndex] = words.last().copy(text = words.last().text + " ")
                continue
            }
            val end = parts.getOrNull(i + 1)?.first ?: -1
            words += Word(part.first, end, part.second.trimStart().let { if (it.endsWith(' ')) it.trimEnd() + " " else it })
        }
        val openEnd = words.isNotEmpty() && words.last().endMs < 0
        if (openEnd) words[words.lastIndex] = words.last().copy(endMs = words.last().startMs)
        return Triple(words, openEnd, words.joinToString("") { it.text }.trim())
    }

    private fun millis(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toLong()
    }
}
