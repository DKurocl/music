package ua.olex.music.user

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import ua.olex.music.model.RecordingCandidate
import ua.olex.music.model.RecordingMatcher
import ua.olex.music.model.HistoryEntry
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.UserPlaylist

/** Durable personal library. Cache scans never write here, and file loss never removes saved songs. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserRepository(private val database: UserDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.userData()
    /** Saved metadata for every collected, rated or played song, independent of the history page limit. */
    val catalogue = dao.catalogue().map { rows -> rows.map(RecordingRelations::toSong) }.distinctUntilChanged()
    val favourites = dao.favourites().map { rows -> rows.map(RecordingRelations::toSong) }
    val playlists = dao.playlists().map { rows -> rows.map { row ->
        val songs = row.songs.associateBy { it.id }
        UserPlaylist(row.playlist.id, row.playlist.name, row.entries.sortedBy { it.position }.mapNotNull { songs[it.songId]?.toSong() }, row.playlist.createdAt)
    } }
    val history = dao.history(200).map { rows -> rows.map { HistoryEntry(it.eventId, it.toSong(), it.playedAt) } }

    private suspend fun register(songs: List<SavedSong>, at: Long): List<SavedSong> {
        if (songs.isEmpty()) return emptyList()
        val session = RecordingRegistrar.create(dao, songs, at) { "recording:${java.util.UUID.randomUUID()}" }
        val registered = songs.map { session.register(it) }
        // A later occurrence can confirm a merge with an older identity. Never append a stale FK.
        val canonical = registered.map { it.id }.distinct().associateWith { dao.canonicalId(it) ?: it }
        return registered.map { it.copy(id = canonical.getValue(it.id)) }
    }

    /** Read a source's durable recording without creating data or guessing an ambiguous match. */
    fun reference(track: Track) = reference(SavedSong.from(track))
    /** Resolve an old durable ID through permanent merge aliases. */
    fun reference(id: String): kotlinx.coroutines.flow.Flow<SavedSong?> = dao.observeCanonicalId(id).flatMapLatest { canonical ->
        if (canonical == null) kotlinx.coroutines.flow.flowOf(null) else dao.observeRecording(canonical).map { it?.toSong() }
    }.distinctUntilChanged()
    private fun reference(snapshot: SavedSong): kotlinx.coroutines.flow.Flow<SavedSong?> {
        fun candidates() = dao.observeCandidates(listOf(snapshot.id),
            (listOf(snapshot.songKey) + snapshot.references.map { it.songKey }).distinct().take(32), snapshot.references.map { it.key }.distinct().take(32))
            .mapLatest { rows ->
                val group = RecordingMatcher.group(snapshot.references, rows.map { row ->
                    RecordingCandidate(row.id, row.song.createdAt, row.toSong().references, row.song.songKey, row.song.durationMs)
                })
                group.firstOrNull()?.id?.let { id -> rows.first { it.id == id }.toSong() }
            }
        return if (!snapshot.persistent) candidates().distinctUntilChanged()
        else dao.observeCanonicalId(snapshot.id).flatMapLatest { id ->
            if (id == null) candidates() else dao.observeRecording(id).map { it?.toSong() }
        }.distinctUntilChanged()
    }

    suspend fun toggleFavourite(track: Track, existingId: String? = null) = database.withTransaction {
        val at = now()
        val song = register(listOf(SavedSong.from(track)), at).single()
        val id = existingId?.let { dao.canonicalId(it) } ?: song.id
        if (dao.isFavourite(id)) { dao.removeFavourite(id); false }
        else { dao.addFavourite(FavouriteEntity(song.id, at)); true }
    }

    suspend fun removeFavourite(id: String) = database.withTransaction { dao.removeFavourite(dao.canonicalId(id) ?: id) }

    /** Recording IDs own statistics. A unique old SongKey alias keeps unsaved/local callers readable. */
    val stats = kotlinx.coroutines.flow.combine(dao.recordingStats(), dao.recordingAliases()) { rows, aliases ->
        val values = rows.associate { it.songId to ua.olex.music.model.SongStats(it.plays, it.lastPlayedAt, it.rating ?: 0, it.rating != null) }.toMutableMap()
        aliases.forEach { alias -> values[alias.songId]?.let { values[alias.aliasId] = it } }
        rows.groupBy { it.songKey }.forEach { (key, matches) -> if (matches.size == 1) values[key] = values.getValue(matches.single().songId) }
        values.toMap()
    }

    /** 0 clears this recording's rating, including every linked file and online source. */
    suspend fun setRating(track: Track, stars: Int) = database.withTransaction {
        val at = now()
        val song = register(listOf(SavedSong.from(track)), at).single()
        require(stars in 0..5)
        // Keep a dated clear, otherwise a later source merge could resurrect an older rating.
        dao.setRating(RatingEntity(song.id, stars, at))
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
        val at = now()
        val id = dao.createPlaylist(PlaylistEntity(name = validName(name), createdAt = at))
        append(id, register(tracks.map(SavedSong::from), at), duplicates = false)
        id
    }

    /** Imports stable references atomically; repeated occurrences and missing paths keep their order. */
    suspend fun importPlaylist(name: String, songs: List<SavedSong>): Long = database.withTransaction {
        require(songs.size <= ua.olex.music.model.M3uPlaylist.MAX_ENTRIES)
        require(songs.isNotEmpty() && songs.all { it.id.isNotBlank() && it.references.isNotEmpty() })
        val at = now()
        val id = dao.createPlaylist(PlaylistEntity(name = validName(name), createdAt = at))
        append(id, register(songs, at), duplicates = true)
        id
    }

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, validName(name))
    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    /** Adds songs at the end; songs already in the playlist are skipped unless [duplicates] is true. */
    suspend fun addToPlaylist(id: Long, tracks: List<Track>, duplicates: Boolean = false) = database.withTransaction {
        check(dao.playlistExists(id)) { "Playlist no longer exists" }
        append(id, register(tracks.map(SavedSong::from), now()), duplicates)
    }

    private suspend fun append(id: Long, songs: List<SavedSong>, duplicates: Boolean) {
        val existing = dao.entries(id)
        val ids = existing.mapTo(HashSet()) { it.songId }
        val adding = if (duplicates) songs else songs.distinctBy { it.id }.filterNot { it.id in ids }
        adding.mapIndexed { index, song -> PlaylistEntryEntity(id, song.id, existing.size + index) }.chunked(500).forEach { dao.insertEntries(it) }
    }

    /** Removes the entry at [position] (only if it still holds [songId], so a stale screen removes nothing else). */
    suspend fun removeFromPlaylist(id: Long, songId: String, position: Int? = null) = database.withTransaction {
        val ids = dao.entries(id).map { it.songId }.toMutableList()
        val canonical = dao.canonicalId(songId) ?: songId
        val at = if (position != null) { if (ids.getOrNull(position) != canonical) return@withTransaction; position } else ids.indexOf(canonical)
        if (at < 0) return@withTransaction
        ids.removeAt(at)
        replaceEntries(id, ids)
    }

    /** Re-read within the transaction so a concurrent add/remove is never overwritten by stale UI. */
    suspend fun moveInPlaylist(id: Long, songId: String, delta: Int, position: Int? = null) = database.withTransaction {
        val ids = dao.entries(id).map { it.songId }.toMutableList()
        val canonical = dao.canonicalId(songId) ?: songId
        val from = if (position != null) { if (ids.getOrNull(position) != canonical) return@withTransaction; position } else ids.indexOf(canonical)
        if (from < 0) return@withTransaction
        val to = (from.toLong() + delta).coerceIn(0, ids.lastIndex.toLong()).toInt()
        if (from == to) return@withTransaction
        ids.add(to, ids.removeAt(from))
        replaceEntries(id, ids)
    }

    suspend fun recordPlay(track: Track) = database.withTransaction {
        val at = now()
        val song = register(listOf(SavedSong.from(track)), at).single()
        dao.addHistory(HistoryEntity(songId = song.id, playedAt = at))
    }

    fun lyricOffset(song: SavedSong) = reference(song).flatMapLatest { recording ->
        if (recording == null) kotlinx.coroutines.flow.flowOf(0L) else dao.lyricOffset(recording.id).map { it ?: 0L }
    }.distinctUntilChanged()

    suspend fun setLyricOffset(track: Track, offsetMs: Long) = database.withTransaction {
        val at = now()
        val song = register(listOf(SavedSong.from(track)), at).single()
        dao.setOffset(LyricOffsetEntity(song.id, offsetMs, at))
    }

    /** Assign identity before writing source-independent lyrics, without adding a history event. */
    suspend fun keepReference(track: Track): SavedSong = database.withTransaction { register(listOf(SavedSong.from(track)), now()).single() }

    /** Confirm only indexed matches to saved recordings; a scan never collects unrelated files. */
    suspend fun reconcile(local: ua.olex.music.model.LibraryIndex, saved: List<SavedSong>) = database.withTransaction {
        val bound = ua.olex.music.model.SavedCatalog.bindLocal(local, saved).filter { it.recordingId != null }
        val snapshots = bound.groupBy { it.recordingId }.values.map { copies ->
            val preferred = copies.maxBy { it.format?.rank ?: 0 }
            SavedSong.from(preferred.copy(sources = (copies.mapNotNull(ua.olex.music.model.RecordingSource::from) + preferred.sources).distinctBy { it.key }))
        }
        register(snapshots, now())
        Unit
    }

    /** Superseded IDs remain lyric-file lookup aliases after a confirmed recording merge. */
    suspend fun aliases(id: String): List<String> = dao.aliases(dao.canonicalId(id) ?: id)

    private suspend fun replaceEntries(id: Long, songs: List<String>) {
        dao.clearEntries(id)
        dao.insertEntries(songs.mapIndexed { position, songId -> PlaylistEntryEntity(id, songId, position) })
    }

    private fun validName(name: String) = name.trim().also { require(it.isNotEmpty()) { "Enter a playlist name" } }
}
