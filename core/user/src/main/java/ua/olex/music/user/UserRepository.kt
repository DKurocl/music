package ua.olex.music.user

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import ua.olex.music.model.HistoryEntry
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.UserPlaylist

/** Durable personal library. Cache scans never write here, and file loss never removes saved songs. */
class UserRepository(private val database: UserDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.userData()
    val favourites = dao.favourites().map { rows -> rows.map(UserSongEntity::toSong) }
    val playlists = dao.playlists().map { rows -> rows.map { row ->
        val songs = row.songs.associateBy { it.id }
        UserPlaylist(row.playlist.id, row.playlist.name, row.entries.sortedBy { it.position }.mapNotNull { songs[it.songId]?.toSong() }, row.playlist.createdAt)
    } }
    val history = dao.history(200).map { rows -> rows.map { HistoryEntry(it.eventId, it.song.toSong(), it.playedAt) } }

    suspend fun toggleFavourite(track: Track, existingId: String? = null) = database.withTransaction {
        val song = SavedSong.from(track)
        val id = existingId ?: song.id
        if (dao.isFavourite(id)) dao.removeFavourite(id)
        else {
            dao.saveSongs(listOf(UserSongEntity.from(song)))
            dao.addFavourite(FavouriteEntity(song.id, now()))
        }
    }

    suspend fun removeFavourite(id: String) = dao.removeFavourite(id)

    suspend fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Long = database.withTransaction {
        val id = dao.createPlaylist(PlaylistEntity(name = validName(name), createdAt = now()))
        addToPlaylist(id, tracks)
        id
    }

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, validName(name))
    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    suspend fun addToPlaylist(id: Long, tracks: List<Track>) = database.withTransaction {
        check(dao.playlistExists(id)) { "Playlist no longer exists" }
        val songs = tracks.map(SavedSong::from).distinctBy { it.id }
        songs.chunked(500).forEach { dao.saveSongs(it.map(UserSongEntity::from)) }
        val existing = dao.entries(id)
        val ids = existing.map { it.songId }.toSet()
        dao.insertEntries(songs.filterNot { it.id in ids }.mapIndexed { index, song -> PlaylistEntryEntity(id, song.id, existing.size + index) })
    }

    suspend fun removeFromPlaylist(id: Long, songId: String) = database.withTransaction {
        replaceEntries(id, dao.entries(id).map { it.songId }.filterNot { it == songId })
    }

    /** Re-read within the transaction so a concurrent add/remove is never overwritten by stale UI. */
    suspend fun moveInPlaylist(id: Long, songId: String, delta: Int) = database.withTransaction {
        val ids = dao.entries(id).map { it.songId }.toMutableList()
        val from = ids.indexOf(songId)
        if (from < 0) return@withTransaction
        val to = (from.toLong() + delta).coerceIn(0, ids.lastIndex.toLong()).toInt()
        if (from == to) return@withTransaction
        ids.add(to, ids.removeAt(from))
        replaceEntries(id, ids)
    }

    suspend fun recordPlay(track: Track) = database.withTransaction {
        val song = SavedSong.from(track)
        dao.saveSongs(listOf(UserSongEntity.from(song)))
        dao.addHistory(HistoryEntity(songId = song.id, playedAt = now()))
    }

    fun lyricOffset(song: SavedSong) = dao.lyricOffset(song.id).map { it ?: 0L }
    suspend fun setLyricOffset(track: Track, offsetMs: Long) = database.withTransaction {
        val song = SavedSong.from(track)
        dao.saveSongs(listOf(UserSongEntity.from(song)))
        dao.setOffset(LyricOffsetEntity(song.id, offsetMs))
    }

    private suspend fun replaceEntries(id: Long, songs: List<String>) {
        dao.clearEntries(id)
        dao.insertEntries(songs.mapIndexed { position, songId -> PlaylistEntryEntity(id, songId, position) })
    }

    private fun validName(name: String) = name.trim().also { require(it.isNotEmpty()) { "Enter a playlist name" } }
}
