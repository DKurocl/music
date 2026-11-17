package ua.olex.music.model

/**
 * The library as Android Auto, Assistant and other media browsers see it: a root with sections,
 * sections with collections, collections with songs. Pure, so the whole tree is tested here; the
 * playback service only turns nodes into Media3 items.
 *
 * Ids: `root`, `section:<name>`, `album:<id>`, `artist:<id>`, `genre:<id>`, `folder:<id>`,
 * `smart:<index>`, `playlist:<id>`. A song listed inside a collection carries its context as
 * `<parentId>|<trackId>`, so choosing it can play the whole collection from that song
 * ([resolvePlay]); the queue itself always holds plain track ids.
 */
data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUri: String? = null,
    /** Null for a folder of more nodes; the song for a playable node. */
    val track: Track? = null,
) {
    val playable: Boolean get() = track != null
}

/** One input snapshot: the library, the smart playlists and the owner's playlists, already resolved. */
class BrowseTree(
    private val library: LibraryIndex,
    private val smart: List<Pair<String, List<Track>>> = emptyList(),
    private val playlists: List<Triple<Long, String, List<Track>>> = emptyList(),
) {
    fun root() = BrowseNode(ROOT, "Library")

    // Songs a browser can name: the library's, and YouTube Music songs kept in the owner's playlists
    // (they have no file, so they are not in the library index but still play).
    private val known: Map<TrackId, Track> by lazy {
        val extra = playlists.flatMap { it.third }.filter { it.id !in library.byId }
        if (extra.isEmpty()) library.byId else library.byId + extra.associateBy { it.id }
    }

    /** The children of [parentId], or null when there is no such node. */
    fun children(parentId: String): List<BrowseNode>? = when {
        parentId == ROOT -> SECTIONS.filter { (id, _) -> sectionHasContent(id) }.map { (id, title) -> BrowseNode("section:$id", title) }
        parentId == "section:albums" -> library.albums.map { BrowseNode("album:${it.id}", it.name, it.subtitle, it.artworkUri) }
        parentId == "section:artists" -> library.artists.map { BrowseNode("artist:${it.id}", it.name, count(it.tracks.size), it.artworkUri) }
        parentId == "section:smart" -> smart.mapIndexed { i, (name, tracks) -> BrowseNode("smart:$i", name, count(tracks.size), tracks.firstNotNullOfOrNull { it.artworkUri }) }
        parentId == "section:playlists" -> playlists.map { (id, name, tracks) -> BrowseNode("playlist:$id", name, count(tracks.size), tracks.firstNotNullOfOrNull { it.artworkUri }) }
        parentId == "section:genres" -> library.genres.map { BrowseNode("genre:${it.id}", it.name, count(it.tracks.size), it.artworkUri) }
        parentId == "section:songs" -> library.songs.map { song(ROOT_SONGS, it) }
        else -> tracksOf(parentId)?.map { song(parentId, it) }
    }

    /** A node by id: sections, collections and songs (plain or with context). */
    fun item(id: String): BrowseNode? {
        if (id == ROOT) return root()
        SECTIONS.firstOrNull { "section:${it.first}" == id }?.let { return BrowseNode(id, it.second) }
        val trackId = id.substringAfterLast('|')
        known[TrackId(trackId)]?.let { return song(id.substringBeforeLast('|', ""), it).copy(id = id) }
        return children(id.substringBefore(':').let { "section:" + sectionFor(it) })?.firstOrNull { it.id == id }
    }

    /**
     * What to play when a node is chosen: the collection of a song chosen inside one, from that
     * song; a whole collection chosen by itself; a plain song alone. Null for an unknown id.
     */
    fun resolvePlay(id: String): Pair<List<Track>, Int>? {
        val parent = id.substringBeforeLast('|', "")
        val trackId = id.substringAfterLast('|')
        known[TrackId(trackId)]?.let { track ->
            val queue = if (parent.isEmpty()) null else if (parent == ROOT_SONGS) library.songs else tracksOf(parent)
            val index = queue?.indexOfFirst { it.id == track.id } ?: -1
            return if (queue != null && index >= 0) queue to index else listOf(track) to 0
        }
        return tracksOf(id)?.takeIf { it.isNotEmpty() }?.let { it to 0 }
    }

    /**
     * Songs for a spoken or typed search ("play Karma Police", "play Radiohead"): matches in title
     * first, then artist, then album, each word required, case and accents ignored.
     */
    fun search(query: String, limit: Int = 50): List<Track> {
        val words = TextFold.fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        fun all(text: String) = TextFold.fold(text).let { t -> words.all { t.contains(it) } }
        // One pass, each song in the first bucket it fits (title, artist, album, all fields); the
        // buckets stop growing once the earlier ones alone fill the limit.
        val buckets = Array(4) { ArrayList<Track>() }
        val songs = if (known.size == library.byId.size) library.songs else library.songs + known.values.filter { it.id !in library.byId }
        for (song in songs) {
            if (buckets[0].size >= limit) break
            val bucket = when {
                all(song.title) -> 0
                all(song.artists.joinToString(" ") + " " + (song.albumArtist ?: "")) -> 1
                all(song.album.orEmpty()) -> 2
                all("${song.title} ${song.artist} ${song.album.orEmpty()}") -> 3
                else -> -1
            }
            if (bucket >= 0 && buckets.take(bucket).sumOf { it.size } < limit) buckets[bucket] += song
        }
        return buckets.flatMap { it }.take(limit)
    }

    private fun tracksOf(id: String): List<Track>? {
        val (kind, rest) = id.substringBefore(':') to id.substringAfter(':', "")
        return when (kind) {
            "album" -> library.albums.firstOrNull { it.id == rest }?.tracks
            "artist" -> library.artists.firstOrNull { it.id == rest }?.tracks
            "genre" -> library.genres.firstOrNull { it.id == rest }?.tracks
            "folder" -> library.folders.firstOrNull { it.id == rest }?.tracks
            "smart" -> rest.toIntOrNull()?.let { smart.getOrNull(it)?.second }
            "playlist" -> rest.toLongOrNull()?.let { pid -> playlists.firstOrNull { it.first == pid }?.third }
            else -> null
        }
    }

    private fun sectionHasContent(id: String) = when (id) {
        "albums" -> library.albums.isNotEmpty(); "artists" -> library.artists.isNotEmpty()
        "smart" -> smart.any { it.second.isNotEmpty() }; "playlists" -> playlists.isNotEmpty()
        "genres" -> library.genres.isNotEmpty(); else -> library.songs.isNotEmpty()
    }

    private fun sectionFor(kind: String) = when (kind) { "album" -> "albums"; "artist" -> "artists"; "genre" -> "genres"; "smart" -> "smart"; "playlist" -> "playlists"; else -> kind }
    private fun song(parent: String, t: Track) = BrowseNode(if (parent.isEmpty()) t.id.value else "$parent|${t.id.value}", t.title, t.artist, t.artworkUri, t)
    private fun count(n: Int) = if (n == 1) "1 song" else "$n songs"

    companion object {
        const val ROOT = "root"
        private const val ROOT_SONGS = "section:songs"
        private val SECTIONS = listOf("albums" to "Albums", "artists" to "Artists", "smart" to "Smart playlists", "playlists" to "Playlists", "genres" to "Genres", "songs" to "Songs")
    }
}
