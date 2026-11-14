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
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java, "user-schema.db").build()
        try {
            val playlist = UserRepository(database).playlists.first().single()
            assertEquals(7L, playlist.id)
            assertEquals("Keep this playlist", playlist.name)
        } finally { database.close() }
    }
}
