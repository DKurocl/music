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
            """\b(remaster(ed)?|deluxe|bonus( track)?|official (audio|video|music video)|lyrics?( video)?|audio|hq|hd)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
        private val MARKS = Regex("""\p{M}+""")
        private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")

        private fun primaryArtist(artist: String): String =
            // Punctuation is part of band names (AC/DC, Earth, Wind & Fire). Only explicit
            // featured credits are unambiguous until the tag reader supplies multi-value artists.
            fold(FEAT.replace(artist, ""))

        private fun cleanTitle(title: String): String {
            var t = FEAT.replace(title, "")
            t = BRACKETS.replace(t) { m -> annotation(m.groupValues[1]) }
            t = DASH_NOTE.replace(t) { m -> annotation(m.groupValues[1]) }
            return fold(t)
        }

        private fun annotation(note: String): String {
            if (!EDITION.containsMatchIn(note)) return " $note"
            // Removing the whole bracket would turn "Live, Remastered 2011" into a studio song.
            val recording = YEAR.replace(EDITION.replace(note, " "), " ")
            return " $recording"
        }

        /** Lower case, no accents, letters and digits of any script, single spaces. */
        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replace(MARKS, "")
                .lowercase()
                .replace("&", " and ")
                .replace(PUNCTUATION, " ")
                .trim()
    }
}
