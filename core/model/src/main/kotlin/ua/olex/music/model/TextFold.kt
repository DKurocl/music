package ua.olex.music.model

import java.text.Normalizer
import java.util.Locale

/** Text as rules and sorting compare it: no case, no accents ("Beyoncé" = "beyonce"), trimmed. */
object TextFold {
    private val MARKS = Regex("""\p{M}+""")
    fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).trim()
}
