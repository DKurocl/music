package ua.olex.music.lyrics

/** How finely the words are timed. */
enum class Sync { NONE, LINE, WORD }

/** One sung word (or syllable). [text] ends with a space when a space follows it in the line. */
data class Word(val startMs: Long, val endMs: Long, val text: String) {
    /** How much of the word has been sung at [positionMs], 0..1. */
    fun progress(positionMs: Long): Float = when {
        positionMs <= startMs -> 0f
        positionMs >= endMs -> 1f
        else -> (positionMs - startMs).toFloat() / (endMs - startMs)
    }
}

data class Line(
    /** -1 in lyrics without timing. */
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Empty unless the lyrics are word-timed. */
    val words: List<Word> = emptyList(),
    /** Backing vocals: shown smaller under the line they belong to. */
    val background: Boolean = false,
    /** Who sings, where the source says so ("v1", "v2", "F", "M", "D"): duets sit on opposite sides. */
    val agent: String? = null,
    val translation: String? = null,
)

data class Lyrics(val lines: List<Line>, val sync: Sync) {
    /**
     * The line being sung at [positionMs]: the last main line that has started, or -1 before the
     * first. Background lines never count as the current line.
     */
    fun indexAt(positionMs: Long): Int {
        if (sync == Sync.NONE) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        while (found > 0 && lines[found].background) found--
        return found
    }

    companion object {
        val EMPTY = Lyrics(emptyList(), Sync.NONE)

        /** Picks the parser by what the text looks like: TTML is XML, everything else is LRC or plain. */
        fun parse(text: String): Lyrics {
            val head = text.trimStart('﻿', ' ', '\n', '\r', '\t').take(200)
            return when {
                SyllableParser.matches(text) -> SyllableParser.parse(text)
                head.startsWith("<") && ("<tt" in head || "<?xml" in head) -> TtmlParser.parse(text)
                Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE).containsMatchIn(text.take(4000)) -> KrcParser.parse(text)
                else -> LrcParser.parse(text)
            }
        }
    }
}
