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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onOpenArtist: (MusicCollection) -> Unit = {}, duplicates: Int = 0, onStats: () -> Unit = {}, onDuplicates: () -> Unit = {},
    favourites: List<Track> = emptyList(), onSeeAll: (String) -> Unit = {},
) {
    val newest = remember(library) { library.songs.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(100) }
    // Your artists: the most played first, then those with the most songs.
    val artists = remember(library, stats) {
        library.artists.filter { it.name.isNotBlank() && !it.name.equals("Unknown artist", true) }
            .sortedWith(compareByDescending<MusicCollection> { a -> a.tracks.sumOf { stats[it.key.value]?.plays ?: 0 } }.thenByDescending { it.tracks.size })
            .take(15)
    }
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
        // Three one-tap starts, most used first. A row of buttons instead of one huge button: Home stays
        // about music, not about a control.
        item(key = "starts") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onShuffle(library.songs) }, modifier = Modifier.weight(1.2f).height(56.dp)) {
                    Symbol(Glyph.SHUFFLE, size = 20.dp); Spacer(Modifier.size(8.dp)); Text("Shuffle", maxLines = 1)
                }
                FilledTonalButton(enabled = favourites.isNotEmpty(), onClick = { onPlay(favourites, 0) }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Symbol(Glyph.FAVOURITE, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Liked", maxLines = 1)
                }
                FilledTonalButton(enabled = newest.isNotEmpty(), onClick = { onPlay(newest, 0) }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Symbol(Glyph.ADD, size = 20.dp); Spacer(Modifier.size(8.dp)); Text("New", maxLines = 1)
                }
            }
        }
        if (recentAlbums.isNotEmpty()) { item(key = "recent-h") { SectionTitle("Played lately") { onSeeAll("HISTORY") } }; item(key = "recent") { AlbumRow(recentAlbums, onOpenAlbum) { onPlay(it.tracks, 0) } } }
        if (artists.size > 1) {
            item(key = "artists-h") { SectionTitle("Your artists") { onSeeAll("ARTISTS") } }
            item(key = "artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(artists, key = { it.id }) { artist ->
                        Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open ${artist.name}") { onOpenArtist(artist) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistImage(artist.name, Modifier.size(88.dp), 88.dp)
                            Text(artist.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        if (addedAlbums.isNotEmpty()) { item(key = "added-h") { SectionTitle("Added lately") { onSeeAll("ALBUMS") } }; item(key = "added") { AlbumRow(addedAlbums, onOpenAlbum) { onPlay(it.tracks, 0) } } }
        val lists = smart.filter { it.tracks.isNotEmpty() }
        if (lists.isNotEmpty()) {
            item(key = "smart-h") { SectionTitle("Smart playlists") { onSeeAll("SMART") } }
            item(key = "smart") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lists, key = { it.key }) { list ->
                        AssistChip(onClick = { onPlay(list.tracks, 0) }, label = { Text("${list.playlist.name} · ${list.tracks.size}") },
                            leadingIcon = { Symbol(Glyph.SMART, size = 18.dp) })
                    }
                }
            }
        }
        item(key = "cards") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeCard(Glyph.STATS, "Your listening", "Plays, time, top artists", Modifier.weight(1f), onStats)
                // Shown only when there is something to tidy: an empty card would be noise.
                if (duplicates > 0) HomeCard(Glyph.DUPLICATES, "Duplicates", "$duplicates songs kept twice", Modifier.weight(1f), onDuplicates)
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
private fun HomeCard(glyph: Glyph, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(glyph, color = MaterialTheme.colorScheme.primary, filled = true)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/**
 * Songs kept twice: each group shows every copy, best first (marked "Keep"); the others can go to
 * the trash (Android keeps them 30 days, so nothing is lost by a wrong tap).
 */
@Composable
internal fun DuplicatesPage(groups: List<List<Track>>, currentId: String?, modifier: Modifier, onPlay: (List<Track>, Int) -> Unit, onTrash: (List<Track>) -> Unit) {
    val extra = groups.flatMap { it.drop(1) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (groups.isEmpty()) "No song is kept twice." else "${groups.size} songs are kept in more than one file. The best copy is kept; the others can go to the trash.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (extra.isNotEmpty()) Button(onClick = { onTrash(extra) }) { Text("Move ${extra.size} extra copies to trash") }
            }
        }
        groups.forEach { group ->
            item(key = "h-" + group.first().id.value) {
                Text("${group.first().title} · ${group.first().artist}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            group.forEachIndexed { i, track ->
                item(key = track.id.value) {
                    ListItem(
                        headlineContent = { Text(track.format?.label ?: "Unknown format") },
                        supportingContent = { Text(track.path ?: track.uri, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Artwork(track.artworkUri, Modifier.size(48.dp), 12.dp) },
                        trailingContent = {
                            if (i == 0) Text("Keep", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            else IconButton(onClick = { onTrash(listOf(track)) }, modifier = Modifier.semantics { contentDescription = "Move this copy to trash" }) { Symbol(Glyph.DELETE) }
                        },
                        modifier = Modifier.clickable { onPlay(group, i) },
                    )
                }
            }
        }
    }
}

/** A section heading; with [onSeeAll], the whole heading row opens the full list (a bigger target than a small link). */
@Composable
private fun SectionTitle(text: String, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp).clip(RoundedCornerShape(16.dp))
        .then(if (onSeeAll != null) Modifier.clickable(onClickLabel = "See all $text", onClick = onSeeAll) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onSeeAll != null) Symbol(Glyph.CHEVRON, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
            val tiles = listOf("Listened" to hours(s.listenedMs), "Plays" to s.plays.toString(), "Songs" to s.songs.toString(), "Total length" to hours(s.totalMs),
                "Albums" to s.albums.toString(), "Artists" to s.artists.toString(), "Lossless" to s.lossless.toString(), "Hi-res" to s.hiRes.toString())
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) } }
                }
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
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), modifier = modifier) {
        Column(Modifier.padding(20.dp)) {
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
