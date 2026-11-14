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
) {
    val favouriteByTrackId = favourites.mapNotNull { row -> row.track?.let { it.id.value to row.song } }.toMap()

    companion object {
        fun resolve(library: LibraryIndex, favourites: List<SavedSong>, playlists: List<UserPlaylist>, history: List<HistoryEntry>): PersonalLibraryState {
            fun row(song: SavedSong) = SavedSongRow(song, song.resolve(library))
            return PersonalLibraryState(
                favourites.map(::row),
                playlists.map { PlaylistUi(it.id, it.name, it.songs.map(::row)) },
                history.map { row(it.song) }.distinctBy { it.track?.id?.value ?: it.song.id },
            )
        }
    }
}

/** Screen callbacks expose user actions, not database or player implementations. */
data class PersonalLibraryActions(
    val create: (String, List<Track>) -> Unit,
    val rename: (Long, String) -> Unit,
    val delete: (Long) -> Unit,
    val add: (Long, List<Track>) -> Unit,
    val remove: (Long, String) -> Unit,
    val move: (Long, String, Int) -> Unit,
    val removeFavourite: (String) -> Unit,
)
