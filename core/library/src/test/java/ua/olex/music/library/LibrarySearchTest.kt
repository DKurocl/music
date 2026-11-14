package ua.olex.music.library

import androidx.room.Room
import androidx.room.withTransaction
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import ua.olex.music.model.LibrarySearch

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibrarySearchTest {
    private lateinit var database: LibraryDatabase
    private val dao get() = database.tracks()

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java).build()
    }

    @After fun close() { database.close() }

    @Test fun unicodePrefixesMatchTitleArtistAndAlbumAcrossFields() = runBlocking {
        dao.upsert(listOf(
            track("1", "Déjà Vu", "Beyoncé", "B'Day"),
            track("2", "Обійми", "Океан Ельзи", "Земля"),
        ))
        assertEquals(listOf("local:1"), search("bey dej"))
        assertEquals(listOf("local:1"), search("day deja"))
        assertEquals(listOf("local:2"), search("ОБІЙ ОКЕ"))
        assertEquals(emptyList<String>(), search("deja ocean"))
    }

    @Test fun fileMetadataSurvivesRoomAndAllTaggedArtistsAreSearchable() = runBlocking {
        val parsed = FileTagScanner.merge(track("tagged", "System", "System", "System"), ua.olex.music.tags.AudioTags(
            "flac", "flac", 96_000, 24, 2, 120_000,
            fields = mapOf("TITLE" to listOf("Tagged song"), "ARTIST" to listOf("Björk", "Guest"), "GENRE" to listOf("Electronic", "Pop"),
                "REPLAYGAIN_TRACK_GAIN" to listOf("-3 dB"), "LYRICS" to listOf("Words\nNext line")),
        ))
        dao.upsert(listOf(parsed))
        val stored = dao.observeAll().first().single().toTrack()
        assertEquals(listOf("Björk", "Guest"), stored.artists)
        assertEquals(listOf("Electronic", "Pop"), stored.genres)
        assertEquals("FLAC 24/96", stored.format?.label)
        assertEquals(-3f, stored.metadata?.trackGainDb)
        assertEquals("Words\nNext line", stored.metadata?.lyrics)
        assertEquals(listOf("local:tagged"), search("bjork tagged"))
        assertEquals(listOf("local:tagged"), search("guest"))
    }

    @Test fun userOperatorsAreLiteralAndCannotBroadenTheQuery() = runBlocking {
        dao.upsert(listOf(track("1", "One", "Artist", "Album")))
        assertEquals(emptyList<String>(), search("One OR Missing"))
        assertEquals(listOf("local:1"), search("\"One\"*"))
    }

    @Test fun editingAndDeletingTracksUpdatesTheIndex() = runBlocking {
        val first = track("1", "Before", "Singer", "Album")
        dao.replace("local", 1, listOf(first))
        assertEquals(listOf("local:1"), search("before"))
        dao.upsert(listOf(first.copy(title = "After")))
        assertEquals(emptyList<String>(), search("before"))
        assertEquals(listOf("local:1"), search("after"))
        dao.replace("local", 2, emptyList())
        assertEquals(emptyList<String>(), search("after"))
    }

    @Test fun albumArtistIsSearchableWithoutOverwritingTrackArtist() = runBlocking {
        dao.upsert(listOf(track("1", "Track", "Guest", "Compilation").copy(albumArtist = "Various Artists")))
        assertEquals(listOf("local:1"), search("various"))
        assertEquals(listOf("local:1"), search("guest"))
    }

    @Test fun failedTransactionRollsBackBothTheCacheAndSearchIndex() {
        runBlocking { dao.upsert(listOf(track("1", "Original", "Artist", "Album"))) }
        assertThrows(IOException::class.java) {
            runBlocking {
                database.withTransaction {
                    dao.replace("local", 2, listOf(track("2", "Partial", "Artist", "Album").copy(scanId = 2)))
                    throw IOException("Interrupted write")
                }
            }
        }
        runBlocking {
            assertEquals(listOf("local:1"), search("original"))
            assertEquals(emptyList<String>(), search("partial"))
        }
    }

    private suspend fun search(text: String) = dao.search(requireNotNull(LibrarySearch.expression(text))).first().map { it.id }

    private fun track(id: String, title: String, artist: String, album: String) = TrackEntity(
        id = "local:$id", uri = "content://media/external/audio/media/$id", title = title, artist = artist,
        album = album, albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null,
        year = null, artworkUri = null, codec = "flac", sampleRateHz = null, bitDepth = null,
        bitrateKbps = null, path = null, songKey = "$artist|$title", modifiedAt = 0, sizeBytes = 1, scanId = 1,
    )
}
