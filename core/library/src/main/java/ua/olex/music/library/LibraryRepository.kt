package ua.olex.music.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.olex.music.model.Origin
import ua.olex.music.model.SongKey
import ua.olex.music.model.Track
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.LibraryOrder
import ua.olex.music.model.LibrarySearch

/** The local library: what is on the phone, and the way to find a song in it. */
class LibraryRepository internal constructor(
    private val dao: TrackDao,
    private val scan: suspend (Long) -> List<TrackEntity>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val changes: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    private val enrich: suspend (List<TrackEntity>) -> List<TrackEntity> = { it },
) {
    constructor(context: Context, database: LibraryDatabase) : this(
        database.tracks(), { scanId -> MediaStoreScanner.scan(context.applicationContext, scanId) },
        changes = context.applicationContext.contentResolver.musicChanges(),
        enrich = { rows -> FileTagScanner.enrich(rows, database.tracks().observeAll().first(), AndroidTagSource(context.applicationContext)::read) },
    )

    private val scanning = Mutex()
    private var lastScanId = 0L

    val tracks: Flow<List<Track>> = dao.observeAll().map { rows -> rows.map(TrackEntity::toTrack) }.flowOn(Dispatchers.Default)
    val index: Flow<LibraryIndex> = tracks.map(LibraryIndex::build).flowOn(Dispatchers.Default)

    /** Prefix matches across title, artist, album and album artist; accents are handled by SQLite. */
    fun search(query: String): Flow<List<Track>> {
        if (query.isBlank()) return index.map { it.songs }
        val expression = LibrarySearch.expression(query) ?: return flowOf(emptyList())
        return dao.search(expression).map { rows ->
            rows.map { it.toTrack() to LibraryOrder.name(it.title) }
                .sortedWith(compareBy<Pair<Track, String>> { it.second }.thenBy { it.first.id.value })
                .map { it.first }
        }.flowOn(Dispatchers.Default)
    }

    /** Reads the phone's music again; returns how many tracks there are. One scan at a time. */
    suspend fun rescan(): Int = scanning.withLock {
        withContext(ioDispatcher) {
            // Two fast scans (or a wall-clock adjustment) must not retain deleted rows.
            val scanId = maxOf(System.currentTimeMillis(), lastScanId + 1).also { lastScanId = it }
            val found = enrich(scan(scanId))
            currentCoroutineContext().ensureActive()
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
