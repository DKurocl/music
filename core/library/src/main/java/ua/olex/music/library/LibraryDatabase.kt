package ua.olex.music.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.AudioFormat
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

/**
 * A track as the library knows it. This database is a cache of what is on the phone: it can be
 * dropped and rebuilt by a scan at any time. What cannot be rebuilt (playlists, play counts,
 * favourites) must live in its own database with real migrations (docs/ARCHITECTURE.md).
 */
@Entity(tableName = "tracks", indices = [Index("songKey"), Index("album"), Index("artist")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val artworkUri: String?,
    val codec: String?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val bitrateKbps: Int?,
    val path: String?,
    /** [ua.olex.music.model.SongKey] of the track, to find the same song from another source. */
    val songKey: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    /** The scan that last saw the file; rows left behind by a scan are files that are gone. */
    val scanId: Long,
    val genre: String? = null,
) {
    fun toTrack() = Track(
        id = TrackId(id), uri = uri, title = title, artist = artist, album = album, albumArtist = albumArtist,
        durationMs = durationMs, trackNumber = trackNumber, discNumber = discNumber, year = year, artworkUri = artworkUri,
        format = codec?.let { AudioFormat(it, sampleRateHz, bitDepth, bitrateKbps) }, path = path, genre = genre,
    )
}

/** External-content FTS: Room's triggers index tracks without storing a second copy of their tags. */
@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=2"],
    prefix = [2, 3, 4],
)
@Entity(tableName = "track_search")
data class TrackSearchEntity(val title: String, val artist: String, val album: String?, val albumArtist: String?)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT tracks.* FROM tracks JOIN track_search ON tracks.rowid = track_search.rowid WHERE track_search MATCH :expression")
    fun search(expression: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE songKey = :key")
    suspend fun byKey(key: String): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id LIKE :prefix || ':%' AND scanId != :scanId")
    suspend fun deleteNotSeen(prefix: String, scanId: Long): Int

    /** Puts a finished scan in place in one step, so the list never shows a half-scanned library. */
    @Transaction
    suspend fun replace(prefix: String, scanId: Long, tracks: List<TrackEntity>) {
        tracks.chunked(500).forEach { upsert(it) }
        deleteNotSeen(prefix, scanId)
    }
}

@Database(entities = [TrackEntity::class, TrackSearchEntity::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                // A cache: a schema change rebuilds it from the files.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
