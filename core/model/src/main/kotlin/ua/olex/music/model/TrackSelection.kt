package ua.olex.music.model

/** Ephemeral file selection: identity survives sorting; actions follow visible order, not tap order. */
data class TrackSelection(val ids: Set<TrackId> = emptySet()) {
    fun toggle(id: TrackId): TrackSelection = when {
        id in ids -> copy(ids = ids - id)
        ids.size < MAX_ITEMS -> copy(ids = ids + id)
        else -> this
    }
    fun all(tracks: List<Track>): TrackSelection {
        require(tracks.size <= MAX_ITEMS)
        return TrackSelection(tracks.mapTo(LinkedHashSet()) { it.id })
    }
    fun retain(tracks: List<Track>): TrackSelection {
        if (ids.isEmpty()) return this
        val available = tracks.mapTo(HashSet()) { it.id }
        return copy(ids = ids.filterTo(LinkedHashSet()) { it in available })
    }
    fun ordered(tracks: List<Track>): List<Track> = tracks.filter { it.id in ids }.distinctBy { it.id }
    companion object { const val MAX_ITEMS = 20_000 }
}
