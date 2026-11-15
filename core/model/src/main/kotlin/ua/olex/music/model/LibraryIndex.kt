package ua.olex.music.model

import java.text.Normalizer
import java.util.Locale

/** A stable, ordered collection of playable tracks, used by album, artist, genre and folder pages. */
data class MusicCollection(
    val id: String,
    val name: String,
    val subtitle: String?,
    val tracks: List<Track>,
) {
    val artworkUri: String? = tracks.firstOrNull { it.artworkUri != null && (it.metadata?.coverLength ?: 0) > 0 }?.artworkUri
        ?: tracks.firstNotNullOfOrNull { it.artworkUri }
    val durationMs: Long = tracks.sumOf { it.durationMs.coerceAtLeast(0) }
}

/** One immutable library snapshot. Build off the UI thread; all pages share the same ordering. */
data class LibraryIndex(
    val songs: List<Track>,
    val albums: List<MusicCollection>,
    val artists: List<MusicCollection>,
    val genres: List<MusicCollection>,
    val folders: List<MusicCollection>,
) {
    val byId: Map<TrackId, Track> = songs.associateBy { it.id }
    val bySongKey: Map<String, List<Track>> by lazy(LazyThreadSafetyMode.PUBLICATION) { songs.groupBy { it.key.value } }

    val playlistPaths: PlaylistPathIndex by lazy(LazyThreadSafetyMode.PUBLICATION) { PlaylistPathIndex(songs) }

    /** Years, newest first; songs in album order (artist, album, disc, track). */
    val years: List<MusicCollection> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        albums.flatMap { it.tracks }.filter { (it.year ?: 0) > 0 }.groupBy { it.year!! }.toSortedMap(reverseOrder())
            .map { (year, tracks) -> MusicCollection("year$year", year.toString(), albumCount(tracks), tracks) }
    }

    /** Composers from the files' tags, by name; songs in album order. */
    val composers: List<MusicCollection> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        albums.flatMap { it.tracks }
            .flatMap { t -> t.metadata?.composers.orEmpty().filter(String::isNotBlank).distinctBy { it.trim().lowercase(Locale.ROOT) }.map { it.trim() to t } }
            .groupBy({ it.first.lowercase(Locale.ROOT) }, { it })
            .map { (key, rows) -> MusicCollection("composer${key.length}:$key", rows.first().first, null, rows.map { it.second }.distinctBy { it.id }) }
            .sortedBy { LibraryOrder.name(it.name) }
    }

    companion object {
        val EMPTY = LibraryIndex(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        /** Album artist owns a compilation; disc and track numbers order its playback queue. */
        fun build(tracks: List<Track>): LibraryIndex {
            // A one-file album with a cue sheet becomes its tracks; everything else stays as it is.
            @Suppress("NAME_SHADOWING")
            val tracks = if (tracks.none { it.metadata?.cueSheet != null }) tracks else tracks.flatMap { t ->
                t.metadata?.cueSheet?.let(CueSheet::parse)?.split(t)?.takeIf { it.isNotEmpty() } ?: listOf(t)
            }
            // Normalize once per track, rather than allocating strings in every sort comparison.
            val entries = tracks.map(::Entry)
            val songs = entries.sortedWith(songOrder).map { it.track }
            fun groups(
                kind: String,
                key: (Entry) -> List<String>,
                name: (Entry) -> String,
                subtitle: (Entry) -> String? = { null },
                order: Comparator<Entry>,
                source: List<Entry> = entries,
            ): List<MusicCollection> = source.groupBy(key).map { (parts, rows) ->
                val ordered = rows.sortedWith(order)
                val first = ordered.first()
                MusicCollection(
                    // Length-prefixed parts avoid collisions between album/artist names containing separators.
                    id = kind + parts.joinToString("") { "${it.length}:$it" },
                    name = name(first), subtitle = subtitle(first), tracks = ordered.map { it.track },
                )
            }.sortedWith(compareBy<MusicCollection> { LibraryOrder.name(it.name) }
                .thenBy { LibraryOrder.name(it.subtitle.orEmpty()) }.thenBy { it.id })

            return LibraryIndex(
                songs = songs,
                albums = groups("album", { listOf(it.artistId, it.albumId) }, { it.album }, { it.artist }, albumOrder),
                artists = groups("artist", { listOf(it.artistId) }, { it.artist }, order = collectionOrder,
                    source = tracks.flatMap { track ->
                        val names = track.metadata?.albumArtists?.takeIf { it.isNotEmpty() }
                            ?: track.albumArtist?.takeIf(String::isNotBlank)?.let(::listOf)
                            ?: track.artists.ifEmpty { listOf(track.artist) }
                        names.distinctBy(::identity).map { Entry(track, artistOverride = it) }
                    }),
                genres = groups("genre", { listOf(identity(it.genre)) }, { it.genre }, order = songOrder,
                    source = tracks.flatMap { track -> track.genres.ifEmpty { listOf(track.genre ?: "Unknown genre") }
                        .distinctBy(::identity).map { Entry(track, genreOverride = it) } }),
                folders = groups("folder", { listOf(it.folder) }, { it.folder.substringAfterLast('/') }, { it.folder }, collectionOrder),
            )
        }

        private class Entry(val track: Track, artistOverride: String? = null, genreOverride: String? = null) {
            val titleOrder = LibraryOrder.name(track.title)
            val artist = artistOverride ?: track.albumArtist?.takeIf(String::isNotBlank) ?: track.artist
            val album = track.album?.takeIf(String::isNotBlank) ?: "Unknown album"
            val genre = genreOverride ?: track.genre?.takeIf(String::isNotBlank) ?: "Unknown genre"
            val folder = track.path?.substringBeforeLast('/', "")?.trimEnd('/')?.takeIf(String::isNotBlank) ?: "Unknown folder"
            val artistId = identity(artist)
            val albumId = identity(album)
            val artistOrder = LibraryOrder.name(artist)
            val albumOrder = LibraryOrder.name(album)
        }

        private val songOrder = compareBy<Entry> { it.titleOrder }.thenBy { it.track.id.value }
        private val albumOrder = compareBy<Entry> { it.track.discNumber?.takeIf { n -> n > 0 } ?: 1 }
            .thenBy { it.track.trackNumber?.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
            .then(songOrder)
        private val collectionOrder = compareBy<Entry> { it.artistOrder }.thenBy { it.albumOrder }
            .thenBy { it.albumId }.then(albumOrder)
        private fun identity(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}

private fun albumCount(tracks: List<Track>): String {
    val n = tracks.mapNotNull { it.album }.distinct().size
    return if (n == 1) "1 album" else "$n albums"
}

/** Sort labels independent of device locale, ignoring leading English articles and accent marks. */
object LibraryOrder {
    private val marks = Regex("\\p{M}+")
    private val article = Regex("^(the|an|a)\\s+")

    fun name(value: String): String = article.replace(
        marks.replace(Normalizer.normalize(value, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).trim(), "",
    )
}

/** Turns user text into literal AND-ed token prefixes, never executable FTS operators or SQL. */
object LibrarySearch {
    private val words = Regex("[\\p{L}\\p{N}\\p{M}]+")

    fun expression(text: String): String? = words.findAll(text).map { "\"${it.value}*\"" }
        .joinToString(" ").ifEmpty { null }
}
