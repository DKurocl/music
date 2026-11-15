package ua.olex.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.LibraryStats
import ua.olex.music.model.MusicCollection
import ua.olex.music.model.SongStats
import ua.olex.music.model.Track

/**
 * The first page: the usual thing in one tap. Shuffle everything, albums played lately, albums
 * added lately, the most played songs and the smart playlists, each one tap from playing.
 */
@Composable
internal fun HomePage(
    library: LibraryIndex, history: List<Track>, stats: Map<String, SongStats>, smart: List<SmartPlaylistUi>,
    currentId: String?, modifier: Modifier, onPlay: (List<Track>, Int) -> Unit, onShuffle: (List<Track>) -> Unit,
    onOpenAlbum: (MusicCollection) -> Unit, onTrackActions: (Track) -> Unit,
) {
    val albumOf = remember(library) { library.albums.flatMap { a -> a.tracks.map { it.id to a } }.toMap() }
    val recentAlbums = remember(history, albumOf) { history.mapNotNull { albumOf[it.id] }.distinctBy { it.id }.take(12) }
    val addedAlbums = remember(library) {
        library.albums.filter { a -> a.tracks.any { it.addedAt != null } }.sortedByDescending { a -> a.tracks.maxOf { it.addedAt ?: 0 } }.take(12)
    }
    val mostPlayed = remember(library, stats) {
        library.songs.map { it to (stats[it.key.value]?.plays ?: 0) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(10).map { it.first }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (library.songs.isEmpty()) { item { Text("No music yet. Put music in the Music folder, then rescan.", Modifier.padding(16.dp)) }; return@LazyColumn }
        item(key = "shuffle") {
            Button(onClick = { onShuffle(library.songs) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(56.dp)) {
                Symbol(Glyph.SHUFFLE, size = 20.dp); Spacer(Modifier.size(8.dp)); Text("Shuffle all ${library.songs.size} songs")
            }
        }
        if (recentAlbums.isNotEmpty()) { item(key = "recent-h") { SectionTitle("Played lately") }; item(key = "recent") { AlbumRow(recentAlbums, onOpenAlbum) { onPlay(it.tracks, 0) } } }
        if (addedAlbums.isNotEmpty()) { item(key = "added-h") { SectionTitle("Added lately") }; item(key = "added") { AlbumRow(addedAlbums, onOpenAlbum) { onPlay(it.tracks, 0) } } }
        val lists = smart.filter { it.tracks.isNotEmpty() }
        if (lists.isNotEmpty()) {
            item(key = "smart-h") { SectionTitle("Smart playlists") }
            item(key = "smart") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lists, key = { it.key }) { list ->
                        AssistChip(onClick = { onPlay(list.tracks, 0) }, label = { Text("${list.playlist.name} · ${list.tracks.size}") },
                            leadingIcon = { Symbol(Glyph.SMART, size = 18.dp) })
                    }
                }
            }
        }
        if (mostPlayed.isNotEmpty()) {
            item(key = "most-h") { SectionTitle("Most played") }
            mostPlayed.forEachIndexed { index, track ->
                item(key = "most-${track.id.value}") {
                    TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }) { onPlay(mostPlayed, index) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))

@Composable
private fun AlbumRow(albums: List<MusicCollection>, onOpen: (MusicCollection) -> Unit, onPlay: (MusicCollection) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { album ->
            Column(Modifier.width(148.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open ${album.name}") { onOpen(album) }) {
                Box {
                    Artwork(album.artworkUri, Modifier.size(148.dp), 16.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
                    FilledIconButton(onClick = { onPlay(album) }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp)) {
                        Symbol(Glyph.PLAY, size = 20.dp, filled = true)
                    }
                }
                Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(album.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Library and listening numbers: size, quality, time listened, top artists and songs. */
@Composable
internal fun StatsPage(library: LibraryIndex, stats: Map<String, SongStats>, modifier: Modifier, onPlay: (List<Track>, Int) -> Unit) {
    val s = remember(library, stats) { LibraryStats.of(library) { stats[it.key.value] } }
    fun hours(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000} h ${ms / 60_000 % 60} min" else "${ms / 60_000} min"
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Songs", s.songs.toString()); StatTile("Albums", s.albums.toString()); StatTile("Artists", s.artists.toString())
                StatTile("Total length", hours(s.totalMs)); StatTile("Lossless", "${s.lossless}"); StatTile("Hi-res", "${s.hiRes}")
                StatTile("Plays", s.plays.toString()); StatTile("Listened", hours(s.listenedMs))
            }
        }
        if (s.topArtists.isNotEmpty()) {
            item { Text("Top artists", style = MaterialTheme.typography.titleLarge) }
            items(s.topArtists) { (name, plays) -> BarRow(name, plays, s.topArtists.first().second) }
        }
        if (s.topSongs.isNotEmpty()) {
            item { Text("Top songs", style = MaterialTheme.typography.titleLarge) }
            val queue = s.topSongs.map { it.first }
            items(s.topSongs.size) { i ->
                val (track, plays) = s.topSongs[i]
                BarRow("${track.title} · ${track.artist}", plays, s.topSongs.first().second, Modifier.clickable { onPlay(queue, i) })
            }
        }
        item { Text("Formats", style = MaterialTheme.typography.titleLarge) }
        items(s.formats) { (codec, count) -> BarRow(codec, count, s.formats.first().second) }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.widthIn(min = 100.dp).padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarRow(label: String, value: Int, max: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(progress = { if (max > 0) value.toFloat() / max else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), drawStopIndicator = {})
    }
}
