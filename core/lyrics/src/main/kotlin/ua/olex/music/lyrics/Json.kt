package ua.olex.music.lyrics

/**
 * A small JSON reader for the lyric services' replies, so this module stays pure Kotlin without a
 * dependency: objects become maps, arrays lists, numbers Double, true/false Boolean, null null.
 */
object Json {
    fun parse(text: String): Any? = Reader(text).run { val v = value(); ws(); if (i != s.length) error("trailing data"); v }

    private const val MAX_DEPTH = 128

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int = 0): Any? {
            require(depth <= MAX_DEPTH) { "JSON nesting exceeds the parser limit" }
            ws()
            if (i >= s.length) error("end of input")
            return when (val c = s[i]) {
                '{' -> obj(depth); '[' -> arr(depth); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) error("bad literal at $i"); i += word.length; return v }
        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
        fun str(): String {
            i++
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); 'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                            'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            error("unterminated string")
        }
        fun arr(depth: Int): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) { out += value(depth + 1); ws(); when (s[i++]) { ',' -> continue; ']' -> return out; else -> error("bad array at $i") } }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws(); if (s[i++] != ':') error("expected ':' at $i")
                out[k] = value(depth + 1); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> error("bad object at $i") }
            }
        }
    }
}
