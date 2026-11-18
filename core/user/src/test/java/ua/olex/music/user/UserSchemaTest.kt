package ua.olex.music.user

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Version 1 is the migration baseline; future upgrades must preserve this exported fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserSchemaTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDatabase::class.java)
    @After fun cleanup() { RuntimeEnvironment.getApplication().deleteDatabase("user-schema.db") }

    @Test fun exportedVersionOneOpensWithoutRebuildingItsData() = runBlocking {
        helper.createDatabase("user-schema.db", 1).use { database ->
            database.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (7, 'Keep this playlist', 1000)")
        }
        helper.runMigrationsAndValidate("user-schema.db", 1, true).close()
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").addMigrations(UserDatabase.MIGRATION_2_3, UserDatabase.MIGRATION_3_4, UserDatabase.MIGRATION_4_5).build()
        try {
            val playlist = UserRepository(database).playlists.first().single()
            assertEquals(7L, playlist.id)
            assertEquals("Keep this playlist", playlist.name)
        } finally { database.close() }
    }

    /** 1 -> 2 adds ratings and smart playlists; playlists and history from version 1 are kept. */
    @Test fun versionOneUpgradesToTwoKeepingDataAndAddingRatings() = runBlocking {
        helper.createDatabase("user-schema.db", 1).use { database ->
            database.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (3, 'Old list', 1000)")
            database.execSQL("INSERT INTO saved_songs (id, songKey, path, title, artist, album, durationMs, artworkUri) VALUES ('s1', 'radiohead|creep', 'Music/creep.flac', 'Creep', 'Radiohead', NULL, 238000, NULL)")
            database.execSQL("INSERT INTO play_history (songId, playedAt) VALUES ('s1', 5000)")
            database.execSQL("INSERT INTO play_history (songId, playedAt) VALUES ('s1', 9000)")
        }
        helper.runMigrationsAndValidate("user-schema.db", 2, true).close()
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").addMigrations(UserDatabase.MIGRATION_2_3, UserDatabase.MIGRATION_3_4, UserDatabase.MIGRATION_4_5).build()
        try {
            val repository = UserRepository(database)
            assertEquals("Old list", repository.playlists.first().single().name)
            val stats = repository.stats.first().getValue("radiohead|creep")
            assertEquals(2, stats.plays); assertEquals(9000L, stats.lastPlayedAt); assertEquals(0, stats.rating)
            val id = repository.saveSmartPlaylist(ua.olex.music.model.SmartPlaylist("Loud", listOf(ua.olex.music.model.SmartRule(ua.olex.music.model.SmartField.PLAYS, ua.olex.music.model.SmartOp.AT_LEAST, "2"))))
            assertEquals("Loud", repository.smartPlaylists.first().single { it.id == id }.name)
        } finally { database.close() }
    }

    /** 2 -> 3 keys entries by position: the same songs, in the same order, and duplicates allowed after. */
    @Test fun versionTwoUpgradesToThreeKeepingPlaylistOrder() = runBlocking {
        helper.createDatabase("user-schema.db", 2).use { database ->
            database.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Mix', 1000)")
            database.execSQL("INSERT INTO saved_songs (id, songKey, path, title, artist, album, durationMs, artworkUri) VALUES ('a', 'x|a', 'Music/a.flac', 'A', 'X', NULL, 1000, NULL)")
            database.execSQL("INSERT INTO saved_songs (id, songKey, path, title, artist, album, durationMs, artworkUri) VALUES ('b', 'x|b', 'Music/b.flac', 'B', 'X', NULL, 1000, NULL)")
            database.execSQL("INSERT INTO playlist_entries (playlistId, songId, position) VALUES (1, 'b', 0)")
            database.execSQL("INSERT INTO playlist_entries (playlistId, songId, position) VALUES (1, 'a', 1)")
        }
        helper.runMigrationsAndValidate("user-schema.db", 3, true, UserDatabase.MIGRATION_2_3).close()
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").addMigrations(UserDatabase.MIGRATION_2_3, UserDatabase.MIGRATION_3_4, UserDatabase.MIGRATION_4_5).build()
        try {
            assertEquals(listOf("b", "a"), database.userData().entries(1).map { it.songId })
            database.userData().insertEntries(listOf(PlaylistEntryEntity(1, "b", 2)))
            assertEquals(listOf("b", "a", "b"), database.userData().entries(1).map { it.songId })
        } finally { database.close() }
    }

    @Test fun versionThreeAddsSourceLinksWithoutChangingAnyPersonalRelation() = runBlocking {
        helper.createDatabase("user-schema.db", 3).use { db ->
            db.execSQL("INSERT INTO saved_songs VALUES ('local-old', 'artist|song', 'Music/song.flac', 'Song', 'Artist', 'Album', 120000, 'content://cover')")
            db.execSQL("INSERT INTO saved_songs VALUES ('online-old', 'artist|song', 'ytm://video', 'Song', 'Artist', 'Album', 121000, 'https://cover')")
            db.execSQL("INSERT INTO saved_songs VALUES ('metadata-only', 'artist|old', '', 'Old', 'Artist', NULL, 100000, NULL)")
            db.execSQL("INSERT INTO playlists VALUES (7, 'Mixed', 100)")
            db.execSQL("INSERT INTO playlist_entries VALUES (7, 'online-old', 0), (7, 'local-old', 1), (7, 'online-old', 2)")
            db.execSQL("INSERT INTO favourites VALUES ('online-old', 200)")
            db.execSQL("INSERT INTO play_history VALUES (17, 'online-old', 300), (18, 'local-old', 400)")
            db.execSQL("INSERT INTO ratings VALUES ('local-old', 4, 500)")
            db.execSQL("INSERT INTO lyric_offsets VALUES ('online-old', -250)")
            db.execSQL("INSERT INTO smart_playlists VALUES (9, 'unchanged-definition', 600)")
        }
        helper.runMigrationsAndValidate("user-schema.db", 4, true, UserDatabase.MIGRATION_3_4).close()
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db")
            .addMigrations(UserDatabase.MIGRATION_3_4, UserDatabase.MIGRATION_4_5).build()
        try {
            val repo = UserRepository(db)
            val playlist = repo.playlists.first().single()
            assertEquals(listOf("online-old", "local-old", "online-old"), playlist.songs.map { it.id })
            assertEquals("online-old", repo.favourites.first().single().id)
            assertEquals(listOf(18L, 17L), repo.history.first().map { it.id })
            assertEquals(-250L, repo.lyricOffset(playlist.songs.first()).first())
            assertEquals(4, repo.stats.first().getValue("local-old").rating)
            val rows = db.userData().allRecordings().associateBy { it.id }
            assertEquals("ytm://video", rows.getValue("online-old").toSong().references.single().uri)
            assertEquals("Music/song.flac", rows.getValue("local-old").toSong().references.single().path)
            assertEquals(null, rows.getValue("local-old").toSong().references.single().uri)
            assertEquals(emptyList<RecordingSourceEntity>(), rows.getValue("metadata-only").sources)
            assertEquals("online-old", db.userData().canonicalId("online-old"))
            db.openHelper.readableDatabase.query("SELECT definition FROM smart_playlists WHERE id=9").use {
                org.junit.Assert.assertTrue(it.moveToFirst()); assertEquals("unchanged-definition", it.getString(0))
            }
        } finally { db.close() }
    }
    @Test fun versionFourKeepsOldPlayCountsAndAcceptsMonotonicTimeCheckpoints() = runBlocking {
        helper.createDatabase("user-schema.db", 4).use { db ->
            db.execSQL("INSERT INTO saved_songs VALUES ('old', 'artist|song', '', 'Song', 'Artist', NULL, 120000, NULL, 0)")
            db.execSQL("INSERT INTO play_history VALUES (17, 'old', 300)")
        }
        helper.runMigrationsAndValidate("user-schema.db", 5, true, UserDatabase.MIGRATION_4_5).close()
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db")
            .addMigrations(UserDatabase.MIGRATION_4_5).build()
        try {
            val repo = UserRepository(db)
            val entry = repo.historySince(0).first().single()
            assertEquals(17L, entry.id)
            assertEquals(null, entry.listenedMs)
            val dao = db.userData()
            dao.updateListening(17, 12_000)
            dao.updateListening(17, 7_000)
            assertEquals(12_000L, repo.historySince(0).first().single().listenedMs)
        } finally { db.close() }
    }

}
