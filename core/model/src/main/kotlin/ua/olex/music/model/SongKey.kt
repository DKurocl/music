package ua.olex.music.model

import java.text.Normalizer

/**
 * One song, wherever it is. A local FLAC, a YouTube Music result and a Soulseek file are the same
 * song when their keys are equal and their lengths agree ([sameSong]). Everything that links the
 * sources goes through here: a mix from a local track, playing the owned file instead of a stream,
 * "already in the library" on a download.
 *
 * The key is the artist and the title reduced to what does not vary between sources: case,
 * accents, punctuation, "feat." credits, and edition notes such as "(Remastered 2011)" are
 * dropped. Notes that make a different recording ("live", "remix", "acoustic", "instrumental")
 * are kept.
 */
@JvmInline
value class SongKey private constructor(val value: String) {
    companion object {
        fun of(artist: String, title: String): SongKey = SongKey(primaryArtist(artist) + "|" + cleanTitle(title))

        /** Lengths of the same recording differ by padding and encoder delay, rarely by over 3 s. */
        const val LENGTH_TOLERANCE_MS = 3_000L

        fun sameSong(a: Track, b: Track): Boolean =
            a.key == b.key && (a.durationMs <= 0 || b.durationMs <= 0 || kotlin.math.abs(a.durationMs - b.durationMs) <= LENGTH_TOLERANCE_MS)

        /** "(feat. X)", "[with X]" in brackets; "feat. X" to the end outside them ("with" is a word there). */
        private val FEAT = Regex(
            """\s*[(\[]\s*(feat\.?|ft\.?|featuring|with)\b[^)\]]*[)\]]|\s+(feat\.?|ft\.?|featuring)\s.*$""",
            RegexOption.IGNORE_CASE,
        )
        private val BRACKETS = Regex("""\s*[(\[]([^)\]]*)[)\]]""")
        private val DASH_NOTE = Regex("""\s+-\s+([^-]*)$""")
        /** Edition notes: the same recording, sold again. */
        private val EDITION = Regex(
            """\b(remaster(ed)?|mono|stereo|deluxe|bonus( track)?|explicit|clean|original( mix| version)?|album version|single version|radio edit|\d{4} (mix|version|edit)|official (audio|video|music video)|lyrics?( video)?|audio|hq|hd)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val ARTIST_SPLIT = Regex("""\s*(,|;|&|/| x | vs\.? | and )\s*""", RegexOption.IGNORE_CASE)

        private fun primaryArtist(artist: String): String =
            fold(FEAT.replace(artist, "").split(ARTIST_SPLIT).firstOrNull().orEmpty())

        private fun cleanTitle(title: String): String {
            var t = FEAT.replace(title, "")
            t = BRACKETS.replace(t) { m -> if (EDITION.containsMatchIn(m.groupValues[1])) "" else " " + m.groupValues[1] }
            t = DASH_NOTE.replace(t) { m -> if (EDITION.containsMatchIn(m.groupValues[1])) "" else " " + m.groupValues[1] }
            return fold(t)
        }

        /** Lower case, no accents, letters and digits of any script, single spaces. */
        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replace(Regex("""\p{M}+"""), "")
                .lowercase()
                .replace("&", " and ")
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .trim()
    }
}
