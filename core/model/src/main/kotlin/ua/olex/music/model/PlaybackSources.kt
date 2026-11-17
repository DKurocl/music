package ua.olex.music.model

/** Immutable per-request lookup; build once off main, never query Room for each queue occurrence. */
class RecordingLookup(songs: List<SavedSong>, private val aliases: Map<String, String> = emptyMap()) {
    private val byId = songs.associateBy { it.id }
    private val byKey = HashMap<String, MutableSet<String>>()
    private val bySource = HashMap<String, MutableSet<String>>()
    init {
        for (song in songs) {
            byKey.getOrPut(song.songKey) { LinkedHashSet() }.add(song.id)
            for (source in song.references) {
                byKey.getOrPut(source.songKey) { LinkedHashSet() }.add(song.id)
                bySource.getOrPut(source.key) { LinkedHashSet() }.add(song.id)
            }
        }
    }

    private fun candidates(sources: List<RecordingSource>): List<RecordingCandidate> {
        val ids = LinkedHashSet<String>()
        for (source in sources) {
            ids.addAll(byKey[source.songKey].orEmpty())
            ids.addAll(bySource[source.key].orEmpty())
        }
        return ids.mapNotNull { byId[it] }.map { RecordingCandidate(it.id, it.createdAt, it.references, it.songKey, it.durationMs) }
    }

    /** Competing recordings which could own this physical source, including metadata-only rows. */
    fun possible(source: RecordingSource) = candidates(listOf(source)).filter {
        RecordingMatcher.group(listOf(source), listOf(it)).isNotEmpty()
    }

    /** A known recording ID wins; otherwise only a confirmed, unambiguous source match binds it. */
    fun find(track: Track): SavedSong? {
        track.recordingId?.let { id -> byId[aliases[id] ?: id]?.let { return it } }
        val incoming = SavedSong.from(track).references
        val group = RecordingMatcher.group(incoming, candidates(incoming))
        val winner = group.firstOrNull() ?: return null
        return byId.getValue(winner.id).copy(sources = group.flatMap { it.sources }.distinctBy { it.key })
    }

}

/** A retained occurrence can be unavailable without losing its intended identity or queue position. */
enum class SourceIssue { MISSING, OFFLINE, AMBIGUOUS }

/** The service stages unavailable occurrences with explicit metadata instead of opening a wrong take. */
data class PlaybackSourceChoice(val track: Track, val issue: SourceIssue? = null)

/** One queue-source policy, independent of the initiating UI, browser, widget or recovery path. */
class PlaybackSources(local: LibraryIndex, saved: List<SavedSong>, aliases: Map<String, String> = emptyMap()) {
    private val recordings = RecordingLookup(saved, aliases)
    private val bound = local.copy(songs = SavedCatalog.bindLocal(local, saved))
    private data class Projection(val rejected: Set<String>, val available: (String) -> Boolean, val index: LibraryIndex)
    @Volatile private var projection: Projection? = null

    private fun localSources(rejected: Set<String>, available: (String) -> Boolean): LibraryIndex {
        projection?.takeIf { it.rejected == rejected && it.available === available }?.let { return it.index }
        return bound.copy(songs = bound.songs.filter { track ->
            val source = RecordingSource.from(track)
            source != null && source.key !in rejected && available(track.uri)
        }).also { projection = Projection(rejected.toSet(), available, it) }
    }

    /** Restore the original video intent after a previous physical-source substitution. */
    fun requested(track: Track): Track {
        val id = track.requestedId
        if (id?.startsWith("yt:") == true && id != track.id.value) {
            val uri = SavedSong.ONLINE_PREFIX + id.removePrefix("yt:")
            if (track.sources.any { it.kind == RecordingSourceKind.YOUTUBE && it.uri == uri }) {
                val original = SavedSong.from(track).copy(path = uri).online()?.copy(recordingId = null, requestedId = id)
                if (original != null) return requested(original)
            }
        }
        if (track.durationMs > 0) return track
        val source = SavedSong.from(track).references.firstOrNull() ?: return track
        val known = recordings.find(track.copy(sources = listOf(source))) ?: return track
        val length = known.references.firstOrNull { it.key == source.key }?.durationMs?.takeIf { it > 0 } ?: return track
        return track.copy(durationMs = length)
    }

    fun select(request: Track, rejected: Set<String> = emptySet(), allowDirect: Boolean = false,
        available: (String) -> Boolean = { true }, proposedLocal: Track? = null): PlaybackSourceChoice {
        val intent = requested(request)
        if (intent !== request) return select(intent, rejected, allowDirect, available, proposedLocal)
        val snapshot = SavedSong.from(request)
        val primary = snapshot.references.firstOrNull()
        val known = recordings.find(request.copy(sources = listOfNotNull(primary)))
        val owned = known?.references.orEmpty()
        val wantedCredits = (request.artists + request.artist).map { SongKey.of(it, request.title).value.substringBefore('|') }.toSet() +
            listOfNotNull(primary?.songKey?.substringBefore('|'))
        val proposed = proposedLocal?.takeIf { local ->
            val source = RecordingSource.from(local)
            owned.any { it.key == source?.key } || (local.artists + local.artist).any { SongKey.of(it, request.title).value.substringBefore('|') in wantedCredits }
        }?.let(RecordingSource::from)
        val incoming = (listOfNotNull(primary, proposed) + snapshot.references).distinctBy { it.key }
        val retained = incoming.filter { source ->
            source.key == primary?.key || owned.any { it.key == source.key } ||
                RecordingMatcher.canCombine(owned + listOfNotNull(primary) + source,
                    (listOfNotNull(primary) + source).flatMap(recordings::possible).distinctBy { it.id })
        }
        val mayAttach = known != null && RecordingMatcher.group(listOfNotNull(primary),
            listOf(RecordingCandidate(known.id, known.createdAt, owned, known.songKey, known.durationMs))).isNotEmpty()
        val reference = if (mayAttach) known!!.copy(sources = (retained + owned).distinctBy { it.key })
            else known ?: snapshot.copy(sources = retained)
        val usable = localSources(rejected, available)
        // An explicitly empty set must not fall back to a legacy path and resurrect a failed source.
        val remaining = reference.references.filter { it.key !in rejected }
        val filtered = remaining.takeIf { it.isNotEmpty() }?.let {
            // Honour this request's source preference, not an older favourite's preferred path.
            // Online intent picks the best owned file and keeps the exact requested video reserve.
            reference.copy(path = snapshot.path, sources = it)
        }
        val selected = filtered?.resolve(usable)
        if (selected != null && !selected.uri.startsWith(SavedSong.ONLINE_PREFIX)) {
            val actual = RecordingSource.from(selected)!!
            val original = RecordingSource.from(request)
            val direct = actual.key == original?.key && (known?.references ?: reference.references).all {
                it.kind == RecordingSourceKind.LOCAL && it.songKey == actual.songKey && it.durationMs == actual.durationMs
            }
            val permitted = direct || RecordingMatcher.canCombine(reference.references + actual, recordings.possible(actual))
            if (permitted) return PlaybackSourceChoice(selected.copy(sources =
                (listOf(actual) + reference.references).distinctBy { it.key }))
        }
        val online = filtered?.online()
        if (online != null && available(online.uri)) return PlaybackSourceChoice(online.copy(
            sources = reference.references.filter { it.kind == RecordingSourceKind.YOUTUBE }))
        val source = RecordingSource.from(request)
        val hasOnline = reference.references.any { it.kind == RecordingSourceKind.YOUTUBE }
        if (known == null && !hasOnline && allowDirect && source != null && source.key !in rejected &&
            request.uri.isNotBlank() && available(request.uri)) return PlaybackSourceChoice(request)
        val issue = when {
            online != null -> SourceIssue.OFFLINE
            selected != null -> SourceIssue.AMBIGUOUS
            else -> SourceIssue.MISSING
        }
        return PlaybackSourceChoice(reference.copy(path = snapshot.path).displayTrack(), issue)
    }
}
