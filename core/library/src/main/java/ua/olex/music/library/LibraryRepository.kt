package ua.olex.music.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.olex.music.model.Origin
import ua.olex.music.model.SongKey
import ua.olex.music.model.Track

/** The local library: what is on the phone, and the way to find a song in it. */
class LibraryRepository(private val context: Context, private val database: LibraryDatabase) {
    private val dao get() = database.tracks()
    private val scanning = Mutex()

    val tracks: Flow<List<Track>> = dao.observeAll().map { rows -> rows.map(TrackEntity::toTrack) }.flowOn(Dispatchers.Default)

    /** Reads the phone's music again; returns how many tracks there are. One scan at a time. */
    suspend fun rescan(): Int = scanning.withLock {
        withContext(Dispatchers.IO) {
            val scanId = System.currentTimeMillis()
            val found = MediaStoreScanner.scan(context, scanId)
            dao.replace(Origin.LOCAL.prefix, scanId, found)
            found.size
        }
    }

    /**
     * The owned copy of a song from anywhere, if there is one: the best-sounding local file with
     * the same [SongKey] and a matching length. A mix plays this instead of a stream.
     */
    suspend fun owned(song: Track): Track? =
        dao.byKey(song.key.value).map(TrackEntity::toTrack)
            .filter { SongKey.sameSong(it, song) }
            .maxByOrNull { it.format?.rank ?: 0 }
}
