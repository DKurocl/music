package ua.olex.music.model

/** Independent song-list choices leave playlist order and album defaults untouched. */
enum class TrackSortMode { DEFAULT, TITLE, ARTIST, ALBUM, YEAR, DURATION, FILE_NAME }
/** Preference namespaces for track lists; playlist ordering is deliberately outside these scopes. */
enum class TrackSortScope { SONGS, SEARCH, ALBUM_TRACKS, ARTIST_TRACKS, GENRE_TRACKS, FOLDER_TRACKS }

/** Deterministic user-selected ordering. Missing values stay last in both directions. */
data class TrackSortOrder(val mode: TrackSortMode = TrackSortMode.DEFAULT, val descending: Boolean = false) {
    fun sort(tracks: List<Track>): List<Track> {
        if (mode == TrackSortMode.DEFAULT) return if (descending) tracks.reversed() else tracks
        class Entry(val track: Track) {
            val title = LibraryOrder.name(track.title)
            val text = when (mode) {
                TrackSortMode.TITLE -> title.takeIf { it.isNotBlank() }
                TrackSortMode.ARTIST -> track.artist.takeIf { it.isNotBlank() }?.let(LibraryOrder::name)
                TrackSortMode.ALBUM -> track.album?.takeIf { it.isNotBlank() }?.let(LibraryOrder::name)
                TrackSortMode.FILE_NAME -> track.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let(LibraryOrder::name)
                else -> null
            }
            val number = when (mode) {
                TrackSortMode.YEAR -> track.year?.takeIf { it > 0 }?.toLong()
                TrackSortMode.DURATION -> track.durationMs.takeIf { it > 0 }
                else -> null
            }
            val albumArtist = if (mode == TrackSortMode.ALBUM) LibraryOrder.name(track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist) else ""
            val missing = if (mode == TrackSortMode.YEAR || mode == TrackSortMode.DURATION) number == null else text == null
        }
        val comparator = Comparator<Entry> { a, b ->
            var order = a.missing.compareTo(b.missing)
            if (order == 0) {
                order = if (mode == TrackSortMode.YEAR || mode == TrackSortMode.DURATION) compareValues(a.number, b.number) else compareValues(a.text, b.text)
                if (descending) order = -order
            }
            // Direction changes album order, never the musical order inside each album.
            if (order == 0 && mode == TrackSortMode.ALBUM) {
                order = a.albumArtist.compareTo(b.albumArtist)
                if (order == 0) order = (a.track.discNumber?.takeIf { it > 0 } ?: 1).compareTo(b.track.discNumber?.takeIf { it > 0 } ?: 1)
                if (order == 0) order = (a.track.trackNumber?.takeIf { it > 0 } ?: Int.MAX_VALUE).compareTo(b.track.trackNumber?.takeIf { it > 0 } ?: Int.MAX_VALUE)
            }
            if (order == 0) order = a.title.compareTo(b.title)
            if (order == 0) order = a.track.id.value.compareTo(b.track.id.value)
            order
        }
        return tracks.map(::Entry).sortedWith(comparator).map { it.track }
    }
}
