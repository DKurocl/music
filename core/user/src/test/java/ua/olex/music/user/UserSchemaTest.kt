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
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").addMigrations(UserDatabase.MIGRATION_2_3).build()
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
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").addMigrations(UserDatabase.MIGRATION_2_3).build()
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
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").addMigrations(UserDatabase.MIGRATION_2_3).build()
        try {
            assertEquals(listOf("b", "a"), database.userData().entries(1).map { it.songId })
            database.userData().insertEntries(listOf(PlaylistEntryEntity(1, "b", 2)))
            assertEquals(listOf("b", "a", "b"), database.userData().entries(1).map { it.songId })
        } finally { database.close() }
    }
}
