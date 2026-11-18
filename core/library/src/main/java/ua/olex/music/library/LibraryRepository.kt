package ua.olex.music.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
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
    private val artworkPackage: String? = null,
    private val artworkRevision: Flow<Long> = flowOf(0L),
    /** Folders the owner hid from the library (relative path prefixes such as "Recordings/"). */
    private val excluded: Flow<Set<String>> = flowOf(emptySet()),
    private val version: suspend () -> String? = { null },
    private val changedSince: (suspend (Long, Long) -> List<TrackEntity>)? = null,
    private val currentIds: (suspend () -> Set<String>)? = null,
) {
    constructor(context: Context, database: LibraryDatabase, files: LocalMusicFiles? = null) : this(
        database.tracks(), { scanId -> MediaStoreScanner.scan(context.applicationContext, scanId) },
        changes = context.applicationContext.contentResolver.musicChanges(),
        enrich = { rows -> FileTagScanner.enrich(rows, database.tracks().observeAll().first(), cue = { path -> files?.sidecarCueBlocking(path) }, read = AndroidTagSource(context.applicationContext)::read) },
        excluded = LibraryFolders.excluded(context.applicationContext),
        artworkPackage = context.applicationContext.packageName, artworkRevision = files?.revision ?: flowOf(0L),
        changedSince = { scanId, previous -> MediaStoreScanner.scan(context.applicationContext, scanId, previous) },
        currentIds = { MediaStoreScanner.musicIds(context.applicationContext) },
        version = metadata@{
            val revisions = ArrayList<String>()
            for (volume in android.provider.MediaStore.getExternalVolumeNames(context.applicationContext).sorted()) {
                val revision = android.provider.MediaStore.getVersion(context.applicationContext, volume) ?: return@metadata null
                revisions += "$volume:$revision:${android.provider.MediaStore.getGeneration(context.applicationContext, volume)}"
            }
            revisions.joinToString("|")
        },
    )

    private val scanning = Mutex()
    private var lastScanId = 0L
    private var lastVersion: String? = null
    private var lastCount = 0

    val tracks: Flow<List<Track>> = combine(dao.observeAll(), artworkRevision) { rows, revision -> rows.map { it.toTrack(artworkPackage, revision) } }.flowOn(Dispatchers.Default)
    val index: Flow<LibraryIndex> = combine(tracks, excluded) { all, hidden ->
        LibraryIndex.build(if (hidden.isEmpty()) all else all.filter { t -> val p = t.path; p == null || hidden.none { p.startsWith(it) } })
    }.flowOn(Dispatchers.Default)

    /** Prefix matches across title, artist, album and album artist; accents are handled by SQLite. */
    fun search(query: String): Flow<List<Track>> {
        if (query.isBlank()) return index.map { it.songs }
        val expression = LibrarySearch.expression(query) ?: return flowOf(emptyList())
        return combine(dao.search(expression), artworkRevision) { rows, revision ->
            rows.map { it.toTrack(artworkPackage, revision) to LibraryOrder.name(it.title) }
                .sortedWith(compareBy<Pair<Track, String>> { it.second }.thenBy { it.first.id.value })
                .map { it.first }
        }.flowOn(Dispatchers.Default)
    }

    /** Reads the phone's music again; returns how many tracks there are. One scan at a time. */
    suspend fun rescan(): Int = refresh(force = true)

    /** Reuse the current snapshot until a MediaStore volume version/generation changes. */
    suspend fun refresh(): Int = refresh(force = false)

    private suspend fun refresh(force: Boolean): Int = scanning.withLock {
        withContext(ioDispatcher) {
            val generation = try { version() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (!force && generation != null && generation == lastVersion) return@withContext lastCount
            // Two fast scans (or a wall-clock adjustment) must not retain deleted rows.
            val scanId = maxOf(System.currentTimeMillis(), lastScanId + 1).also { lastScanId = it }
            val found = if (force) enrich(scan(scanId)) else incremental(generation, scanId) ?: enrich(scan(scanId))
            currentCoroutineContext().ensureActive()
            dao.replace(Origin.LOCAL.prefix, scanId, found)
            lastVersion = generation
            lastCount = found.size
            found.size
        }
    }

    /** A version change or multiple volumes need a full scan; generations belong to one volume. */
    private suspend fun incremental(current: String?, scanId: Long): List<TrackEntity>? {
        val previous = lastVersion ?: return null
        if (current == null || '|' in previous || '|' in current) return null
        if (previous.substringBeforeLast(':') != current.substringBeforeLast(':')) return null
        val since = previous.substringAfterLast(':').toLongOrNull() ?: return null
        if ((current.substringAfterLast(':').toLongOrNull() ?: return null) <= since) return null
        val scanDelta = changedSince ?: return null
        val ids = currentIds ?: return null
        return try {
            val updated = enrich(scanDelta(scanId, since)).associateBy { it.id }
            val existing = dao.snapshot(Origin.LOCAL.prefix).associateBy { it.id }
            val live = ids()
            if (live.any { it !in updated && it !in existing }) return null
            live.map { id ->
                currentCoroutineContext().ensureActive()
                updated[id] ?: existing.getValue(id).copy(scanId = scanId)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The owned copy of a song from anywhere, if there is one: the best-sounding local file with
     * the same [SongKey] and a matching length. A mix plays this instead of a stream.
     */
    suspend fun owned(song: Track): Track? {
        val revision = artworkRevision.first()
        return dao.byKey(song.key.value).map { it.toTrack(artworkPackage, revision) }
            .filter { SongKey.sameSong(it, song) }
            .maxByOrNull { it.format?.rank ?: 0 }
    }
}
