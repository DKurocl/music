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
    val artworkUri: String? = tracks.firstNotNullOfOrNull { it.artworkUri }
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

    companion object {
        val EMPTY = LibraryIndex(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        /** Album artist owns a compilation; disc and track numbers order its playback queue. */
        fun build(tracks: List<Track>): LibraryIndex {
            // Normalize once per track, rather than allocating strings in every sort comparison.
            val entries = tracks.map(::Entry)
            val songs = entries.sortedWith(songOrder).map { it.track }
            fun groups(
                kind: String,
                key: (Entry) -> List<String>,
                name: (Entry) -> String,
                subtitle: (Entry) -> String? = { null },
                order: Comparator<Entry>,
            ): List<MusicCollection> = entries.groupBy(key).map { (parts, rows) ->
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
                artists = groups("artist", { listOf(it.artistId) }, { it.artist }, order = collectionOrder),
                genres = groups("genre", { listOf(identity(it.genre)) }, { it.genre }, order = songOrder),
                folders = groups("folder", { listOf(it.folder) }, { it.folder.substringAfterLast('/') }, { it.folder }, collectionOrder),
            )
        }

        private class Entry(val track: Track) {
            val titleOrder = LibraryOrder.name(track.title)
            val artist = track.albumArtist?.takeIf(String::isNotBlank) ?: track.artist
            val album = track.album?.takeIf(String::isNotBlank) ?: "Unknown album"
            val genre = track.genre?.takeIf(String::isNotBlank) ?: "Unknown genre"
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
