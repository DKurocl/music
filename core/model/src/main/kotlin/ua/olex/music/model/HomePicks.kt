package ua.olex.music.model

/** Builds one playable shelf, retaining the provider order and preferring a confirmed catalogue copy. */
object HomePicks {
    fun select(catalogue: List<Track>, recommended: List<Track>, history: List<Track>, favourites: List<Track>,
        stats: Map<String, SongStats>, seed: Long, playable: (Track) -> Boolean, excluded: (Track) -> Boolean): List<Track> {
        val known = catalogue.distinctBy { it.id }.filter { playable(it) && !excluded(it) }
        val filesByStream = HashMap<String, Track>()
        for (track in known) {
            if (track.uri.startsWith(SavedSong.ONLINE_PREFIX)) continue
            for (source in track.sources) if (source.kind == RecordingSourceKind.YOUTUBE) source.uri?.let { filesByStream[it] = track }
        }
        val recent = history.distinctBy { it.statsKey }.mapIndexed { index, track -> track.statsKey to index }.toMap()
        val liked = favourites.mapTo(HashSet()) { it.statsKey }
        val shuffled = known.shuffled(java.util.Random(seed))
        val familiar = shuffled.mapNotNull { track ->
            val key = track.statsKey
            val score = (stats[key]?.plays ?: 0).coerceAtMost(40) + (if (key in liked) 12 else 0) +
                (recent[key]?.let { (16 - it).coerceAtLeast(1) } ?: 0)
            if (score > 0) track to score else null
        }.sortedByDescending { it.second }.map { it.first }
        val result = ArrayList<Track>(24)
        val ids = HashSet<TrackId>()
        val recordings = HashSet<String>()
        fun add(track: Track) {
            if (result.size == 24 || excluded(track)) return
            val chosen = filesByStream[track.uri] ?: track
            if (!playable(chosen) || chosen.id in ids || (chosen.recordingId != null && chosen.recordingId in recordings)) return
            ids += chosen.id
            chosen.recordingId?.let { recordings += it }
            result += chosen
        }
        val mine = familiar.iterator()
        val service = recommended.iterator()
        while (result.size < 24 && (mine.hasNext() || service.hasNext())) {
            if (mine.hasNext()) add(mine.next())
            if (service.hasNext()) add(service.next())
        }
        for (track in shuffled) {
            if (result.size == 24) break
            add(track)
        }
        return result
    }
}
