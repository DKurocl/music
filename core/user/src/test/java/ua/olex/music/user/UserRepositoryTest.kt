package ua.olex.music.user

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ua.olex.music.model.SavedSong
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: UserDatabase
    private lateinit var repository: UserRepository
    private var time = 1_000L
    private val a = track(1)
    private val b = track(2)
    private val c = track(3)

    @Before fun open() {
        context.deleteDatabase("user-test.db")
        reopen()
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, UserDatabase::class.java, "user-test.db").build()
        repository = UserRepository(database) { time++ }
    }

    @After fun close() { database.close(); context.deleteDatabase("user-test.db") }

    @Test fun duplicatesOnPurposeAndPositionalEdits() = runBlocking {
        val id = repository.createPlaylist("Loop", listOf(a))
        repository.addToPlaylist(id, listOf(b, a), duplicates = true)
        assertEquals(listOf("Song 1", "Song 2", "Song 1"), names())
        repository.removeFromPlaylist(id, SavedSong.from(a).id, position = 2)
        assertEquals(listOf("Song 1", "Song 2"), names())
        repository.addToPlaylist(id, listOf(a), duplicates = true)
        repository.moveInPlaylist(id, SavedSong.from(a).id, -1, position = 2)
        assertEquals(listOf("Song 1", "Song 1", "Song 2"), names())
        assertEquals(listOf(0, 1, 2), database.userData().entries(id).map { it.position })
    }

    @Test fun playlistEditsPreserveOrderAndDeduplicateAdds() = runBlocking {
        val id = repository.createPlaylist("  Evening  ", listOf(a, b, a))
        repository.addToPlaylist(id, listOf(b, c))
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), names())
        repository.moveInPlaylist(id, SavedSong.from(c).id, -1)
        assertEquals(listOf("Song 1", "Song 3", "Song 2"), names())
        repository.removeFromPlaylist(id, SavedSong.from(a).id)
        repository.addToPlaylist(id, listOf(a))
        assertEquals(listOf("Song 3", "Song 2", "Song 1"), names())
        repository.renamePlaylist(id, " Night ")
        assertEquals("Night", repository.playlists.first().single().name)
        assertEquals(listOf(0, 1, 2), database.userData().entries(id).map { it.position })
    }

    @Test fun metadataRefreshAndPlaylistDeletionCannotEraseFavouritesHistoryOrOffsets() = runBlocking {
        repository.toggleFavourite(a)
        repository.setLyricOffset(a, -250)
        val id = repository.createPlaylist("Evening", listOf(a))
        repository.recordPlay(a.copy(album = "Updated album"))
        repository.deletePlaylist(id)
        assertTrue(repository.playlists.first().isEmpty())
        assertEquals("Updated album", repository.favourites.first().single().album)
        assertEquals(1, repository.history.first().size)
        assertEquals(-250L, repository.lyricOffset(SavedSong.from(a)).first())
    }

    @Test fun allPersonalDataSurvivesDatabaseReopen() = runBlocking {
        repository.createPlaylist("Travel", listOf(b, a))
        repository.toggleFavourite(a)
        repository.recordPlay(a)
        repository.recordPlay(a)
        repository.setLyricOffset(a, 750)
        database.close()
        reopen()
        assertEquals(listOf("Song 2", "Song 1"), names())
        assertEquals(SavedSong.from(a).id, repository.favourites.first().single().id)
        assertEquals(2, repository.history.first().size)
        assertEquals(750L, repository.lyricOffset(SavedSong.from(a)).first())
    }

    @Test fun movedFavouriteCanBeRemovedUsingItsOriginalReference() = runBlocking {
        repository.toggleFavourite(a)
        repository.toggleFavourite(a.copy(path = "Moved/song.flac"), SavedSong.from(a).id)
        assertTrue(repository.favourites.first().isEmpty())
    }

    @Test fun invalidNameRollsBackWithoutAnEmptyPlaylist() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.createPlaylist("   ", listOf(a)) } }
        runBlocking { assertTrue(repository.playlists.first().isEmpty()) }
    }

    @Test fun importedMissingReferencesAndOrderSurviveReopen() = runBlocking {
        val missing = ua.olex.music.model.M3uPlaylist.resolve(
            ua.olex.music.model.M3uPlaylist.parse("#EXTINF:120,Artist - Missing\nMusic/missing.flac"),
            ua.olex.music.model.LibraryIndex.EMPTY).songs.single()
        val saved = SavedSong.from(a)
        repository.importPlaylist(" Imported ", listOf(missing, saved, missing))
        database.close(); reopen()
        val playlist = repository.playlists.first().single()
        assertEquals("Imported", playlist.name)
        assertEquals(listOf(missing, saved, missing), playlist.songs) // the file's duplicates are kept
    }

    @Test fun largeImportsAreBatchedWithoutLosingPositionsOrFavourites() = runBlocking {
        repository.toggleFavourite(a)
        val songs = (1..1201).map { SavedSong.from(track(it)) }
        val id = repository.importPlaylist("Large", songs)
        assertEquals(songs.map { it.id }, repository.playlists.first().single().songs.map { it.id })
        assertEquals((0..1200).toList(), database.userData().entries(id).map { it.position })
        assertEquals(listOf(SavedSong.from(a)), repository.favourites.first())
    }

    @Test fun invalidImportsDoNotLeaveEmptyPlaylistHeaders() {
        for (songs in listOf(emptyList(), listOf(SavedSong.from(a).copy(path = "")))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.importPlaylist("Invalid", songs) } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.importPlaylist(" ", listOf(SavedSong.from(a))) } }
        runBlocking { assertTrue(repository.playlists.first().isEmpty()) }
    }

    @Test fun unsupportedSchemaNeverDestructivelyRebuildsUserData() = runBlocking {
        repository.createPlaylist("Keep this", listOf(a))
        database.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("user-test.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 } // a version from the future
        reopen()
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.playlists.first() } }
        database.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("user-test.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM playlists", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Keep this", cursor.getString(0))
            }
        }
    }

    private suspend fun names() = repository.playlists.first().single().songs.map { it.title }
    private fun track(id: Int) = Track(TrackId("local:$id"), "content://$id", "Song $id", "Artist", path = "Music/$id.flac", durationMs = 120_000)

    @Test fun ratingsAndPlayCountsFollowTheSongAcrossCopies() = runBlocking {
        repository.recordPlay(a); repository.recordPlay(a); repository.recordPlay(b)
        repository.setRating(a, 4)
        var stats = repository.stats.first()
        assertEquals(2, stats.getValue(a.key.value).plays)
        assertEquals(4, stats.getValue(a.key.value).rating)
        assertEquals(1, stats.getValue(b.key.value).plays)
        repository.setRating(a, 2)
        assertEquals("a new rating replaces the old one", 2, repository.stats.first().getValue(a.key.value).rating)
        repository.setRating(a, 0)
        stats = repository.stats.first()
        assertEquals("0 clears it; plays stay", 0, stats.getValue(a.key.value).rating)
        assertEquals(2, stats.getValue(a.key.value).plays)
    }
}
