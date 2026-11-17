package ua.olex.music.model

/** Bounded per-occurrence retries; a manual Play/Prepare permits a fresh attempt after conditions change. */
class SourceRecoveryBudget(private val maxOccurrences: Int = 64, private val maxAttempts: Int = 3) {
    private val failed = LinkedHashMap<String, MutableSet<String>>()
    init { require(maxOccurrences > 0 && maxAttempts > 0) }
    fun begin(occurrence: String, source: String): Boolean {
        val attempts = failed.getOrPut(occurrence) {
            if (failed.size >= maxOccurrences) failed.remove(failed.keys.first())
            LinkedHashSet()
        }
        if (source in attempts || attempts.size >= maxAttempts) return false
        attempts.add(source)
        return true
    }
    fun rejected(occurrence: String): Set<String> = failed[occurrence]?.toSet().orEmpty()
    fun reset() = failed.clear()
}
