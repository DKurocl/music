package ua.olex.music.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.combinedClickable
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
    moods: List<YouTubeMusic.Mood> = emptyList(),
    youTubeHome: List<YouTubeMusic.Shelf> = emptyList(),
    /** Set when the app may not read the owner's files: Home asks for them in place, above YouTube Music. */
    onAllowFiles: (() -> Unit)? = null, scanError: String? = null, onScanRetry: () -> Unit = {}, cache: HomeCache = remember(library) { HomeCache() },
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val textScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    // Everything Home shows is worked out from the whole library and the play counts, always off the
    // main thread. Until the first result the last one this process made is shown (or an empty Home
    // with the library's first album), so opening Home never waits on a pass over every song.
    val day = java.time.LocalDate.now().toEpochDay()
    val data = androidx.compose.runtime.key(cache) { androidx.compose.runtime.produceState(cache.last ?: HomeData.EMPTY, library, history, stats, favourites, day) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { homeData(library, history, stats, favourites, day) }.also { cache.last = it }
    }.value }
    val newest = data.newest; val artists = data.artists; val recentAlbums = data.recentAlbums; val addedAlbums = data.addedAlbums
    val mostPlayed = data.mostPlayed; val listened = data.listened; val quickPicks = data.quickPicks
    val hero = recentAlbums.firstOrNull() ?: addedAlbums.firstOrNull() ?: library.albums.firstOrNull()

    // A mood chip filters Home in place, as YouTube Music's do; Back or the same chip again clears it.
    var moodLabel by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val mood = moods.firstOrNull { it.label == moodLabel }
    androidx.activity.compose.BackHandler(mood != null) { moodLabel = null }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    fun chooseMood(label: String?) {
        moodLabel = label
        scrollScope.launch { list.scrollToItem(0) }
    }
    val moodView = mood?.let { m -> androidx.compose.runtime.key(m.label) { rememberMoodView(m, library) } }

    LazyColumn(modifier, state = list, contentPadding = PaddingValues(bottom = 24.dp + LocalPlayerInset.current)) {
        if (scanError != null) item(key = "scan-error") { ErrorNotice(scanError, onScanRetry) }
        // Moods first, as in YouTube Music: Energize, Relax, Sad, Sleep... a tap shows Home for that mood.
        if (moods.isNotEmpty()) item(key = "moods") { MoodRow(moods, Modifier.padding(top = 4.dp, bottom = 4.dp), chosen = mood?.label) { chooseMood(it?.label) } }
        if (mood != null && moodView != null) { moodItems(mood, moodView, currentId); return@LazyColumn }
        // No files yet: YouTube Music's moods and shelves still make a Home (the library part waits).
        val noFiles = library.songs.isEmpty()

        // The owner's own YouTube Music home, at the top as they asked: quick picks, mixes, listen
        // again... Songs they have as files play from the files.
        youTubeHome.take(4).forEachIndexed { i, shelf ->
            item(key = "yt-h-$i") { SectionTitle(shelf.title) }
            if (shelf.songs.isNotEmpty()) item(key = "yt-s-$i") { QuickPicks(shelf.songs.take(20), currentId, onPlay, onTrackActions) }
            if (shelf.cards.isNotEmpty()) item(key = "yt-c-$i") { YouTubeCards(shelf.cards) }
        }
        if (noFiles) {
            item(key = "no-files") {
                if (onAllowFiles != null) EmptyState(Glyph.FOLDER, "Your music files", "Allow access to the audio files on this phone to play them here, in their best quality and offline.",
                    actions = { androidx.compose.material3.Button(onClick = onAllowFiles) { Text("Allow") } })
                else EmptyState(Glyph.FOLDER, "No music files yet", "Put music in the Music folder, or download albums from their pages. YouTube Music plays meanwhile.")
            }
            return@LazyColumn
        }

        // Bento: the album to jump back into, large; two bright one-tap starts beside it.
        item(key = "bento") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(264.dp + 144.dp * (textScale - 1f)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hero != null) Surface(onClick = { onOpenAlbum(hero) }, shape = RoundedCornerShape(32.dp), color = cs.primaryContainer,
                    modifier = Modifier.weight(1.45f).fillMaxHeight().squish(0.03f)) {
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
                    StartTile("Liked", if (favourites.isEmpty()) "Nothing yet" else "${favourites.size} songs", Glyph.FAVOURITE, androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
                        cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary, Modifier.weight(1f), enabled = favourites.isNotEmpty()) { haptics.confirm(); onPlay(favourites, 0) }
                }
            }
        }
        if (newest.isNotEmpty()) item(key = "new") {
            Surface(onClick = { haptics.confirm(); onPlay(newest, 0) }, shape = androidx.compose.foundation.shape.CircleShape, color = cs.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().height(56.dp).squish(0.02f)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(androidx.compose.material3.MaterialShapes.Cookie6Sided.toShape()).background(cs.primary), contentAlignment = Alignment.Center) {
                        Symbol(Glyph.ADD, color = cs.onPrimary, bold = true)
                    }
                    Text("Play what's new", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Text("${newest.size} songs", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                }
            }
        }

        if (quickPicks.size >= 4) {
            item(key = "picks-h") { SectionTitle("Quick picks") }
            item(key = "picks") { QuickPicks(quickPicks, currentId, onPlay, onTrackActions) }
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
                        Column(Modifier.width(112.dp).squish(0.06f).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open ${artist.name}") { onOpenArtist(artist) }
                            .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistImage(artist.name, Modifier.size(104.dp), 104.dp, shapes[i % shapes.size].toShape())
                            Text(artist.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }

        if (recentAlbums.size > 1) { item(key = "recent-h") { SectionTitle("Listen again") { onSeeAll("HISTORY") } }; item(key = "recent") { AlbumCarousel(recentAlbums.drop(1), onOpenAlbum) } }
        if (addedAlbums.isNotEmpty()) { item(key = "added-h") { SectionTitle("Added lately") { onSeeAll("ALBUMS") } }; item(key = "added") { AlbumCarousel(addedAlbums, onOpenAlbum) } }

        val lists = smart.filter { it.tracks.isNotEmpty() }
        if (lists.isNotEmpty()) {
            item(key = "smart-h") { SectionTitle("Made for you") { onSeeAll("SMART") } }
            item(key = "smart") {
                // Each list as a cover mosaic with its name, like playlists everywhere else; a tap plays it.
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(lists.size) { i ->
                        val list = lists[i]
                        Column(Modifier.width(148.dp).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Play ${list.playlist.name}") { haptics.confirm(); onPlay(list.tracks, 0) }.squish(0.03f)) {
                            PlaylistCover(list.tracks, 148.dp, 20.dp)
                            Text(list.playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                            Text(songCount(list.tracks.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }

        // Listening as one big number, with a shape for character; Duplicates beside it only when needed.
        item(key = "cards") {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(onClick = onStats, shape = RoundedCornerShape(32.dp), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, modifier = Modifier.weight(1f).height(150.dp + 100.dp * (textScale - 1f)).squish(0.03f)) {
                    Box {
                        Box(Modifier.align(Alignment.TopEnd).offset(x = 24.dp, y = (-24).dp).size(120.dp)
                            .clip(androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape()).background(cs.onTertiaryContainer.copy(alpha = 0.08f)))
                        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                            if (listened.plays == 0) {
                                Text("Your listening", style = MaterialTheme.typography.headlineSmallEmphasized)
                                Text("Plays and time show up as you listen", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(if (listened.listenedMs >= 3_600_000) "${listened.listenedMs / 3_600_000} h" else "${listened.listenedMs / 60_000} min",
                                    style = MaterialTheme.typography.displaySmallEmphasized)
                                Text("listened · ${listened.plays} plays", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (duplicates > 0) Surface(onClick = onDuplicates, shape = RoundedCornerShape(32.dp), color = cs.surfaceContainerHigh, modifier = Modifier.weight(0.75f).height(150.dp + 100.dp * (textScale - 1f))) {
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
                        KickIconButton(onClick = { onTrackActions(track) }, modifier = Modifier.semantics { contentDescription = "Actions for ${track.title}" }) { k -> Symbol(Glyph.MORE, color = cs.onSurfaceVariant, kick = k) }
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
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp), color = container, contentColor = content, modifier = modifier.fillMaxWidth().squish()) {
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
        Box(Modifier.fillMaxSize().squish(0.03f).maskClip(RoundedCornerShape(28.dp)).clickable(onClickLabel = "Open ${album.name}") { onOpen(album) }) {
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
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + LocalPlayerInset.current)) {
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
                            else KickIconButton(onClick = { onTrash(listOf(track)) }, modifier = Modifier.semantics { contentDescription = "Move this copy to trash" }) { k -> Symbol(Glyph.DELETE, kick = k) }
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
/**
 * Songs in columns of four, swiped sideways a column at a time, the next column peeking in:
 * many songs in little height, the way YouTube Music opens. A tap plays the shelf from that song.
 */
@Composable
internal fun QuickPicks(songs: List<Track>, currentId: String?, onPlay: (List<Track>, Int) -> Unit, onTrackActions: (Track) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val columns = songs.chunked(4)
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val width = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() } * 0.86f
    LazyRow(state = state, flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        items(columns.size) { c ->
            Column(Modifier.width(width)) {
                columns[c].forEachIndexed { r, track ->
                    val index = c * 4 + r
                    // Tap plays, a long press opens the song's actions (as YouTube Music's quick picks): no
                    // menu button on every row, so the shelf stays calm.
                    val haptics = rememberHaptics()
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onLongClickLabel = "Song actions", onLongClick = { haptics.longPress(); onTrackActions(track) }) { onPlay(songs, index) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(track.artworkUri, Modifier.size(52.dp), 12.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (track.id.value == currentId) cs.primary else cs.onSurface)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String, onSeeAll: (() -> Unit)? = null) {
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

/**
 * Your listening, with room to breathe: one hero number, three small facts, the library's sound as a
 * coloured bar, then the artists (in M3E shapes) and songs you play most. Numbers are large and few;
 * the rest is shape and colour from the wallpaper.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatsPage(library: LibraryIndex, stats: Map<String, SongStats>, modifier: Modifier, onPlay: (List<Track>, Int) -> Unit) {
    val s = rememberOffMain(library, stats, initial = null) { LibraryStats.of(library) { stats[it.statsKey] } } ?: LibraryStats.of(LibraryIndex.EMPTY) { null }
    val cs = MaterialTheme.colorScheme
    fun hours(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000} h ${ms / 60_000 % 60} min" else "${ms / 60_000} min"
    val byName = rememberOffMain(library, initial = emptyMap()) { library.artists.associateBy { it.name } }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalPlayerInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape = RoundedCornerShape(36.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.fillMaxWidth().height(190.dp)) {
                Box {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-30).dp).size(190.dp)
                        .clip(androidx.compose.material3.MaterialShapes.Cookie12Sided.toShape()).background(cs.onPrimaryContainer.copy(alpha = 0.07f)))
                    Box(Modifier.align(Alignment.TopEnd).padding(20.dp).size(52.dp).clip(androidx.compose.material3.MaterialShapes.Sunny.toShape()).background(cs.primary),
                        contentAlignment = Alignment.Center) { Symbol(Glyph.HEADPHONES, color = cs.onPrimary, filled = true) }
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                        Text("You've listened", style = MaterialTheme.typography.titleMedium)
                        Text(hours(s.listenedMs), style = MaterialTheme.typography.displayMediumEmphasized)
                        Text("${s.plays} plays", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Fact(s.songs.toString(), "songs", Glyph.NOTE, androidx.compose.material3.MaterialShapes.Cookie6Sided.toShape(), cs.secondaryContainer, cs.onSecondaryContainer, Modifier.weight(1f))
                Fact(s.albums.toString(), "albums", Glyph.ALBUM, androidx.compose.material3.MaterialShapes.Circle.toShape(), cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.weight(1f))
                Fact(s.artists.toString(), "artists", Glyph.PERSON, androidx.compose.material3.MaterialShapes.Clover4Leaf.toShape(), cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f))
            }
        }
        // The library's sound: one bar split by format, lossless kinds in the accent families.
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("How your library sounds", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text("${hours(s.totalMs)} of music · ${s.lossless} lossless · ${s.hiRes} hi-res", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    val colors = listOf(cs.primary, cs.tertiary, cs.secondary, cs.primaryContainer, cs.tertiaryContainer, cs.outline)
                    val total = s.formats.sumOf { it.second }.coerceAtLeast(1)
                    // Formats under 1 % would read "0%" and leave a hairline: they are left out.
                    val formats = s.formats.filter { it.second * 100 / total >= 1 }.take(colors.size)
                    Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        formats.forEachIndexed { i, (_, n) ->
                            Box(Modifier.weight(n.toFloat() / total).fillMaxHeight().clip(RoundedCornerShape(
                                topStart = if (i == 0) 10.dp else 3.dp, bottomStart = if (i == 0) 10.dp else 3.dp,
                                topEnd = if (i == formats.lastIndex) 10.dp else 3.dp, bottomEnd = if (i == formats.lastIndex) 10.dp else 3.dp))
                                .background(colors[i]))
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        formats.forEachIndexed { i, (name, n) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors[i]))
                                Text("  $name  ", style = MaterialTheme.typography.labelLarge)
                                Text("${n * 100 / total}%", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (s.topArtists.isNotEmpty()) {
            item { Text("Artists you play most", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
            val shapes = listOf(androidx.compose.material3.MaterialShapes.Cookie9Sided, androidx.compose.material3.MaterialShapes.Clover4Leaf,
                androidx.compose.material3.MaterialShapes.Arch, androidx.compose.material3.MaterialShapes.SoftBurst, androidx.compose.material3.MaterialShapes.Pill)
            items(s.topArtists.size) { i ->
                val (name, plays) = s.topArtists[i]
                val max = s.topArtists.first().second.coerceAtLeast(1)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    ArtistImage(name, Modifier.size(52.dp), 52.dp, shapes[i % shapes.size].toShape())
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.surfaceContainerHighest)) {
                            Box(Modifier.fillMaxWidth(plays.toFloat() / max).fillMaxHeight().clip(androidx.compose.foundation.shape.CircleShape).background(if (i == 0) cs.primary else cs.primary.copy(alpha = 0.6f)))
                        }
                    }
                    Text("$plays", style = MaterialTheme.typography.titleMediumEmphasized, color = cs.primary)
                }
            }
        }
        if (s.topSongs.isNotEmpty()) {
            item { Text("Songs on repeat", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) }
            val queue = s.topSongs.map { it.first }
            items(s.topSongs.size) { i ->
                val (track, plays) = s.topSongs[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPlay(queue, i) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.headlineSmallEmphasized, color = cs.primary, modifier = Modifier.width(36.dp))
                    Artwork(track.artworkUri, Modifier.size(48.dp), 14.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Text("$plays×", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** A small fact: the icon in its own shape, a big number, a word. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Fact(value: String, label: String, glyph: Glyph, shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = container, contentColor = content, modifier = modifier.then(Modifier.heightIn(min = 128.dp))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(36.dp).clip(shape).background(content.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Symbol(glyph, size = 20.dp, filled = true) }
            Column {
                Text(value, style = MaterialTheme.typography.headlineMediumEmphasized)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
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

/** What Home shows, worked out in one pass from the library, history, play counts and favourites. */
internal class HomeData(val newest: List<Track>, val artists: List<MusicCollection>, val recentAlbums: List<MusicCollection>, val addedAlbums: List<MusicCollection>,
    val mostPlayed: List<Track>, val listened: LibraryStats, val quickPicks: List<Track>) {
    companion object { val EMPTY = HomeData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryStats.of(LibraryIndex.EMPTY) { null }, emptyList()) }
}

/** Keeps the current library and account snapshot ready while another tab is open. */
internal class HomeCache { internal var last: HomeData? = null }

private fun homeData(library: LibraryIndex, history: List<Track>, stats: Map<String, SongStats>, favourites: List<Track>, day: Long): HomeData {
    val newest = library.songs.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(100)
    val artists = library.artists.filter { it.name.isNotBlank() && !it.name.equals("Unknown artist", true) }
        .map { artist -> artist to artist.tracks.sumOf { stats[it.statsKey]?.plays ?: 0 } }
        .sortedWith(compareByDescending<Pair<MusicCollection, Int>> { it.second }.thenByDescending { it.first.tracks.size })
        .take(15).map { it.first }
    val albumOf = HashMap<ua.olex.music.model.TrackId, MusicCollection>().apply { library.albums.forEach { a -> a.tracks.forEach { put(it.id, a) } } }
    val recentAlbums = history.mapNotNull { albumOf[it.id] }.distinctBy { it.id }.take(12)
    val addedAlbums = library.albums.mapNotNull { album ->
        album.tracks.asSequence().mapNotNull { it.addedAt }.maxOrNull()?.let { album to it }
    }.sortedByDescending { it.second }.take(12).map { it.first }
    val mostPlayed = library.songs.map { it to (stats[it.statsKey]?.plays ?: 0) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
    val listened = LibraryStats.of(library) { stats[it.statsKey] }
    // Quick picks (YouTube Music's opening shelf): today's mix of what the owner plays and likes,
    // with a few unplayed songs by the artists they play; stable for the day. Liked songs are
    // compared by id: hashing whole tracks (every tag) for each of thousands of songs was the slow part.
    val random = java.util.Random(day)
    val liked = favourites.mapTo(HashSet()) { it.id }
    val played = library.songs.filter { (stats[it.statsKey]?.plays ?: 0) > 0 || it.id in liked }
        .sortedByDescending { (stats[it.statsKey]?.plays ?: 0) + if (it.id in liked) 5 else 0 }.take(60).shuffled(random).take(14)
    val topArtists = artists.take(6).map { it.name }.toSet()
    val fresh = library.songs.filter { it.artist in topArtists && (stats[it.statsKey]?.plays ?: 0) == 0 }.shuffled(random).take(6)
    val quickPicks = (played + fresh).distinctBy { it.id }.ifEmpty { library.songs.shuffled(random).take(20) }.shuffled(random).take(20)
    return HomeData(newest, artists, recentAlbums, addedAlbums, mostPlayed, listened, quickPicks)
}

/** Home before the library is read: mood chips, the bento and song rows as quiet tonal shapes. */
@Composable
internal fun HomeSkeleton(modifier: Modifier) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val pulse = rememberLoadingPulse()
    Column(modifier.fillMaxSize().graphicsLayer { alpha = pulse() }.semantics { contentDescription = "Loading your library" }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { Box(Modifier.width(104.dp).height(40.dp).clip(RoundedCornerShape(50)).background(tone)) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(264.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1.45f).fillMaxHeight().clip(RoundedCornerShape(32.dp)).background(tone))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(tone))
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(tone))
            }
        }
        Spacer(Modifier.height(12.dp))
        repeat(4) { PlaceholderRow() }
    }
}
