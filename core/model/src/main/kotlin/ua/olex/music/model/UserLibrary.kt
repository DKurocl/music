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
            // A YouTube Music song (liked, in a playlist, in history) stays itself, streaming, until the
            // owner has it as a file: then the file above wins, with no step from him.
            ?: online()
    }

    /** The streamed song this reference names, when it was saved from YouTube Music. */
    fun online(): Track? {
        if (!path.startsWith(ONLINE_PREFIX)) return null
        val videoId = path.removePrefix(ONLINE_PREFIX).takeIf { it.isNotBlank() } ?: return null
        return Track(TrackId("yt:$videoId"), path, title, artist, album = album, durationMs = durationMs, artworkUri = artworkUri)
    }

    companion object {
        /** How a YouTube Music song's address starts (OnlineStreams.SCHEME in the player). */
        const val ONLINE_PREFIX = "ytm://"

        fun from(track: Track): SavedSong {
            val key = track.key.value
            // A file is kept by its path; a YouTube Music song by its address, which never changes.
            val path = track.path ?: track.uri.takeIf { it.startsWith(ONLINE_PREFIX) }.orEmpty()
            return SavedSong("${key.length}:$key${path.length}:$path", key, path, track.title, track.artist, track.album, track.durationMs, track.artworkUri)
        }
    }
}

/** A user-owned ordered playlist. Missing files remain in [songs] until explicitly removed. */
data class UserPlaylist(val id: Long, val name: String, val songs: List<SavedSong>, val createdAt: Long)

/** One actual playback start, not a click or a pause/resume event. */
data class HistoryEntry(val id: Long, val song: SavedSong, val playedAt: Long)
