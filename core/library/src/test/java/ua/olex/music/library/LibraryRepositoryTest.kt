package ua.olex.music.library

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {
    @Test fun failedScanDoesNotReplaceTheSavedLibrary() {
        val dao = MemoryTracks()
        assertThrows(IOException::class.java) {
            runTest {
                LibraryRepository(dao, { throw IOException("Provider unavailable") }, StandardTestDispatcher(testScheduler)).rescan()
            }
        }
        assertEquals(listOf("local:old"), dao.rows.value.map { it.id })
        assertEquals(0, dao.replacements)
    }

    @Test fun revokedPermissionDoesNotReplaceTheSavedLibrary() {
        val dao = MemoryTracks()
        assertThrows(SecurityException::class.java) {
            runTest {
                LibraryRepository(dao, { throw SecurityException("Permission revoked") }, StandardTestDispatcher(testScheduler)).rescan()
            }
        }
        assertEquals(0, dao.replacements)
    }

    @Test fun successfulEmptyScanRemovesDeletedFiles() = runTest {
        val dao = MemoryTracks()
        val repository = LibraryRepository(dao, { emptyList() }, StandardTestDispatcher(testScheduler))
        assertEquals(0, repository.rescan())
        assertEquals(emptyList<TrackEntity>(), dao.rows.value)
        assertEquals(1, dao.replacements)
    }

    @Test fun cancelledScanDoesNotPublishPartialResults() = runTest {
        val dao = MemoryTracks()
        val repository = LibraryRepository(dao, { awaitCancellation() }, StandardTestDispatcher(testScheduler))
        val job = launch { repository.rescan() }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(0, dao.replacements)
    }

    @Test fun scansAreSerializedAndNeverReuseAnId() = runTest {
        val dao = MemoryTracks()
        val unblock = CompletableDeferred<Unit>()
        val ids = mutableListOf<Long>()
        val repository = LibraryRepository(dao, { id ->
            ids += id
            if (ids.size == 1) unblock.await()
            listOf(entity("local:$id", id))
        }, StandardTestDispatcher(testScheduler))
        val first = launch { repository.rescan() }
        runCurrent()
        val second = launch { repository.rescan() }
        runCurrent()
        assertEquals(1, ids.size)
        assertFalse(second.isCompleted)
        unblock.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertEquals(listOf("local:${ids.last()}"), dao.rows.value.map { it.id })
    }

    private class MemoryTracks : TrackDao {
        val rows = MutableStateFlow(listOf(entity("local:old", 1)))
        var replacements = 0
        override fun observeAll() = rows
        override fun search(expression: String) = error("Search uses SQLite integration tests")
        override suspend fun byKey(key: String) = rows.value.filter { it.songKey == key }
        override suspend fun count() = rows.value.size
        override suspend fun upsert(tracks: List<TrackEntity>) {
            val incoming = tracks.associateBy { it.id }
            rows.value = rows.value.filterNot { it.id in incoming } + tracks
        }
        override suspend fun deleteNotSeen(prefix: String, scanId: Long): Int {
            val old = rows.value
            rows.value = old.filterNot { it.id.startsWith("$prefix:") && it.scanId != scanId }
            return old.size - rows.value.size
        }
        override suspend fun replace(prefix: String, scanId: Long, tracks: List<TrackEntity>) {
            replacements++
            super.replace(prefix, scanId, tracks)
        }
    }
}

private fun entity(id: String, scanId: Long) = TrackEntity(
    id = id, uri = "content://media/external/audio/media/1", title = "Song", artist = "Artist",
    album = null, albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null,
    year = null, artworkUri = null, codec = "flac", sampleRateHz = null, bitDepth = null,
    bitrateKbps = null, path = null, songKey = "artist|song", modifiedAt = 0, sizeBytes = 1, scanId = scanId,
)
