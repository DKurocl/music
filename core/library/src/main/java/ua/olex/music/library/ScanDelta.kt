package ua.olex.music.library

/** Rows to write and identities to remove from one complete source snapshot. */
internal data class ScanDelta<T>(val changed: List<T>, val removed: List<String>)

/** Linear comparison excludes scan bookkeeping through [sameContent]; duplicate IDs fail closed. */
internal fun <T> scanDelta(previous: List<T>, incoming: List<T>, key: (T) -> String,
    sameContent: (T, T) -> Boolean): ScanDelta<T> {
    val old = previous.associateBy(key)
    val seen = HashSet<String>(incoming.size)
    val changed = ArrayList<T>()
    for (row in incoming) {
        val id = key(row)
        require(seen.add(id)) { "Duplicate track in scan" }
        val saved = old[id]
        if (saved == null || !sameContent(saved, row)) changed += row
    }
    return ScanDelta(changed, old.keys.filterNot { it in seen })
}
