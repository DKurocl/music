package ua.olex.music.model

/** A durable song reference and display snapshot, independent of the rebuildable MediaStore id. */
data class SavedSong(
    val id: String,
    val songKey: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
) {
    /** Prefer the saved file; after a move, accept the same recording with a matching duration. */
    fun resolve(library: LibraryIndex): Track? {
        if (id.startsWith(M3uPlaylist.MISSING_ID_PREFIX)) return library.playlistPaths.resolve(path).track
        val candidates = library.bySongKey[songKey].orEmpty().filter {
            durationMs <= 0 || it.durationMs <= 0 || kotlin.math.abs(durationMs - it.durationMs) <= SongKey.LENGTH_TOLERANCE_MS
        }
        return candidates.firstOrNull { it.path.orEmpty() == path }
            ?: candidates.maxByOrNull { it.format?.rank ?: 0 }
    }

    companion object {
        fun from(track: Track): SavedSong {
            val key = track.key.value
            val path = track.path.orEmpty()
            return SavedSong("${key.length}:$key${path.length}:$path", key, path, track.title, track.artist, track.album, track.durationMs, track.artworkUri)
        }
    }
}

/** A user-owned ordered playlist. Missing files remain in [songs] until explicitly removed. */
data class UserPlaylist(val id: Long, val name: String, val songs: List<SavedSong>, val createdAt: Long)

/** One actual playback start, not a click or a pause/resume event. */
data class HistoryEntry(val id: Long, val song: SavedSong, val playedAt: Long)
