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
    private val changedVolumes: (suspend (Long, Map<String, Long>) -> List<TrackEntity>)? = null,
) {
    constructor(context: Context, database: LibraryDatabase, files: LocalMusicFiles? = null) : this(
        database.tracks(), { scanId -> MediaStoreScanner.scan(context.applicationContext, scanId) },
        changes = context.applicationContext.contentResolver.musicChanges(),
        enrich = { rows -> FileTagScanner.enrich(rows, database.tracks().observeAll().first(), cue = { path -> files?.sidecarCueBlocking(path) }, read = AndroidTagSource(context.applicationContext)::read) },
        excluded = LibraryFolders.excluded(context.applicationContext),
        artworkPackage = context.applicationContext.packageName, artworkRevision = files?.revision ?: flowOf(0L),
        changedVolumes = { scanId, volumes -> MediaStoreScanner.scan(context.applicationContext, scanId, volumes) },
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

    private data class VolumeStamp(val version: String, val generation: Long)

    private fun volumeStamps(value: String): Map<String, VolumeStamp>? {
        val stamps = LinkedHashMap<String, VolumeStamp>()
        for (entry in value.split('|')) {
            val volume = entry.substringBefore(':')
            val last = entry.lastIndexOf(':')
            if (last <= volume.length + 1) return null
            val generation = entry.substring(last + 1).toLongOrNull() ?: return null
            if (volume.isBlank() || generation < 0 || stamps.put(volume, VolumeStamp(
                    entry.substring(volume.length + 1, last), generation,
                )) != null) return null
        }
        return stamps
    }

    /** Mount changes, provider resets and inconsistent ID reads trigger full reconciliation. */
    private suspend fun incremental(current: String?, scanId: Long): List<TrackEntity>? {
        val old = lastVersion?.let(::volumeStamps) ?: return null
        val new = current?.let(::volumeStamps) ?: return null
        if (old.keys != new.keys) return null
        val changed = LinkedHashMap<String, Long>()
        for ((volume, before) in old) {
            val after = new.getValue(volume)
            if (before.version != after.version || before.generation > after.generation) return null
            if (before.generation < after.generation) changed[volume] = before.generation
        }
        if (changed.isEmpty()) return null
        val ids = currentIds ?: return null
        return try {
            val volumeScanner = changedVolumes
            val legacyScanner = changedSince
            val delta = when {
                volumeScanner != null -> volumeScanner(scanId, changed)
                legacyScanner != null && old.size == 1 -> legacyScanner(scanId, changed.values.single())
                else -> return null
            }
            val updated = enrich(delta).associateBy { it.id }
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
     * The cold playback path can read relevant cached rows while the full index builds.
     * This query discovers candidates; the same service policy confirms identity and availability.
     */
    suspend fun cachedPlaybackCandidates(request: Track): LibraryIndex? {
        val credits = request.artists.takeIf { it.joinToString(", ") == request.artist }.orEmpty()
        val keys = (listOf(request.key.value) + credits.map { SongKey.of(it, request.title).value }).toSet()
        if (keys.size > 8) return null
        val revision = artworkRevision.first()
        val tracks = dao.byKeys(keys).map { it.toTrack(artworkPackage, revision) }
        return tracks.takeIf { it.isNotEmpty() }?.let { LibraryIndex.build(it) }
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
