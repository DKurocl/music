package ua.olex.music.user

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import ua.olex.music.model.SavedSong

/** User data has an exported schema and no destructive migration fallback. */
@Database(
    entities = [UserSongEntity::class, FavouriteEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, HistoryEntity::class, LyricOffsetEntity::class,
        RatingEntity::class, SmartPlaylistEntity::class, RecordingSourceEntity::class, RecordingAliasEntity::class],
    version = 5, exportSchema = true,
    // Version 2 only adds tables (ratings, smart playlists): Room migrates it from the exported schemas.
    // Version 3 keys playlist entries by position so a song can be added twice (MIGRATION_2_3).
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userData(): UserDao

    companion object {
        fun open(context: Context): UserDatabase = Room.databaseBuilder(context.applicationContext, UserDatabase::class.java, "user.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN listenedMs INTEGER")
            }
        }

        /** Preserve every legacy ID and relation while giving recordings independent source links. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_songs ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE lyric_offsets ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS song_sources (
                    songId TEXT NOT NULL, sourceKey TEXT NOT NULL, kind INTEGER NOT NULL, songKey TEXT NOT NULL,
                    uri TEXT, path TEXT, durationMs INTEGER NOT NULL, musicBrainzId TEXT, qualityRank INTEGER NOT NULL,
                    clipStartMs INTEGER, clipEndMs INTEGER, artworkUri TEXT, addedAt INTEGER NOT NULL,
                    PRIMARY KEY(songId, sourceKey), FOREIGN KEY(songId) REFERENCES saved_songs(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_sources_songId ON song_sources(songId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_sources_sourceKey ON song_sources(sourceKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_song_sources_songKey ON song_sources(songKey)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS recording_aliases (
                    aliasId TEXT NOT NULL PRIMARY KEY, songId TEXT NOT NULL,
                    FOREIGN KEY(songId) REFERENCES saved_songs(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recording_aliases_songId ON recording_aliases(songId)")
                db.execSQL("INSERT INTO recording_aliases(aliasId, songId) SELECT id, id FROM saved_songs")
                // Legacy local snapshots contain a path, not a current MediaStore URI. Never guess one.
                db.execSQL("""INSERT INTO song_sources(songId, sourceKey, kind, songKey, uri, path, durationMs,
                    musicBrainzId, qualityRank, clipStartMs, clipEndMs, artworkUri, addedAt)
                    SELECT id, CASE WHEN path LIKE 'ytm://%' THEN 'youtube:' || substr(path, 7) ELSE 'file:' || path END,
                    CASE WHEN path LIKE 'ytm://%' THEN 1 WHEN id LIKE 'm3u8-path:%' THEN 2 ELSE 0 END, songKey,
                    CASE WHEN path LIKE 'ytm://%' THEN path ELSE NULL END,
                    CASE WHEN path LIKE 'ytm://%' THEN NULL ELSE path END,
                    MAX(durationMs, 0), NULL, 0, NULL, NULL, artworkUri, 0 FROM saved_songs WHERE path <> ''""")
            }
        }

        /** Same rows, new primary key (playlistId, position); the old unique position index becomes the key. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_entries_new` (`playlistId` INTEGER NOT NULL, `songId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlistId`, `position`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`songId`) REFERENCES `saved_songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("INSERT INTO `playlist_entries_new` (`playlistId`, `songId`, `position`) SELECT `playlistId`, `songId`, `position` FROM `playlist_entries`")
                db.execSQL("DROP TABLE `playlist_entries`")
                db.execSQL("ALTER TABLE `playlist_entries_new` RENAME TO `playlist_entries`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entries_songId` ON `playlist_entries` (`songId`)")
            }
        }
    }
}

/** Recording metadata survives source loss; legacy primary keys remain valid after migration. */
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
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
) {
    fun toSong(sources: List<ua.olex.music.model.RecordingSource> = emptyList()) = SavedSong(id, songKey, path, title, artist, album, durationMs, artworkUri, sources, createdAt)
    companion object {
        fun from(song: SavedSong) = UserSongEntity(song.id, song.songKey, song.path, song.title, song.artist, song.album, song.durationMs, song.artworkUri, song.createdAt)
    }
}

/** Child rows retain unavailable files and online reserves independently of display metadata. */
@Entity(tableName = "song_sources", primaryKeys = ["songId", "sourceKey"],
    indices = [Index("songId"), Index("sourceKey"), Index("songKey")],
    foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class RecordingSourceEntity(
    val songId: String, val sourceKey: String, val kind: Int, val songKey: String, val uri: String?, val path: String?,
    val durationMs: Long, val musicBrainzId: String?, val qualityRank: Long, val clipStartMs: Long?, val clipEndMs: Long?,
    val artworkUri: String?, val addedAt: Long,
) {
    fun toSource() = ua.olex.music.model.RecordingSource(ua.olex.music.model.RecordingSourceKind.entries.first { it.value == kind },
        songKey, uri, path, durationMs, musicBrainzId, qualityRank, clipStartMs, clipEndMs, artworkUri)
    companion object {
        fun from(id: String, source: ua.olex.music.model.RecordingSource, at: Long) = RecordingSourceEntity(id, source.key,
            source.kind.value, source.songKey, source.uri, source.path, source.durationMs, source.musicBrainzId,
            source.qualityRank, source.clipStartMs, source.clipEndMs, source.artworkUri, at)
    }
}

/** Old durable IDs remain valid after two confirmed records become one recording. */
@Entity(tableName = "recording_aliases", indices = [Index("songId")],
    foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class RecordingAliasEntity(@PrimaryKey val aliasId: String, val songId: String)

/** Room reads identity and every source within the same transaction. */
data class RecordingRelations(@Embedded val song: UserSongEntity,
    @Relation(parentColumn = "id", entityColumn = "songId") val sources: List<RecordingSourceEntity>) {
    val id: String get() = song.id
    fun toSong() = song.toSong(sources.sortedWith(compareBy<RecordingSourceEntity> { it.addedAt }.thenBy { it.sourceKey }).map { it.toSource() })
}

/** A saved favourite, separate from its metadata so refreshes cannot reset user choices. */
@Entity(tableName = "favourites", foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class FavouriteEntity(@PrimaryKey val songId: String, val addedAt: Long)

/** User-created playlist header; deleting it cascades only to its own entries. */
@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)

/** One row per place in a playlist: a song may appear more than once; positions are rewritten atomically. */
@Entity(
    tableName = "playlist_entries", primaryKeys = ["playlistId", "position"],
    indices = [Index("songId")],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistEntryEntity(val playlistId: Long, val songId: String, val position: Int)

/** Append-only playback starts; the UI reads a bounded recent window without deleting history. */
@Entity(tableName = "play_history", indices = [Index("songId"), Index("playedAt")], foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class HistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val songId: String, val playedAt: Long, val listenedMs: Long? = null)

/** A signed per-song lyrics adjustment; its lifetime is independent of rescanning music. */
@Entity(tableName = "lyric_offsets", foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class LyricOffsetEntity(@PrimaryKey val songId: String, val offsetMs: Long, @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0)

/** Stars 1 to 5; 0 is a dated clear retained across later merges. No row is also unrated. */
@Entity(tableName = "ratings", foreignKeys = [ForeignKey(entity = UserSongEntity::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE)])
data class RatingEntity(@PrimaryKey val songId: String, val stars: Int, val ratedAt: Long)

/** A user's smart playlist: its definition only (SmartPlaylistCodec); its songs are computed. */
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val definition: String, val createdAt: Long)

/** Counts are attached to a durable recording, so alternate sources never multiply its totals. */
data class RecordingStats(val songId: String, val songKey: String, val plays: Int, val lastPlayedAt: Long?, val rating: Int?,
    val favouriteSince: Long?)

/** Room loads both relations under the same transaction; entry order is applied by the repository. */
data class PlaylistRelations(
    @Embedded val playlist: PlaylistEntity,
    @Relation(parentColumn = "id", entityColumn = "playlistId") val entries: List<PlaylistEntryEntity>,
    @Relation(parentColumn = "id", entityColumn = "id", entity = UserSongEntity::class, associateBy = Junction(value = PlaylistEntryEntity::class, parentColumn = "playlistId", entityColumn = "songId"))
    val songs: List<RecordingRelations>,
)

/** One history event joined to its durable display metadata. */
data class HistoryRow(val eventId: Long, val playedAt: Long, val listenedMs: Long?, @Embedded val song: UserSongEntity,
    @Relation(parentColumn = "id", entityColumn = "songId") val sources: List<RecordingSourceEntity>) {
    fun toSong() = song.toSong(sources.sortedWith(compareBy<RecordingSourceEntity> { it.addedAt }.thenBy { it.sourceKey }).map { it.toSource() })
}

/** All writes involving multiple tables are orchestrated in UserRepository transactions. */
@Dao
interface UserDao {
    @Query("""
        WITH history AS (
            SELECT songId, COUNT(*) AS plays, MAX(playedAt) AS lastPlayedAt
            FROM play_history GROUP BY songId
        )
        SELECT s.id AS songId, s.songKey, COALESCE(h.plays, 0) AS plays, h.lastPlayedAt,
            r.stars AS rating, f.addedAt AS favouriteSince
        FROM saved_songs s LEFT JOIN history h ON h.songId = s.id LEFT JOIN ratings r ON r.songId = s.id
            LEFT JOIN favourites f ON f.songId = s.id
    """)
    fun recordingStats(): Flow<List<RecordingStats>>
    @Query("""
        WITH matches AS (
            SELECT id FROM saved_songs WHERE id IN (:keys)
            UNION SELECT id FROM saved_songs WHERE songKey IN (:keys)
            UNION SELECT songId FROM recording_aliases WHERE aliasId IN (:keys)
        )
        SELECT s.id AS songId, s.songKey,
            (SELECT COUNT(*) FROM play_history h WHERE h.songId = s.id) AS plays,
            (SELECT MAX(playedAt) FROM play_history h WHERE h.songId = s.id) AS lastPlayedAt,
            r.stars AS rating, f.addedAt AS favouriteSince
        FROM matches m JOIN saved_songs s ON s.id = m.id
            LEFT JOIN ratings r ON r.songId = s.id LEFT JOIN favourites f ON f.songId = s.id
    """)
    suspend fun recordingStatsFor(keys: List<String>): List<RecordingStats>
    /** Matchers order the compatible candidates when choosing a recording ID. */
    @Transaction @Query("SELECT * FROM saved_songs")
    suspend fun allRecordings(): List<RecordingRelations>
    @Transaction @Query("SELECT * FROM saved_songs ORDER BY createdAt, id") fun observeRecordings(): Flow<List<RecordingRelations>>
    @Transaction @Query("SELECT * FROM saved_songs WHERE id = :id") suspend fun recording(id: String): RecordingRelations?

    @Transaction @Query("""
        SELECT * FROM saved_songs WHERE id IN (:ids)
        OR id IN (SELECT songId FROM recording_aliases WHERE aliasId IN (:ids))
        OR songKey IN (:keys)
        OR id IN (SELECT songId FROM song_sources WHERE songKey IN (:keys) OR sourceKey IN (:sources))
        ORDER BY createdAt, id
    """)
    suspend fun recordingCandidates(ids: List<String>, keys: List<String>, sources: List<String>): List<RecordingRelations>

    @Transaction @Query("""
        SELECT * FROM saved_songs WHERE id IN (:ids)
        OR id IN (SELECT songId FROM recording_aliases WHERE aliasId IN (:ids))
        OR songKey IN (:keys)
        OR id IN (SELECT songId FROM song_sources WHERE songKey IN (:keys) OR sourceKey IN (:sources))
        ORDER BY createdAt, id
    """)
    fun observeCandidates(ids: List<String>, keys: List<String>, sources: List<String>): Flow<List<RecordingRelations>>

    @Query("SELECT id FROM saved_songs WHERE id = :id UNION SELECT songId FROM recording_aliases WHERE aliasId = :id LIMIT 1")
    suspend fun canonicalId(id: String): String?
    @Query("""
        SELECT aliasId, MIN(songId) AS songId FROM (
            SELECT id AS aliasId, id AS songId FROM saved_songs WHERE id IN (:ids)
            UNION ALL
            SELECT aliasId, songId FROM recording_aliases WHERE aliasId IN (:ids)
        ) GROUP BY aliasId
    """)
    suspend fun canonicalIds(ids: List<String>): List<RecordingAliasEntity>
    @Query("SELECT aliasId FROM recording_aliases WHERE songId = :id ORDER BY aliasId")
    suspend fun aliases(id: String): List<String>
    @Query("SELECT * FROM recording_aliases") fun recordingAliases(): Flow<List<RecordingAliasEntity>>
    @Query("SELECT * FROM recording_aliases") suspend fun allAliases(): List<RecordingAliasEntity>
    @Query("SELECT id FROM saved_songs WHERE id = :id UNION SELECT songId FROM recording_aliases WHERE aliasId = :id LIMIT 1")
    fun observeCanonicalId(id: String): Flow<String?>
    @Transaction @Query("SELECT * FROM saved_songs WHERE id = :id") fun observeRecording(id: String): Flow<RecordingRelations?>

    @Upsert suspend fun saveSources(sources: List<RecordingSourceEntity>)
    @Upsert suspend fun saveAliases(aliases: List<RecordingAliasEntity>)
    @Query("UPDATE recording_aliases SET songId = :keep WHERE songId IN (:others)") suspend fun redirectAliases(keep: String, others: List<String>)
    @Query("UPDATE playlist_entries SET songId = :keep WHERE songId IN (:others)") suspend fun redirectPlaylistEntries(keep: String, others: List<String>)
    @Query("UPDATE play_history SET songId = :keep WHERE songId IN (:others)") suspend fun redirectHistory(keep: String, others: List<String>)
    @Query("DELETE FROM saved_songs WHERE id IN (:ids)") suspend fun deleteRecordings(ids: List<String>)
    @Query("SELECT MAX(addedAt) FROM favourites WHERE songId IN (:ids)") suspend fun latestFavourite(ids: List<String>): Long?
    @Query("SELECT * FROM ratings WHERE songId IN (:ids) ORDER BY ratedAt DESC, (songId = :keep) DESC, songId LIMIT 1")
    suspend fun latestRating(ids: List<String>, keep: String): RatingEntity?
    @Query("SELECT * FROM lyric_offsets WHERE songId IN (:ids) ORDER BY updatedAt DESC, (songId = :keep) DESC, songId LIMIT 1")
    suspend fun latestOffset(ids: List<String>, keep: String): LyricOffsetEntity?

    @Transaction @Query("""
        SELECT * FROM saved_songs WHERE id IN (
            SELECT songId FROM favourites UNION SELECT songId FROM playlist_entries
            UNION SELECT songId FROM play_history UNION SELECT songId FROM ratings WHERE stars > 0 UNION SELECT songId FROM lyric_offsets
        ) ORDER BY id
    """)
    fun catalogue(): Flow<List<RecordingRelations>>

    @Transaction @Query("SELECT saved_songs.* FROM saved_songs JOIN favourites ON saved_songs.id = favourites.songId ORDER BY favourites.addedAt DESC, saved_songs.id")
    fun favourites(): Flow<List<RecordingRelations>>
    @Transaction @Query("SELECT * FROM playlists ORDER BY createdAt DESC, id DESC")
    fun playlists(): Flow<List<PlaylistRelations>>
    @Transaction @Query("SELECT play_history.id AS eventId, play_history.playedAt, play_history.listenedMs, saved_songs.* FROM play_history JOIN saved_songs ON saved_songs.id = play_history.songId ORDER BY play_history.playedAt DESC, play_history.id DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<HistoryRow>>

    @Transaction
    @Query("SELECT play_history.id AS eventId, play_history.playedAt, play_history.listenedMs, saved_songs.* FROM play_history JOIN saved_songs ON saved_songs.id = play_history.songId WHERE playedAt >= :start ORDER BY playedAt DESC, play_history.id DESC")
    fun historySince(start: Long): Flow<List<HistoryRow>>
    @Query("SELECT offsetMs FROM lyric_offsets WHERE songId = :songId") fun lyricOffset(songId: String): Flow<Long?>

    @Upsert suspend fun saveSongs(songs: List<UserSongEntity>)
    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE songId = :songId)") suspend fun isFavourite(songId: String): Boolean
    @Query("SELECT * FROM favourites WHERE songId IN (:ids)") suspend fun favouriteTimes(ids: List<String>): List<FavouriteEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavourite(favourite: FavouriteEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavourites(favourites: List<FavouriteEntity>)
    @Query("DELETE FROM favourites WHERE songId = :songId") suspend fun removeFavourite(songId: String)
    @Query("DELETE FROM favourites WHERE songId IN (:ids)") suspend fun removeFavourites(ids: List<String>)
    @Insert suspend fun createPlaylist(playlist: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :id)") suspend fun playlistExists(id: Long): Boolean
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position") suspend fun entries(id: Long): List<PlaylistEntryEntity>
    @Query("DELETE FROM playlist_entries WHERE playlistId = :id") suspend fun clearEntries(id: Long)
    @Insert suspend fun insertEntries(entries: List<PlaylistEntryEntity>)
    @Insert suspend fun addHistory(history: HistoryEntity): Long
    @Query("UPDATE play_history SET listenedMs = :millis WHERE id = :id AND :millis > COALESCE(listenedMs, 0)")
    suspend fun updateListening(id: Long, millis: Long)
    @Upsert suspend fun setOffset(offset: LyricOffsetEntity)

    @Upsert suspend fun setRating(rating: RatingEntity)
    @Query("SELECT * FROM smart_playlists ORDER BY createdAt, id") fun smartPlaylists(): Flow<List<SmartPlaylistEntity>>
    @Insert suspend fun addSmartPlaylist(p: SmartPlaylistEntity): Long
    @Query("UPDATE smart_playlists SET definition = :definition WHERE id = :id") suspend fun updateSmartPlaylist(id: Long, definition: String)
    @Query("DELETE FROM smart_playlists WHERE id = :id") suspend fun deleteSmartPlaylist(id: Long)
}
