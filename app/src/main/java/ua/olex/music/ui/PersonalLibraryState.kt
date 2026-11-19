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

/** Keep resolutions for just the current history window and catalogue. */
internal class HistoryRows(private val library: LibraryIndex) {
    private var previous: Map<String, SavedSongRow> = emptyMap()

    fun resolve(events: List<HistoryEntry>): List<SavedSongRow> {
        val next = LinkedHashMap<String, SavedSongRow>()
        for (event in events) {
            val song = event.song
            next.getOrPut(song.id) {
                previous[song.id]?.takeIf { it.song == song } ?: SavedSongRow(song, song.resolve(library))
            }
        }
        previous = next
        return next.values.toList()
    }
}

/** Resolved once per library/user-data change, rather than on every player-state emission. */
data class PersonalLibraryState(
    val favourites: List<SavedSongRow> = emptyList(),
    val playlists: List<PlaylistUi> = emptyList(),
    val history: List<SavedSongRow> = emptyList(),
    /** Confirmed current file per linked video URI, prepared off main for row source marks. */
    val localCopies: Map<String, Track> = emptyMap(),
    val favouriteByTrackId: Map<String, SavedSong> = emptyMap(),
) {
    companion object {
        fun resolve(library: LibraryIndex, favourites: List<SavedSong>, playlists: List<UserPlaylist>, history: List<HistoryEntry>, aliases: Map<String, String> = emptyMap()): PersonalLibraryState {
            fun row(song: SavedSong) = SavedSongRow(song, song.resolve(library))
            val byRecording = favourites.associateBy { it.id }
            val physical = (library.songs + library.folders.flatMap { it.tracks }).distinctBy { it.id }
            val linked = physical.mapNotNull { track ->
                val id = track.recordingId?.let { aliases[it] ?: it }
                byRecording[id]?.let { track.id.value to it }
            }.toMap() + aliases.mapNotNull { (alias, target) -> byRecording[target]?.let { alias to it } }.toMap()
            val favouriteRows = favourites.map(::row)
            val lookup = linked.toMutableMap().apply {
                for (row in favouriteRows) {
                    put(row.song.id, row.song)
                    if (!row.song.persistent) row.track?.let { put(it.id.value, row.song) }
                    row.song.references.filter { it.kind == ua.olex.music.model.RecordingSourceKind.YOUTUBE }
                        .forEach { put("yt:${it.uri!!.removePrefix(SavedSong.ONLINE_PREFIX)}", row.song) }
                }
            }
            return PersonalLibraryState(
                favouriteRows,
                playlists.map { PlaylistUi(it.id, it.name, it.songs.map(::row)) },
                HistoryRows(library).resolve(history),
                physical.filterNot { it.uri.startsWith(SavedSong.ONLINE_PREFIX) }.flatMap { track ->
                    track.sources.filter { it.kind == ua.olex.music.model.RecordingSourceKind.YOUTUBE && it.uri != null }
                        .map { it.uri!! to track }
                }.groupBy({ it.first }, { it.second }).mapValues { (_, copies) -> copies.maxBy { it.format?.rank ?: 0 } },
                lookup,
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
    val removeWithUndo: (suspend (Long, String, Int) -> (suspend () -> Boolean)?)? = null,
)
