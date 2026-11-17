package ua.olex.music.model

/** Projects durable online references into the library without changing their stored source links. */
object SavedCatalog {
    /** Build off main. Files win when they match; unavailable files do not become playable snapshots. */
    fun merge(local: LibraryIndex, saved: List<SavedSong>): LibraryIndex {
        val added = saved.asSequence().filter { it.path.startsWith(SavedSong.ONLINE_PREFIX) }
            .mapNotNull { it.resolve(local) }.filter { it.id !in local.byId }.distinctBy { it.id }.toList()
        if (added.isEmpty()) return local
        // Online songs have no filesystem folder. Keep the local folder tree exactly as scanned.
        return LibraryIndex.build(local.songs + added).copy(folders = local.folders)
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
