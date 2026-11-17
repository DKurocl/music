package ua.olex.music.model

/** A durable song reference and display snapshot, independent of the rebuildable MediaStore id. */
data class SavedSong(
    val id: String,
    val songKey: String,
    val path: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUri: String?,
    val sources: List<RecordingSource> = emptyList(),
    val createdAt: Long = 0,
) {
    /** Sources belong to the recording even while their files are absent from the current scan. */
    val references: List<RecordingSource> get() = sources.ifEmpty { listOfNotNull(RecordingSource.legacy(this)) }
    val persistent: Boolean get() = !id.startsWith(SNAPSHOT_PREFIX)

    /** Prefer a matching file; retain every source when projecting it into the current library. */
    fun resolve(library: LibraryIndex): Track? {
        val refs = references
        val missing = refs.firstOrNull { it.kind == RecordingSourceKind.MISSING_LOCAL }
        if (missing != null) library.playlistPaths.resolve(missing.path.orEmpty()).track?.let { return bind(it) }
        val possible = (refs.map { it.songKey } + songKey).distinct().flatMap { library.bySongKey[it].orEmpty() }.distinctBy { it.id }
        val matches = RecordingMatcher.group(refs, possible.mapNotNull { track ->
            val source = RecordingSource.from(track)?.takeIf { it.kind == RecordingSourceKind.LOCAL } ?: return@mapNotNull null
            RecordingCandidate(track.id.value, track.addedAt ?: 0, listOf(source))
        }).mapTo(HashSet()) { it.id }
        val candidates = possible.filter { it.id.value in matches }
        val paths = refs.mapNotNull { it.path }.toSet()
        val file = candidates.firstOrNull { it.path.orEmpty() == path }
            ?: candidates.filter { it.path in paths }.maxByOrNull { it.format?.rank ?: 0 }
            ?: candidates.maxByOrNull { it.format?.rank ?: 0 }
        return file?.let(::bind) ?: online()
    }

    /** The online reserve survives a change of the preferred local path. */
    fun online(): Track? {
        val refs = references
        val online = refs.firstOrNull { it.kind == RecordingSourceKind.YOUTUBE && it.uri == path }
            ?: refs.firstOrNull { it.kind == RecordingSourceKind.YOUTUBE } ?: return null
        val uri = online.uri ?: return null
        val videoId = uri.removePrefix(ONLINE_PREFIX).takeIf { it.isNotBlank() } ?: return null
        return Track(TrackId("yt:$videoId"), uri, title, artist, album = album,
            durationMs = online.durationMs.takeIf { it > 0 } ?: durationMs,
            artworkUri = online.artworkUri ?: artworkUri.takeIf { path.startsWith(ONLINE_PREFIX) }, recordingId = id.takeIf { persistent },
            sources = refs.takeIf { persistent || it.size > 1 }.orEmpty(), addedAt = createdAt.takeIf { it > 0 })
    }

    /** Honest metadata for an unavailable recording, without borrowing an unrelated request's file. */
    fun displayTrack(): Track {
        online()?.let { return it }
        val source = references.firstOrNull { it.path == path } ?: references.firstOrNull()
        return Track(TrackId(id), source?.uri.orEmpty(), title, artist, album = album,
            durationMs = source?.durationMs?.takeIf { it > 0 } ?: durationMs,
            artworkUri = source?.artworkUri ?: artworkUri, path = source?.path,
            clipStartMs = source?.clipStartMs, clipEndMs = source?.clipEndMs,
            recordingId = id.takeIf { persistent }, sources = references)
    }

    /** Attach identity and reserves without changing the file's playback address, tags or quality. */
    fun bind(track: Track): Track {
        val refs = (listOfNotNull(RecordingSource.from(track)) + references + track.sources).distinctBy { it.key }
        if (!persistent && refs.none { it.kind == RecordingSourceKind.YOUTUBE }) return track
        return track.copy(recordingId = id.takeIf { persistent } ?: track.recordingId, sources = refs)
    }

    companion object {
        const val ONLINE_PREFIX = "ytm://"
        /** Lookup snapshots are not durable IDs; UserRepository assigns or finds the recording ID. */
        const val SNAPSHOT_PREFIX = "source:"

        fun from(track: Track): SavedSong {
            val key = track.key.value
            val fresh = RecordingSource.from(track)
            val old = track.sources.firstOrNull { it.key == fresh?.key }
            val primary = if (fresh == null || old == null) fresh else fresh.copy(
                songKey = if (fresh.kind == RecordingSourceKind.YOUTUBE) old.songKey else fresh.songKey,
                durationMs = fresh.durationMs.takeIf { it > 0 } ?: old.durationMs,
                musicBrainzId = fresh.musicBrainzId ?: old.musicBrainzId,
                qualityRank = fresh.qualityRank.takeIf { it > 0 } ?: old.qualityRank,
                artworkUri = if (fresh.kind == RecordingSourceKind.YOUTUBE) old.artworkUri ?: fresh.artworkUri else fresh.artworkUri ?: old.artworkUri,
            )
            val refs = (listOfNotNull(primary) + track.sources).distinctBy { it.key }
            val path = track.path ?: primary?.uri?.takeIf { it.startsWith(ONLINE_PREFIX) }.orEmpty()
            val sourceKey = primary?.key.orEmpty()
            val id = track.recordingId ?: "$SNAPSHOT_PREFIX${key.length}:$key${sourceKey.length}:$sourceKey"
            return SavedSong(id, key, path, track.title, track.artist, track.album, track.durationMs.coerceAtLeast(0), track.artworkUri, refs)
        }
    }

}

/** A user-owned ordered playlist. Missing files remain in [songs] until explicitly removed. */
data class UserPlaylist(val id: Long, val name: String, val songs: List<SavedSong>, val createdAt: Long)

/** One actual playback start, not a click or a pause/resume event. */
data class HistoryEntry(val id: Long, val song: SavedSong, val playedAt: Long)
