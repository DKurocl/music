package ua.olex.music.model

/** Exact song-stem matching never borrows another case-sensitive filename's lyrics. */
object SidecarLyrics {
    /** A chosen filename or an explicit ambiguity; no arbitrary file is selected. */
    data class Choice(val name: String? = null, val ambiguous: Boolean = false)
    fun directory(audioPath: String, rootPath: String): String? {
        fun valid(path: String) = path.isNotBlank() && '\u0000' !in path && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        if (!valid(audioPath) || !valid(rootPath) || !audioPath.startsWith("$rootPath/")) return null
        return audioPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
    }
    fun primaryName(audioPath: String): String? {
        val file = audioPath.substringAfterLast('/')
        return file.substringBeforeLast('.', file).takeIf { it.isNotBlank() }?.let { "$it.lrc" }
    }
    fun choose(audioPath: String, names: List<String>): Choice {
        val file = audioPath.substringAfterLast('/')
        val stem = file.substringBeforeLast('.', file)
        if (stem.isBlank()) return Choice()
        for (ext in listOf("lrc", "ttml", "txt")) {
            val exact = names.filter { it == "$stem.$ext" }
            if (exact.size == 1) return Choice(exact.single())
            if (exact.size > 1) return Choice(ambiguous = true)
            val candidates = names.filter { it.substringBeforeLast('.', "") == stem && it.substringAfterLast('.', "").equals(ext, true) }
            if (candidates.size == 1) return Choice(candidates.single())
            if (candidates.size > 1) return Choice(ambiguous = true)
        }
        return Choice()
    }
}
