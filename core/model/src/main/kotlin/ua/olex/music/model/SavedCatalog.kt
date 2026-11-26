package ua.olex.music.model

/** Projects durable recordings into the library without losing their source links. */
object SavedCatalog {
    /** Bind scanned copies through indexed evidence; ambiguous takes keep their own identity. */
    fun bindLocal(local: LibraryIndex, saved: List<SavedSong>): List<Track> =
        if (saved.isEmpty()) local.songs else local.songs.map(LocalBindings(local, saved)::bind)

    /** Use the same matching evidence for the catalogue and single-file request path. */
    internal class LocalBindings(local: LibraryIndex, saved: List<SavedSong>) {
        private val byId = saved.associateBy { it.id }
        private val byKey = HashMap<String, MutableSet<RecordingCandidate>>()
        private val bySource = HashMap<String, MutableSet<RecordingCandidate>>()

        init {
            for (song in saved) {
                val refs = song.references
                val resolved = refs.filter { it.kind == RecordingSourceKind.MISSING_LOCAL }.mapNotNull { source ->
                    local.playlistPaths.resolve(source.path.orEmpty()).track?.let(RecordingSource::from)
                        ?.copy(kind = RecordingSourceKind.MISSING_LOCAL)
                }
                val candidate = RecordingCandidate(song.id, song.createdAt, (refs + resolved).distinctBy { it.key }, song.songKey, song.durationMs)
                byKey.getOrPut(song.songKey) { LinkedHashSet() }.add(candidate)
                for (source in candidate.sources) {
                    byKey.getOrPut(source.songKey) { LinkedHashSet() }.add(candidate)
                    bySource.getOrPut(source.key) { LinkedHashSet() }.add(candidate)
                }
            }
        }

        fun bind(track: Track): Track {
            if (byId.isEmpty()) return track
            val source = RecordingSource.from(track) ?: return track
            val group = RecordingMatcher.group(listOf(source), (byKey[source.songKey].orEmpty() + bySource[source.key].orEmpty()).toList())
            val first = group.firstOrNull() ?: return track
            return byId.getValue(first.id).copy(sources = group.flatMap { it.sources }.distinctBy { it.key }).bind(track)
        }
    }

    /** Build off main. Files win when they match; unavailable files do not become playable snapshots. */
    fun merge(local: LibraryIndex, saved: List<SavedSong>, evidence: List<SavedSong> = saved,
        bound: List<Track> = bindLocal(local, evidence),
    ): LibraryIndex {
        if (saved.isEmpty() && evidence.isEmpty()) return local
        val represented = bound.mapNotNullTo(HashSet()) { it.recordingId }
        val linkedSources = HashMap<String, MutableList<RecordingCandidate>>()
        for (track in bound) if (track.sources.isNotEmpty()) {
            val candidate = RecordingCandidate(track.recordingId ?: track.id.value, track.addedAt ?: 0, track.sources)
            track.sources.filter { it.kind == RecordingSourceKind.YOUTUBE }.forEach { source ->
                linkedSources.getOrPut(source.key) { ArrayList() }.add(candidate)
            }
        }
        val added = saved.asSequence().filter { song ->
            song.id !in represented && RecordingMatcher.group(song.references,
                song.references.flatMap { linkedSources[it.key].orEmpty() }.distinctBy { it.id }).isEmpty()
        }.mapNotNull { it.online() }.filter { it.id !in local.byId }.distinctBy { it.id }.toList()
        val preferred = bound.groupBy { it.recordingId ?: it.id.value }.values.map { copies -> copies.maxBy { it.format?.rank ?: 0 } }
        if (added.isEmpty() && preferred == local.songs) return local
        // Online songs have no filesystem folder. Keep the local folder tree exactly as scanned.
        val identities = bound.associateBy { it.id }
        return LibraryIndex.build(preferred + added).copy(folders = local.folders.map { folder ->
            folder.copy(tracks = folder.tracks.map { identities[it.id] ?: it })
        }, verifiedVideoCopies = verifiedCopies(preferred, evidence))
    }

    private fun verifiedCopies(local: List<Track>, evidence: List<SavedSong>): Map<String, Track> {
        val durable = evidence.associateBy { it.id }
        val unique = HashMap<String, Track>()
        val ambiguous = HashSet<String>()
        for (track in local) {
            val recording = durable[track.recordingId] ?: continue
            val sources = recording.references
            if (sources.size < 2) continue
            val file = RecordingSource.from(track) ?: continue
            if (file.kind != RecordingSourceKind.LOCAL || file.durationMs <= 0 ||
                sources.none { it.kind == RecordingSourceKind.LOCAL && it.key == file.key && it.durationMs > 0 }) continue
            for (source in sources) {
                if (source.kind != RecordingSourceKind.YOUTUBE || source.durationMs <= 0 || source.uri == null ||
                    !RecordingMatcher.canCombine(listOf(file, source), emptyList())) continue
                val video = source.uri.removePrefix(SavedSong.ONLINE_PREFIX)
                val previous = unique.putIfAbsent(video, track)
                if (previous != null && previous.recordingId != track.recordingId) ambiguous += video
            }
        }
        ambiguous.forEach(unique::remove)
        return unique
    }

    /** Literal AND-ed word prefixes, matching the local FTS search without network access. */
    fun searchOnline(catalogue: LibraryIndex, query: String): List<Track> {
        val terms = words.findAll(TextFold.fold(query)).map { it.value }.toList()
        if (terms.isEmpty()) return emptyList()
        return catalogue.songs.filter { track ->
            if (!track.uri.startsWith(SavedSong.ONLINE_PREFIX)) return@filter false
            val tokens = words.findAll(TextFold.fold(listOfNotNull(track.title, track.artist, track.album, track.albumArtist).joinToString(" ")))
                .map { it.value }.toList()
            terms.all { term -> tokens.any { it.startsWith(term) } }
        }
    }

    private val words = Regex("[\\p{L}\\p{N}\\p{M}]+")
}
