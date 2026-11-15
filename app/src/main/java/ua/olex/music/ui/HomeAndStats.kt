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
import androidx.compose.material3.toShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
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
/**
 * Home, expressive: a bento to jump back in (the last album large, Shuffle and Liked as bright
 * tiles beside it), artists in M3E shapes, albums in a masked carousel, smart lists as colourful
 * pills, a big-number listening card, and the most played with large ranks. Colour comes from the
 * wallpaper's accent families (primary, secondary, tertiary containers), never invented.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun HomePage(
    library: LibraryIndex, history: List<Track>, stats: Map<String, SongStats>, smart: List<SmartPlaylistUi>,
    currentId: String?, modifier: Modifier, onPlay: (List<Track>, Int) -> Unit, onShuffle: (List<Track>) -> Unit,
    onOpenAlbum: (MusicCollection) -> Unit, onTrackActions: (Track) -> Unit,
    onOpenArtist: (MusicCollection) -> Unit = {}, duplicates: Int = 0, onStats: () -> Unit = {}, onDuplicates: () -> Unit = {},
    favourites: List<Track> = emptyList(), onSeeAll: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val newest = remember(library) { library.songs.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(100) }
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
        library.songs.map { it to (stats[it.key.value]?.plays ?: 0) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
    }
    val listened = remember(library, stats) { LibraryStats.of(library) { stats[it.key.value] } }
    val hero = recentAlbums.firstOrNull() ?: addedAlbums.firstOrNull() ?: library.albums.firstOrNull()

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (library.songs.isEmpty()) { item { Text("No music yet. Put music in the Music folder, then rescan.", Modifier.padding(16.dp)) }; return@LazyColumn }

        // Bento: the album to jump back into, large; two bright one-tap starts beside it.
        item(key = "bento") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(264.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hero != null) Surface(onClick = { onOpenAlbum(hero) }, shape = RoundedCornerShape(32.dp), color = cs.primaryContainer,
                    modifier = Modifier.weight(1.45f).fillMaxHeight()) {
                    Box {
                        Artwork(hero.artworkUri, Modifier.fillMaxSize(), 0.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE)
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 72.dp, bottom = 16.dp)) {
                            Text(if (recentAlbums.isNotEmpty()) "JUMP BACK IN" else "FROM YOUR LIBRARY", style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                            Text(hero.name, style = MaterialTheme.typography.titleLargeEmphasized, color = androidx.compose.ui.graphics.Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(hero.subtitle.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), maxLines = 1)
                        }
                        // The play button is a soft burst, the M3E signature shape.
                        Surface(onClick = { haptics.confirm(); onPlay(hero.tracks, 0) }, shape = androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
                            color = cs.primary, contentColor = cs.onPrimary, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(56.dp)
                                .semantics { contentDescription = "Play ${hero.name}" }) {
                            Box(contentAlignment = Alignment.Center) { Symbol(Glyph.PLAY, size = 28.dp, filled = true) }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartTile("Shuffle all", "${library.songs.size} songs", Glyph.SHUFFLE, androidx.compose.material3.MaterialShapes.Sunny.toShape(),
                        cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary, Modifier.weight(1f)) { haptics.confirm(); onShuffle(library.songs) }
                    StartTile("Liked", if (favourites.isEmpty()) "Nothing yet" else "${favourites.size} songs", Glyph.FAVOURITE, androidx.compose.material3.MaterialShapes.Heart.toShape(),
                        cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary, Modifier.weight(1f), enabled = favourites.isNotEmpty()) { haptics.confirm(); onPlay(favourites, 0) }
                }
            }
        }
        if (newest.isNotEmpty()) item(key = "new") {
            Surface(onClick = { haptics.confirm(); onPlay(newest, 0) }, shape = androidx.compose.foundation.shape.CircleShape, color = cs.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().height(56.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(androidx.compose.material3.MaterialShapes.Cookie6Sided.toShape()).background(cs.primary), contentAlignment = Alignment.Center) {
                        Symbol(Glyph.ADD, color = cs.onPrimary, bold = true)
                    }
                    Text("Play what's new", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Text("${newest.size} songs", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }

        if (artists.size > 1) {
            item(key = "artists-h") { SectionTitle("Your artists") { onSeeAll("ARTISTS") } }
            item(key = "artists") {
                // Each artist in its own M3E shape: playful, and never mistaken for an album cover.
                val shapes = listOf(androidx.compose.material3.MaterialShapes.Cookie9Sided, androidx.compose.material3.MaterialShapes.Clover4Leaf,
                    androidx.compose.material3.MaterialShapes.Arch, androidx.compose.material3.MaterialShapes.Cookie12Sided, androidx.compose.material3.MaterialShapes.Pill,
                    androidx.compose.material3.MaterialShapes.SoftBurst, androidx.compose.material3.MaterialShapes.Circle, androidx.compose.material3.MaterialShapes.Ghostish)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists.size) { i ->
                        val artist = artists[i]
                        Column(Modifier.width(112.dp).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open ${artist.name}") { onOpenArtist(artist) }
                            .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistImage(artist.name, Modifier.size(104.dp), 104.dp, shapes[i % shapes.size].toShape())
                            Text(artist.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }

        if (recentAlbums.size > 1) { item(key = "recent-h") { SectionTitle("Played lately") { onSeeAll("HISTORY") } }; item(key = "recent") { AlbumCarousel(recentAlbums.drop(1), onOpenAlbum) } }
        if (addedAlbums.isNotEmpty()) { item(key = "added-h") { SectionTitle("Added lately") { onSeeAll("ALBUMS") } }; item(key = "added") { AlbumCarousel(addedAlbums, onOpenAlbum) } }

        val lists = smart.filter { it.tracks.isNotEmpty() }
        if (lists.isNotEmpty()) {
            item(key = "smart-h") { SectionTitle("Made for you") { onSeeAll("SMART") } }
            item(key = "smart") {
                val accents = listOf(cs.primaryContainer to cs.onPrimaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer, cs.secondaryContainer to cs.onSecondaryContainer)
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lists.forEachIndexed { i, list ->
                        val (bg, fg) = accents[i % accents.size]
                        Surface(onClick = { haptics.confirm(); onPlay(list.tracks, 0) }, shape = androidx.compose.foundation.shape.CircleShape, color = bg, contentColor = fg) {
                            Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(32.dp).clip(androidx.compose.material3.MaterialShapes.Cookie4Sided.toShape()).background(fg.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                    Symbol(Glyph.SMART, size = 18.dp, filled = true)
                                }
                                Text(list.playlist.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }

        // Listening as one big number, with a shape for character; Duplicates beside it only when needed.
        item(key = "cards") {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(onClick = onStats, shape = RoundedCornerShape(32.dp), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, modifier = Modifier.weight(1f).height(150.dp)) {
                    Box {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 24.dp, y = (-24).dp).size(120.dp)
                            .clip(androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape()).background(cs.onTertiaryContainer.copy(alpha = 0.08f)))
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            Text(if (listened.listenedMs >= 3_600_000) "${listened.listenedMs / 3_600_000} h" else "${listened.listenedMs / 60_000} min",
                                style = MaterialTheme.typography.displaySmallEmphasized)
                            Text("listened · ${listened.plays} plays", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (duplicates > 0) Surface(onClick = onDuplicates, shape = RoundedCornerShape(32.dp), color = cs.surfaceContainerHigh, modifier = Modifier.weight(0.75f).height(150.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.size(40.dp).clip(androidx.compose.material3.MaterialShapes.Pill.toShape()).background(cs.errorContainer), contentAlignment = Alignment.Center) {
                            Symbol(Glyph.DUPLICATES, color = cs.onErrorContainer, size = 20.dp)
                        }
                        Column {
                            Text("$duplicates", style = MaterialTheme.typography.headlineMediumEmphasized)
                            Text("kept twice", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (mostPlayed.isNotEmpty()) {
            item(key = "most-h") { SectionTitle("On repeat") }
            mostPlayed.forEachIndexed { index, track ->
                item(key = "most-${track.id.value}") {
                    Row(Modifier.fillMaxWidth().clickable { onPlay(mostPlayed, index) }.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", style = MaterialTheme.typography.headlineMediumEmphasized, color = cs.primary, modifier = Modifier.width(40.dp))
                        Artwork(track.artworkUri, Modifier.size(52.dp), 14.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (track.id.value == currentId) cs.primary else cs.onSurface)
                            Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = { onTrackActions(track) }, modifier = Modifier.semantics { contentDescription = "Actions for ${track.title}" }) { Symbol(Glyph.MORE, color = cs.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/** A bright start tile: the icon in an M3E shape, title and a count. */
@Composable
private fun StartTile(title: String, subtitle: String, glyph: Glyph, shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color, onAccent: androidx.compose.ui.graphics.Color,
    modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp), color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(48.dp).clip(shape).background(accent), contentAlignment = Alignment.Center) { Symbol(glyph, color = onAccent, filled = true) }
            Column {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Albums in an M3E multi-browse carousel: one large, the next ones narrower, masks morph as they move. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AlbumCarousel(albums: List<MusicCollection>, onOpen: (MusicCollection) -> Unit) {
    androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel(
        state = androidx.compose.material3.carousel.rememberCarouselState { albums.size },
        preferredItemWidth = 200.dp, itemSpacing = 8.dp, contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().height(220.dp),
    ) { i ->
        val album = albums[i]
        Box(Modifier.fillMaxSize().maskClip(RoundedCornerShape(28.dp)).clickable(onClickLabel = "Open ${album.name}") { onOpen(album) }) {
            Artwork(album.artworkUri, Modifier.fillMaxSize(), 0.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.55f to androidx.compose.ui.graphics.Color.Transparent,
                1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))))
            // Names only on the large item: on the narrow ones they would be cut mid-word.
            val info = carouselItemDrawInfo
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp).graphicsLayer {
                alpha = if (info.maxSize > 0f) ((info.size / info.maxSize - 0.9f) / 0.1f).coerceIn(0f, 1f) else 1f
            }) {
                Text(album.name, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), maxLines = 1)
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SectionTitle(text: String, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp).clip(RoundedCornerShape(16.dp))
        .then(if (onSeeAll != null) Modifier.clickable(onClickLabel = "See all $text", onClick = onSeeAll) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
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
