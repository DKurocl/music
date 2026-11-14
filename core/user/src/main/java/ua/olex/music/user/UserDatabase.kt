package ua.olex.music.user

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.SavedSong

/** User data has an exported schema and no destructive migration fallback. */
@Database(
    entities = [UserSongEntity::class, FavouriteEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, HistoryEntity::class, LyricOffsetEntity::class],
    version = 1, exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userData(): UserDao

    companion object {
        fun open(context: Context): UserDatabase = Room.databaseBuilder(context.applicationContext, UserDatabase::class.java, "user.db").build()
    }
}

/** Metadata survives file deletion; identity is SongKey plus path, never a MediaStore row id. */
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
}
