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

    /** Plays, last play and rating per song identity (SongKey value). */
    val stats = dao.stats().map { rows -> rows.associate { it.songKey to ua.olex.music.model.SongStats(it.plays, it.lastPlayedAt, it.rating ?: 0) } }

    /** Rates a song 1 to 5 stars; 0 clears the rating for every saved copy of the song. */
    suspend fun setRating(track: Track, stars: Int) = database.withTransaction {
        val song = SavedSong.from(track)
        dao.clearRatings(song.songKey)
        if (stars in 1..5) {
            dao.saveSongs(listOf(UserSongEntity.from(song)))
            dao.setRating(RatingEntity(song.id, stars, now()))
        }
    }

    val smartPlaylists = dao.smartPlaylists().map { rows -> rows.mapNotNull { ua.olex.music.model.SmartPlaylistCodec.decode(it.definition, it.id) } }
    suspend fun saveSmartPlaylist(p: ua.olex.music.model.SmartPlaylist): Long {
        require(p.name.isNotBlank()) { "Enter a playlist name" }
        val definition = ua.olex.music.model.SmartPlaylistCodec.encode(p.copy(name = p.name.trim()))
        val id = p.id
        return if (id == null) dao.addSmartPlaylist(SmartPlaylistEntity(definition = definition, createdAt = now()))
        else { dao.updateSmartPlaylist(id, definition); id }
    }
    suspend fun deleteSmartPlaylist(id: Long) = dao.deleteSmartPlaylist(id)

    suspend fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Long = database.withTransaction {
        val id = dao.createPlaylist(PlaylistEntity(name = validName(name), createdAt = now()))
        addToPlaylist(id, tracks)
        id
    }

    /** Imports complete durable references atomically, retaining unavailable files and first occurrence order. */
    suspend fun importPlaylist(name: String, songs: List<SavedSong>): Long = database.withTransaction {
        require(songs.size <= ua.olex.music.model.M3uPlaylist.MAX_ENTRIES)
        val ordered = songs.distinctBy { it.id }
        require(ordered.isNotEmpty() && ordered.all { it.id.isNotBlank() && it.path.isNotBlank() })
        val id = dao.createPlaylist(PlaylistEntity(name = validName(name), createdAt = now()))
        ordered.chunked(500).forEach { dao.saveSongs(it.map(UserSongEntity::from)) }
        ordered.mapIndexed { position, song -> PlaylistEntryEntity(id, song.id, position) }.chunked(500).forEach { dao.insertEntries(it) }
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
