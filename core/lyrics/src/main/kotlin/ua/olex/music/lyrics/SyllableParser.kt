package ua.olex.music.lyrics

/**
 * Syllable-timed formats that put each word's time after it, in absolute milliseconds:
 *  - QQ Music QRC (decrypted): `[startMs,durationMs]Word(startMs,durationMs)Word(...)`, often inside
 *    `<Lyric_1 LyricType="1" LyricContent="..."/>` XML;
 *  - Lyricify Syllable: `[n]Word(startMs,durationMs)...`, where n says who sings: 0-2 main, 3-5
 *    backing vocals; 2 and 5 sit on the right (a second singer).
 */
object SyllableParser {
    private val QRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val LYRICIFY_LINE = Regex("""^\[(\d)](.*)$""")
    private val WORD = Regex("""([^()]*?)\((\d+),(\d+)\)""")
    private val CONTENT = Regex("""LyricContent="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)

    /** Whether [text] looks like QRC or Lyricify Syllable rather than LRC or KRC. */
    fun matches(text: String): Boolean {
        val head = text.take(4000)
        return Regex("""\(\d+,\d+\)""").containsMatchIn(head) && !Regex("""<\d+,\d+,\d+>""").containsMatchIn(head) &&
            (Regex("""^\[\d+,\d+]""", RegexOption.MULTILINE).containsMatchIn(head) || Regex("""^\[\d]""", RegexOption.MULTILINE).containsMatchIn(head) || "LyricContent=" in head)
    }

    fun parse(input: String): Lyrics {
        val text = CONTENT.find(input)?.groupValues?.get(1)?.let(::unescape) ?: input
        val lines = ArrayList<Line>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            var start = -1L; var end = -1L; var body: String; var agent: String? = null; var background = false
            val qrc = QRC_LINE.matchEntire(line)
            val ly = if (qrc == null) LYRICIFY_LINE.matchEntire(line) else null
            when {
                qrc != null -> { start = qrc.groupValues[1].toLong(); end = start + qrc.groupValues[2].toLong(); body = qrc.groupValues[3] }
                ly != null -> {
                    val n = ly.groupValues[1].toInt()
                    background = n in 3..5; if (n % 3 == 2) agent = "v2"
                    body = ly.groupValues[2]
                }
                else -> continue
            }
            val words = WORD.findAll(body).mapNotNull { m ->
                val w = m.groupValues[1]
                val s = m.groupValues[2].toLong(); val d = m.groupValues[3].toLong()
                if (w.isEmpty()) null else Word(s, s + d, w)
            }.toList()
            if (words.isEmpty()) continue
            if (start < 0) { start = words.first().startMs; end = words.last().endMs }
            val lineText = words.joinToString("") { it.text }.trim()
            if (lineText.isEmpty()) continue
            lines += Line(start, maxOf(end, words.last().endMs), lineText, words, background = background, agent = agent)
        }
        return if (lines.isEmpty()) Lyrics.EMPTY else Lyrics(lines.sortedBy { it.startMs }, Sync.WORD)
    }

    private fun unescape(s: String) = s.replace("&#10;", "\n").replace("&#13;", "").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

/** Writes lyrics as LRC, word-timed (enhanced LRC) when the lyrics have word timing. */
object LrcWriter {
    fun write(lyrics: Lyrics): String = buildString {
        for (line in lyrics.lines) {
            if (line.startMs < 0) { appendLine(line.text); continue }
            append('[').append(stamp(line.startMs)).append(']')
            if (line.words.isNotEmpty()) {
                for (w in line.words) append('<').append(stamp(w.startMs)).append('>').append(w.text)
                append('<').append(stamp(line.words.last().endMs.coerceAtLeast(line.words.last().startMs))).append('>')
            } else append(line.text)
            appendLine()
        }
    }

    private fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d.%02d".format(t / 60_000, t / 1000 % 60, t % 1000 / 10)
    }
}

/** Timing fixes by hand: move one line (and its words) to a new start. */
object LyricsTiming {
    fun retime(lyrics: Lyrics, index: Int, startMs: Long): Lyrics {
        val line = lyrics.lines.getOrNull(index) ?: return lyrics
        if (line.startMs < 0) return lyrics
        val delta = startMs.coerceAtLeast(0) - line.startMs
        val moved = line.copy(startMs = line.startMs + delta, endMs = line.endMs + delta,
            words = line.words.map { it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta) })
        val lines = lyrics.lines.toMutableList().also { it[index] = moved }
        return lyrics.copy(lines = lines.sortedBy { it.startMs })
    }
}

/**
 * Online lyrics arrive with extras the owner does not want on the player: a translation under
 * every line (Kugou adds Chinese) and credit lines at the start ("Song - Artist", "作词 : ...").
 * Both are dropped; the sung lines and their timing stay as they are.
 */
object LyricsCleanup {
    private val credit = Regex("""^\s*(作词|作曲|编曲|词|曲|制作人|制作|监制|混音|录音|出品|发行|lyrics by|lyricist|composer|composed by|written by|producer|produced by|arranged by)\s*[:：]""", RegexOption.IGNORE_CASE)

    fun clean(lyrics: Lyrics, title: String?, artist: String?): Lyrics {
        if (lyrics.lines.isEmpty()) return lyrics
        fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val t = norm(title); val a = norm(artist)
        val heading = setOf(t + a, a + t)
        val firstSung = lyrics.lines.indexOfFirst { line ->
            val n = norm(line.text)
            n.isNotEmpty() && !(t.isNotEmpty() && n in heading) && !credit.containsMatchIn(line.text)
        }.let { if (it < 0) 0 else it }
        val lines = lyrics.lines.drop(firstSung.coerceAtMost(8))
            .filterNot { credit.containsMatchIn(it.text) }
            .map { if (it.translation != null) it.copy(translation = null) else it }
        return lyrics.copy(lines = lines)
    }
}
