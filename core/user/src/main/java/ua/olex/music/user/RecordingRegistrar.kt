package ua.olex.music.user

import ua.olex.music.model.RecordingCandidate
import ua.olex.music.model.RecordingMatcher
import ua.olex.music.model.RecordingSource
import ua.olex.music.model.RecordingSourceKind
import ua.olex.music.model.SavedSong

/** Runs inside the caller's Room transaction; all merge decisions precede relation rewrites. */
internal class RecordingRegistrar private constructor(
    private val dao: UserDao,
    private val at: Long,
    private val newId: () -> String,
    initial: List<RecordingRelations>,
) {
    private val records = initial.associateByTo(LinkedHashMap()) { it.id }
    private val bySource = HashMap<String, MutableSet<String>>()
    private val byKey = HashMap<String, MutableSet<String>>()
    init { initial.forEach(::index) }

    private fun index(row: RecordingRelations) {
        byKey.getOrPut(row.song.songKey) { LinkedHashSet() }.add(row.id)
        row.toSong().references.forEach { source ->
            byKey.getOrPut(source.songKey) { LinkedHashSet() }.add(row.id)
            bySource.getOrPut(source.key) { LinkedHashSet() }.add(row.id)
        }
    }

    suspend fun register(snapshot: SavedSong): SavedSong {
        val incoming = snapshot.references
        require(incoming.isNotEmpty()) { "This song has no source to save" }
        val ids = LinkedHashSet<String>()
        ids.add(snapshot.id)
        incoming.forEach { source -> ids.addAll(bySource[source.key].orEmpty()); ids.addAll(byKey[source.songKey].orEmpty()) }
        val candidates = ids.mapNotNull { records[it] }.map { row ->
            RecordingCandidate(row.id, row.song.createdAt, row.toSong().references, row.song.songKey, row.song.durationMs)
        }
        val group = RecordingMatcher.group(incoming, candidates).map { records.getValue(it.id) }
        val keep = group.firstOrNull()?.id ?: newId()
        require(!keep.startsWith(SavedSong.SNAPSHOT_PREFIX))
        val existing = group.flatMap { row -> row.sources.ifEmpty { row.toSong().references.map { RecordingSourceEntity.from(row.id, it, row.song.createdAt) } } }
        val mergedSources = existing.groupBy { it.sourceKey }.mapValues { (_, copies) ->
            copies.sortedWith(compareByDescending<RecordingSourceEntity> { it.qualityRank }.thenBy { it.addedAt }).first().copy(songId = keep)
        }.toMutableMap()
        for ((position, source) in incoming.withIndex()) {
            val old = mergedSources[source.key]
            // Only the selected source is fresh evidence. A queue snapshot may carry an older
            // reserve than the database; it must not roll that reserve's metadata backwards.
            val fresh = if (position > 0 && old != null) old.toSource() else source
            val refreshed = if (old == null) fresh else fresh.copy(
                uri = fresh.uri ?: old.uri, path = fresh.path ?: old.path,
                durationMs = fresh.durationMs.takeIf { it > 0 } ?: old.durationMs,
                musicBrainzId = fresh.musicBrainzId ?: old.musicBrainzId,
                qualityRank = fresh.qualityRank.takeIf { it > 0 } ?: old.qualityRank,
                artworkUri = fresh.artworkUri ?: old.artworkUri,
            )
            mergedSources[source.key] = RecordingSourceEntity.from(keep, refreshed, old?.addedAt ?: at)
        }
        val primary = incoming.first()
        val sourceRows = mergedSources.values.map { row ->
            if (primary.kind == RecordingSourceKind.LOCAL && row.kind == RecordingSourceKind.MISSING_LOCAL.value)
                RecordingSourceEntity.from(keep, row.toSource().copy(kind = RecordingSourceKind.LOCAL,
                    songKey = primary.songKey, durationMs = primary.durationMs, musicBrainzId = primary.musicBrainzId), row.addedAt)
            else row
        }.sortedWith(compareBy<RecordingSourceEntity> { it.addedAt }.thenBy { it.sourceKey })
        val oldMetadata = group.maxWithOrNull(compareBy<RecordingRelations> { row ->
            if (row.toSong().references.any { it.kind == RecordingSourceKind.LOCAL }) 1 else 0
        }.thenBy { row -> row.sources.maxOfOrNull { it.qualityRank } ?: 0 })?.toSong()
        val previousRank = existing.filter { it.kind == RecordingSourceKind.LOCAL.value }.maxOfOrNull { it.qualityRank } ?: 0
        val hasLocal = existing.any { it.kind == RecordingSourceKind.LOCAL.value }
        val metadata = if (oldMetadata == null || (!hasLocal && primary.kind != RecordingSourceKind.MISSING_LOCAL) ||
            (primary.kind == RecordingSourceKind.LOCAL && primary.qualityRank >= previousRank)) snapshot else oldMetadata
        val recording = metadata.copy(id = keep, createdAt = group.firstOrNull()?.song?.createdAt ?: at, sources = sourceRows.map { it.toSource() })
        val entity = UserSongEntity.from(recording)
        if (records[keep]?.song != entity) dao.saveSongs(listOf(entity))
        if (group.size > 1) mergeRelations(keep, group.map { it.id })
        if (records[keep]?.sources?.sortedBy { it.sourceKey } != sourceRows.sortedBy { it.sourceKey }) dao.saveSources(sourceRows)
        if (keep !in records) dao.saveAliases(listOf(RecordingAliasEntity(keep, keep)))
        val others = group.map { it.id }.filter { it != keep }
        if (others.isNotEmpty()) {
            others.chunked(400).forEach { chunk ->
                dao.redirectAliases(keep, chunk)
                dao.saveAliases(chunk.map { RecordingAliasEntity(it, keep) })
                dao.deleteRecordings(chunk)
            }
            others.forEach(records::remove)
        }
        val row = RecordingRelations(entity, sourceRows)
        records[keep] = row
        index(row)
        return recording
    }

    private suspend fun mergeRelations(keep: String, ids: List<String>) {
        val chunks = ids.chunked(400)
        chunks.mapNotNull { dao.latestFavourite(it) }.maxOrNull()?.let { dao.addFavourite(FavouriteEntity(keep, it)) }
        chunks.mapNotNull { dao.latestRating(it, keep) }
            .sortedWith(compareByDescending<RatingEntity> { it.ratedAt }.thenByDescending { it.songId == keep }.thenBy { it.songId })
            .firstOrNull()?.let { dao.setRating(it.copy(songId = keep)) }
        chunks.mapNotNull { dao.latestOffset(it, keep) }
            .sortedWith(compareByDescending<LyricOffsetEntity> { it.updatedAt }.thenByDescending { it.songId == keep }.thenBy { it.songId })
            .firstOrNull()?.let { dao.setOffset(it.copy(songId = keep)) }
        val others = ids.filter { it != keep }
        others.chunked(400).forEach { dao.redirectPlaylistEntries(keep, it); dao.redirectHistory(keep, it) }
    }

    companion object {
        suspend fun create(dao: UserDao, snapshots: List<SavedSong>, at: Long, newId: () -> String): RecordingRegistrar {
            val rows = if (snapshots.size > 1 || snapshots.sumOf { it.references.size } > ua.olex.music.model.RecordingSourcesCodec.MAX_SOURCES) dao.allRecordings() else {
                val refs = snapshots.flatMap { it.references }
                dao.recordingCandidates(snapshots.map { it.id }, (refs.map { it.songKey } + snapshots.map { it.songKey }).distinct(), refs.map { it.key }.distinct())
            }
            return RecordingRegistrar(dao, at, newId, rows)
        }
    }
}
