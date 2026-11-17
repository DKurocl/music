package ua.olex.music.ui

import ua.olex.music.model.HistoryEntry
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.UserPlaylist

/** Missing files keep their durable metadata and remain removable from user collections. */
data class SavedSongRow(val song: SavedSong, val track: Track?)

/** Resolved playlist view; only available files enter a playback queue. */
data class PlaylistUi(val id: Long, val name: String, val songs: List<SavedSongRow>) {
    val playable = songs.mapNotNull { it.track }
}

/** Resolved once per library/user-data change, rather than on every player-state emission. */
data class PersonalLibraryState(
    val favourites: List<SavedSongRow> = emptyList(),
    val playlists: List<PlaylistUi> = emptyList(),
    val history: List<SavedSongRow> = emptyList(),
    private val linkedFavourites: Map<String, SavedSong> = emptyMap(),
) {
    val favouriteByTrackId = linkedFavourites + buildMap {
        for (row in favourites) {
            row.track?.let { put(it.id.value, row.song) }
            row.song.references.filter { it.kind == ua.olex.music.model.RecordingSourceKind.YOUTUBE }
                .forEach { put("yt:${it.uri!!.removePrefix(SavedSong.ONLINE_PREFIX)}", row.song) }
        }
    }

    companion object {
        fun resolve(library: LibraryIndex, favourites: List<SavedSong>, playlists: List<UserPlaylist>, history: List<HistoryEntry>): PersonalLibraryState {
            fun row(song: SavedSong) = SavedSongRow(song, song.resolve(library))
            val byRecording = favourites.associateBy { it.id }
            val linked = ua.olex.music.model.SavedCatalog.bindLocal(library, favourites).mapNotNull { track ->
                byRecording[track.recordingId]?.let { track.id.value to it }
            }.toMap()
            return PersonalLibraryState(
                favourites.map(::row),
                playlists.map { PlaylistUi(it.id, it.name, it.songs.map(::row)) },
                history.map { row(it.song) }.distinctBy { it.song.id },
                linked,
            )
        }
    }
}

/** Screen callbacks expose user actions, not database or player implementations. */
data class PersonalLibraryActions(
    val create: (String, List<Track>) -> Unit,
    val rename: (Long, String) -> Unit,
    val delete: (Long) -> Unit,
    /** Playlist, songs, and whether songs already in it are added again. */
    val add: (Long, List<Track>, Boolean) -> Unit,
    /** Playlist, song, and the song's position in the playlist (a song may appear twice). */
    val remove: (Long, String, Int) -> Unit,
    /** Playlist, song, step (-1 up, 1 down), position. */
    val move: (Long, String, Int, Int) -> Unit,
    val removeFavourite: (String) -> Unit,
    val importPlaylist: (String, List<SavedSong>) -> Unit = { _, _ -> },
)
