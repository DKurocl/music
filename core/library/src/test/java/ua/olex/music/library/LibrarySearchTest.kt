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

    @Test fun unchangedScanDoesNotWriteEvenWhenTheOrderAndScanIdChange() = runBlocking {
        val songs = (1..1_201).map { track("$it", "Song $it", "Artist", "Album") }
        dao.replace("local", 1, songs)
        database.withTransaction {
            // SQLite counters are connection-local, so both reads must share the writer connection.
            val writes = totalChanges()
            dao.replace("local", 2, songs.reversed().map { it.copy(scanId = 2) })
            assertEquals(writes, totalChanges())
        }
        assertEquals(1_201, dao.count())
        assertEquals(setOf(1L), dao.snapshot("local").map { it.scanId }.toSet())
        assertEquals(listOf("local:1201"), search("1201"))
    }

    @Test fun mixedScanChangesOnlyEditedAddedAndDeletedRows() = runBlocking {
        val unchanged = track("1", "Unchanged", "Artist", "Album")
        val edited = track("2", "Before", "Artist", "Album")
        val deleted = track("3", "Removed", "Artist", "Album")
        val remote = track("other", "Remote", "Artist", "Album").copy(id = "yt:other")
        dao.upsert(listOf(unchanged, edited, deleted, remote))
        dao.replace("local", 2, listOf(unchanged.copy(scanId = 2), edited.copy(title = "After", scanId = 2),
            track("4", "Added", "Artist", "Album").copy(scanId = 2)))
        assertEquals(1L, dao.snapshot("local").single { it.id == unchanged.id }.scanId)
        assertEquals(2L, dao.snapshot("local").single { it.id == edited.id }.scanId)
        assertEquals(emptyList<String>(), search("before"))
        assertEquals(emptyList<String>(), search("removed"))
        assertEquals(listOf("local:2"), search("after"))
        assertEquals(listOf("local:4"), search("added"))
        assertEquals(listOf("yt:other"), search("remote"))
    }

    @Test fun largeDeletionIsBatchedAndLeavesOtherSourcesUntouched() = runBlocking {
        dao.upsert((1..1_201).map { track("$it", "Song", "Artist", "Album") } +
            track("other", "Remote", "Artist", "Album").copy(id = "yt:other"))
        dao.replace("local", 2, emptyList())
        assertEquals(0, dao.snapshot("local").size)
        assertEquals(listOf("yt:other"), search("remote"))
        assertEquals(emptyList<String>(), search("song"))
    }

    @Test fun metadataOnlyChangeIsStoredWithoutChangingSearchFields() = runBlocking {
        val original = track("1", "Song", "Artist", "Album")
        dao.replace("local", 1, listOf(original))
        val updated = original.copy(scanId = 2, metadataJson = "{\"lyrics\":\"Words\"}", bitDepth = 24)
        dao.replace("local", 2, listOf(updated))
        assertEquals(updated, dao.snapshot("local").single())
        assertEquals(listOf("local:1"), search("song"))
    }

    @Test fun malformedSnapshotCannotDeleteSavedTracks() {
        val original = track("1", "Original", "Artist", "Album")
        runBlocking { dao.replace("local", 1, listOf(original)) }
        val wrongSource = original.copy(id = "yt:1", scanId = 2)
        for (invalid in listOf(listOf(wrongSource), listOf(original), listOf(original.copy(scanId = 2), original.copy(scanId = 2)))) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { dao.replace("local", 2, invalid) }
            }
        }
        runBlocking { assertEquals(listOf(original), dao.snapshot("local")) }
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

    private fun totalChanges(): Long = database.openHelper.writableDatabase.query("SELECT total_changes()").use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    private fun track(id: String, title: String, artist: String, album: String) = TrackEntity(
        id = "local:$id", uri = "content://media/external/audio/media/$id", title = title, artist = artist,
        album = album, albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null,
        year = null, artworkUri = null, codec = "flac", sampleRateHz = null, bitDepth = null,
        bitrateKbps = null, path = null, songKey = "$artist|$title", modifiedAt = 0, sizeBytes = 1, scanId = 1,
    )
}
