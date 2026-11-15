package ua.olex.music.model

/**
 * The same song kept in more than one file: same artist and title (ignoring case, accents and
 * "feat." noise, via [SongKey]) and lengths within [TOLERANCE_MS]. Each group is sorted best copy
 * first (lossless, then resolution, then bitrate), so the rest are what can go. Tracks cut from a
 * cue sheet and files of different lengths (live, remaster edits) are never grouped.
 */
object Duplicates {
    const val TOLERANCE_MS = 3_000L

    fun find(songs: List<Track>): List<List<Track>> =
        songs.filter { '#' !in it.id.value && it.title.isNotBlank() }
            .groupBy { it.key.value }.values
            .flatMap { sameKey -> byLength(sameKey) }
            .filter { group -> group.size > 1 && group.map { it.uri }.distinct().size > 1 }
            .map { group -> group.sortedWith(compareByDescending<Track> { it.format?.rank ?: 0 }.thenBy { it.path.orEmpty() }) }
            .sortedBy { LibraryOrder.name(it.first().artist + " " + it.first().title) }

    /** Splits one title's files into runs of similar length. */
    private fun byLength(tracks: List<Track>): List<List<Track>> {
        val sorted = tracks.sortedBy { it.durationMs }
        val out = ArrayList<MutableList<Track>>()
        for (t in sorted) {
            val last = out.lastOrNull()
            if (last != null && t.durationMs - last.last().durationMs <= TOLERANCE_MS) last += t else out += mutableListOf(t)
        }
        return out
    }
}
