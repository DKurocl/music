package ua.olex.music.model

/**
 * Narrows any song list: quality, play state, rating, decade and genre. All set conditions must
 * hold. An empty filter returns the same list object (no copy), so lists stay cheap.
 */
data class TrackFilter(
    val lossless: Boolean = false,
    val hiRes: Boolean = false,
    val unplayed: Boolean = false,
    val minRating: Int = 0,
    /** First year of a decade (1990 = the 1990s), or null for any. */
    val decade: Int? = null,
    val genre: String? = null,
) {
    val empty: Boolean get() = this == TrackFilter()

    fun apply(tracks: List<Track>, stats: (Track) -> SongStats? = { null }): List<Track> {
        if (empty) return tracks
        val genreKey = genre?.let(TextFold::fold)
        return tracks.filter { t ->
            (!lossless || t.format?.lossless == true) &&
                (!hiRes || t.format?.hiRes == true) &&
                (!unplayed || (stats(t)?.plays ?: 0) == 0) &&
                (minRating == 0 || (stats(t)?.rating ?: 0) >= minRating) &&
                (decade == null || (t.year ?: 0) in decade until decade + 10) &&
                (genreKey == null || t.genres.any { TextFold.fold(it) == genreKey })
        }
    }

    companion object {
        /** Decades present in [tracks], newest first. */
        fun decades(tracks: List<Track>): List<Int> = tracks.mapNotNull { it.year?.takeIf { y -> y > 0 }?.let { y -> y / 10 * 10 } }.distinct().sortedDescending()
    }
}

/** Disc headers for an album shown in its own order: index of the first song of each disc → disc number. */
fun discStarts(tracks: List<Track>): Map<Int, Int> {
    val discs = tracks.map { it.discNumber?.takeIf { n -> n > 0 } ?: 1 }
    if (discs.distinct().size < 2) return emptyMap()
    return discs.withIndex().filter { (i, d) -> i == 0 || discs[i - 1] != d }.associate { it.index to it.value }
}

/** Plain numbers about the library and listening, for the statistics page. */
data class LibraryStats(
    val songs: Int, val albums: Int, val artists: Int, val totalMs: Long, val lossless: Int, val hiRes: Int,
    val plays: Int, val listenedMs: Long,
    val topArtists: List<Pair<String, Int>>, val topSongs: List<Pair<Track, Int>>, val formats: List<Pair<String, Int>>,
) {
    companion object {
        fun of(library: LibraryIndex, stats: (Track) -> SongStats?): LibraryStats {
            val songs = library.songs
            val played = songs.map { it to (stats(it)?.plays ?: 0) }.filter { it.second > 0 }
            return LibraryStats(
                songs = songs.size, albums = library.albums.size, artists = library.artists.size,
                totalMs = songs.sumOf { it.durationMs.coerceAtLeast(0) },
                lossless = songs.count { it.format?.lossless == true }, hiRes = songs.count { it.format?.hiRes == true },
                plays = played.sumOf { it.second }, listenedMs = played.sumOf { (t, n) -> t.durationMs.coerceAtLeast(0) * n },
                topArtists = played.groupBy { it.first.albumArtist?.takeIf(String::isNotBlank) ?: it.first.artist }
                    .map { (name, rows) -> name to rows.sumOf { it.second } }.sortedByDescending { it.second }.take(10),
                topSongs = played.sortedByDescending { it.second }.take(10),
                formats = songs.groupBy { it.format?.codec?.uppercase() ?: "Unknown" }.map { it.key to it.value.size }.sortedByDescending { it.second },
            )
        }
    }
}
