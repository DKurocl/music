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
class PlaybackSources(private val local: LibraryIndex, saved: List<SavedSong>, aliases: Map<String, String> = emptyMap()) {
    private val recordings = RecordingLookup(saved, aliases)
    private val localBindings by lazy { SavedCatalog.LocalBindings(local, saved) }
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
        // Each credited name counts on its own: YouTube writes "A, B" or "A & B" where the file says "A".
        val credited = (request.artists + request.artist).flatMap { it.split(CREDIT_SPLIT) }.map { it.trim() }.filter { it.isNotEmpty() }
        val wantedCredits = (request.artists + request.artist + credited).map { SongKey.of(it, request.title).value.substringBefore('|') }.toSet() +
            listOfNotNull(primary?.songKey?.substringBefore('|'))
        val proposed = proposedLocal?.let { local ->
            val source = RecordingSource.from(local) ?: return@let null
            // A discovery result is only a candidate. A file's whole band name is one credit.
            val creditMatches = (local.artists + local.artist).any {
                SongKey.of(it, request.title).value.substringBefore('|') in wantedCredits
            }
            source.takeIf {
                owned.any { it.key == source.key } ||
                    (primary != null && primary.durationMs > 0 && source.durationMs > 0 && creditMatches)
            }
        }
        val incoming = (listOfNotNull(primary, proposed) + snapshot.references).distinctBy { it.key }
        val retained = incoming.filter { source ->
            if (source.key == primary?.key || owned.any { it.key == source.key }) return@filter true
            if (primary?.kind == RecordingSourceKind.YOUTUBE && source.kind != RecordingSourceKind.YOUTUBE &&
                (primary.durationMs <= 0 || source.durationMs <= 0 ||
                    (source.key != proposed?.key && source.songKey.substringBefore('|') !in wantedCredits))) return@filter false
            RecordingMatcher.canCombine(owned + listOfNotNull(primary) + source,
                (listOfNotNull(primary) + source).flatMap(recordings::possible).distinctBy { it.id })
        }
        val mayAttach = known != null && RecordingMatcher.group(listOfNotNull(primary),
            listOf(RecordingCandidate(known.id, known.createdAt, owned, known.songKey, known.durationMs))).isNotEmpty()
        val reference = if (mayAttach) known!!.copy(sources = (retained + owned).distinctBy { it.key })
            else known ?: snapshot.copy(sources = retained)
        // An explicitly empty set must not fall back to a legacy path and resurrect a failed source.
        val remaining = reference.references.filter { it.key !in rejected }
        val filtered = remaining.takeIf { it.isNotEmpty() }?.let {
            // Honour this request's source preference, not an older favourite's preferred path.
            // Online intent picks the best owned file and keeps the exact requested video reserve.
            reference.copy(path = snapshot.path, sources = it)
        }
        val mastering = request.title.mastering()
        var incompatible = false
        val preferredLocalKey = primary?.takeIf { it.kind == RecordingSourceKind.LOCAL }?.key
        val selected = filtered?.resolve(local, preferredLocalKey) { candidate ->
            val actual = RecordingSource.from(candidate) ?: return@resolve false
            if (actual.key in rejected) return@resolve false
            val exactLinkedVideo = primary != null && primary.kind == RecordingSourceKind.YOUTUBE &&
                owned.any { it.key == primary.key } && owned.any { it.key == actual.key }
            // A release or mastering label can be absent from an online answer. Preserve the
            // requested edition unless the pair is already linked or its actual tags agree.
            if (primary?.kind == RecordingSourceKind.YOUTUBE && !exactLinkedVideo && candidate.title.mastering() != mastering) {
                incompatible = true
                return@resolve false
            }
            val direct = actual.key == primary?.key && (known?.references ?: reference.references).all {
                it.kind == RecordingSourceKind.LOCAL && it.songKey == actual.songKey && it.durationMs == actual.durationMs
            }
            val permitted = direct || RecordingMatcher.canCombine(reference.references + actual, recordings.possible(actual))
            if (!permitted) incompatible = true
            permitted && available(candidate.uri)
        }
        if (selected != null && !selected.uri.startsWith(SavedSong.ONLINE_PREFIX)) {
            // A whole-library copy here rebuilt the same SongKey index on every source request.
            val file = localBindings.bind(local.byId[selected.id] ?: selected)
            val bound = requireNotNull(filtered).bind(file)
            val actual = RecordingSource.from(bound)!!
            return PlaybackSourceChoice(bound.copy(sources = (listOf(actual) + reference.references).distinctBy { it.key }))
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
            incompatible -> SourceIssue.AMBIGUOUS
            else -> SourceIssue.MISSING
        }
        return PlaybackSourceChoice(reference.copy(path = snapshot.path).displayTrack(), issue)
    }
}

/** Separators between credited artists in one artist field ("A, B", "A & B", "A feat. B"). */
private val CREDIT_SPLIT = Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bwith\b|\bx\b|;)\s*""", RegexOption.IGNORE_CASE)

private val MASTERING_WORDS = Regex("""\b(remaster(?:ed)?|mastering|deluxe edition|anniversary edition)\b""")
private val MASTERING_YEAR = Regex("""\b(?:19|20)[0-9]{2}\b""")

private fun String.mastering(): Pair<String, Set<String>>? {
    val folded = TextFold.fold(this)
    val marker = MASTERING_WORDS.find(folded)?.value ?: return null
    val kind = if (marker.startsWith("remaster")) "remaster" else marker
    return kind to MASTERING_YEAR.findAll(folded).map { it.value }.toSet()
}
