package ua.olex.music.user

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.SavedSong

/** User data has an exported schema and no destructive migration fallback. */
@Database(
    entities = [UserSongEntity::class, FavouriteEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, HistoryEntity::class, LyricOffsetEntity::class,
        RatingEntity::class, SmartPlaylistEntity::class],
    version = 2, exportSchema = true,
    // Version 2 only adds tables (ratings, smart playlists): Room migrates it from the exported schemas.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userData(): UserDao

    companion object {
        fun open(context: Context): UserDatabase = Room.databaseBuilder(context.applicationContext, UserDatabase::class.java, "user.db").build()
    }
}

/** Metadata survives deletion: normal identities use SongKey/path; unresolved imports use path identity. */
@Entity(tableName = "saved_songs", indices = [Index("songKey")])
data class UserSongEntity(
    @PrimaryKey val id: String,
    val songKey: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
) {
    fun toSong() = SavedSong(id, songKey, path, title, artist, album, durationMs, artworkUri)
    companion object {
        fun from(song: SavedSong) = UserSongEntity(song.id, song.songKey, song.path, song.title, song.artist, song.album, song.durationMs, song.artworkUri)
    }
}

/** A saved favourite, separate from its metadata so refreshes cannot reset user choices. */
@Entity(tableName = "favourites", foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class FavouriteEntity(@PrimaryKey val songId: String, val addedAt: Long)

/** User-created playlist header; deleting it cascades only to its own entries. */
@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)

/** Each song occurs once per playlist; position is rewritten atomically when moving entries. */
@Entity(
    tableName = "playlist_entries", primaryKeys = ["playlistId", "songId"],
    indices = [Index("songId"), Index(value = ["playlistId", "position"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistEntryEntity(val playlistId: Long, val songId: String, val position: Int)

/** Append-only playback starts; the UI reads a bounded recent window without deleting history. */
@Entity(tableName = "play_history", indices = [Index("songId"), Index("playedAt")], foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class HistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val songId: String, val playedAt: Long)

/** A signed per-song lyrics adjustment; its lifetime is independent of rescanning music. */
@Entity(tableName = "lyric_offsets", foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class LyricOffsetEntity(@PrimaryKey val songId: String, val offsetMs: Long)

/** A song's star rating, 1 to 5; no row means unrated. Kept apart from metadata, like favourites. */
@Entity(tableName = "ratings", foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class RatingEntity(@PrimaryKey val songId: String, val stars: Int, val ratedAt: Long)

/** A user's smart playlist: its definition only (SmartPlaylistCodec); its songs are computed. */
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val definition: String, val createdAt: Long)

/** Listening per song identity (SongKey): plays, the last one, and the rating, for smart playlists. */
data class SongKeyStats(val songKey: String, val plays: Int, val lastPlayedAt: Long?, val rating: Int?)

/** Room loads both relations under the same transaction; entry order is applied by the repository. */
data class PlaylistRelations(
    @Embedded val playlist: PlaylistEntity,
    @Relation(parentColumn = "id", entityColumn = "playlistId") val entries: List<PlaylistEntryEntity>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(value = PlaylistEntryEntity::class, parentColumn = "playlistId", entityColumn = "songId"))
    val songs: List<UserSongEntity>,
)

/** One history event joined to its durable display metadata. */
data class HistoryRow(val eventId: Long, val playedAt: Long, @Embedded val song: UserSongEntity)

/** All writes involving multiple tables are orchestrated in UserRepository transactions. */
@Dao
interface UserDao {
    @Query("SELECT saved_songs.* FROM saved_songs JOIN favourites ON saved_songs.id = favourites.songId ORDER BY favourites.addedAt DESC, saved_songs.id")
    fun favourites(): Flow<List<UserSongEntity>>
    @Transaction @Query("SELECT * FROM playlists ORDER BY createdAt DESC, id DESC")
    fun playlists(): Flow<List<PlaylistRelations>>
    @Query("SELECT play_history.id AS eventId, play_history.playedAt, saved_songs.* FROM play_history JOIN saved_songs ON saved_songs.id = play_history.songId ORDER BY play_history.playedAt DESC, play_history.id DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<HistoryRow>>
    @Query("SELECT offsetMs FROM lyric_offsets WHERE songId = :songId") fun lyricOffset(songId: String): Flow<Long?>

    @Upsert suspend fun saveSongs(songs: List<UserSongEntity>)
    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE songId = :songId)") suspend fun isFavourite(songId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavourite(favourite: FavouriteEntity)
    @Query("DELETE FROM favourites WHERE songId = :songId") suspend fun removeFavourite(songId: String)
    @Insert suspend fun createPlaylist(playlist: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :id)") suspend fun playlistExists(id: Long): Boolean
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position") suspend fun entries(id: Long): List<PlaylistEntryEntity>
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id") suspend fun clearEntries(id: Long)
    @Insert suspend fun insertEntries(entries: List<PlaylistEntryEntity>)
    @Insert suspend fun addHistory(history: HistoryEntity)
    @Upsert suspend fun setOffset(offset: LyricOffsetEntity)

    /**
     * Plays and ratings grouped by song identity, so a moved or re-ripped file keeps its history.
     * A song identity with several saved paths takes the sum of plays and the best rating.
     */
    @Query("""
        SELECT s.songKey AS songKey,
               (SELECT COUNT(*) FROM play_history h JOIN saved_songs s2 ON s2.id = h.songId WHERE s2.songKey = s.songKey) AS plays,
               (SELECT MAX(h.playedAt) FROM play_history h JOIN saved_songs s2 ON s2.id = h.songId WHERE s2.songKey = s.songKey) AS lastPlayedAt,
               (SELECT MAX(r.stars) FROM ratings r JOIN saved_songs s2 ON s2.id = r.songId WHERE s2.songKey = s.songKey) AS rating
        FROM saved_songs s GROUP BY s.songKey
    """)
    fun stats(): Flow<List<SongKeyStats>>
    @Upsert suspend fun setRating(rating: RatingEntity)
    @Query("DELETE FROM ratings WHERE songId IN (SELECT id FROM saved_songs WHERE songKey = :songKey)") suspend fun clearRatings(songKey: String)
    @Query("SELECT * FROM smart_playlists ORDER BY createdAt, id") fun smartPlaylists(): Flow<List<SmartPlaylistEntity>>
    @Insert suspend fun addSmartPlaylist(p: SmartPlaylistEntity): Long
    @Query("UPDATE smart_playlists SET definition = :definition WHERE id = :id") suspend fun updateSmartPlaylist(id: Long, definition: String)
    @Query("DELETE FROM smart_playlists WHERE id = :id") suspend fun deleteSmartPlaylist(id: Long)
}
