@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.LibraryStats
import ua.olex.music.model.SongStats
import ua.olex.music.model.Track

/** Both sides of a filter transition retain the exact recommendations and queue that were drawn. */
private data class HomeFeed(
    val mood: YouTubeMusic.Mood?, val index: Int, val songs: List<Track>, val loading: Boolean,
    val mixes: List<YouTubeMusic.Card>, val albums: List<YouTubeMusic.Card>,
) {
    val key: String get() = mood?.let { "mood-${it.label}" } ?: "picks"
}

/** The mood strip stays anchored while the current collection of music changes beneath it. */
@Composable
internal fun HomePage(
    library: LibraryIndex, history: List<Track>, stats: Map<String, SongStats>, currentId: String?, modifier: Modifier,
    onPlay: (List<Track>, Int, String) -> Unit, onTrackActions: (Track) -> Unit,
    favourites: List<Track> = emptyList(), moods: List<YouTubeMusic.Mood> = YouTubeMusic.DEFAULT_MOODS,
    youTubeHome: List<YouTubeMusic.Shelf> = emptyList(), excluded: Set<String> = emptySet(), onlineEnabled: Boolean = true,
    playlists: List<PlaylistUi> = emptyList(), accountPlaylists: List<YouTubeMusic.Card> = emptyList(),
    onOpenPlaylist: (Long) -> Unit = {}, onAllPlaylists: () -> Unit = {},
    transportPlaying: Boolean = false, onToggle: () -> Unit = {},
    onOpenAlbum: (ua.olex.music.model.MusicCollection) -> Unit = {}, onShuffle: (List<Track>) -> Unit = {},
    onAllowFiles: (() -> Unit)? = null, scanError: String? = null, onScanRetry: () -> Unit = {},
    cache: HomeCache = remember(library) { HomeCache() },
) {
    var label by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val mood = moods.firstOrNull { it.label == label }
    androidx.activity.compose.BackHandler(mood != null) { label = null }
    val network = LocalNetwork.current
    val online = onlineEnabled && network
    val revision = if (!online) ua.olex.music.playback.CacheBudget.revision.collectAsStateWithLifecycle().value else 0L
    val day = java.time.LocalDate.now().toEpochDay()
    val moodView = if (mood != null) key(cache, mood, online) { rememberMoodView(mood, online) } else null
    val queue = key(cache, online, mood?.label) {
        produceState(if (mood == null && cache.online == online) cache.last else null,
            library, history, stats, favourites, youTubeHome, excluded, day, revision, moodView?.page) {
            val picks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val retained = (history + favourites).filter { YouTubeMusic.isOnline(it) }
                val catalogue = library.songs + retained
                val candidates = if (mood == null) catalogue else Mix.moodSongs(mood.label, catalogue)
                val suggested = if (mood != null) moodView?.page?.songs.orEmpty() else youTubeHome.flatMap { shelf ->
                    shelf.songs + shelf.cards.filter { it.kind == YouTubeMusic.Card.Kind.SONG }.map { it.song() }
                }
                ua.olex.music.model.HomePicks.select(candidates, suggested, history, favourites, stats,
                    day + (mood?.label?.hashCode() ?: 0), { t ->
                        online || !t.uri.startsWith("ytm://") || ua.olex.music.playback.CacheBudget.isStreamCached(t.uri)
                    }, { t -> excluded.isNotEmpty() && (t.id.value in excluded || Mix.songKey(t) in excluded) })
            }
            value = picks
            if (mood == null) { cache.online = online; cache.last = picks }
        }.value
    }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val songs = queue.orEmpty()
    val mixes = remember(youTubeHome, accountPlaylists, mood, moodView?.page, online) {
        if (mood != null) moodView?.page?.playlists.orEmpty().map { YouTubeMusic.Card(it.id, it.title, it.subtitle, it.cover, YouTubeMusic.Card.Kind.PLAYLIST) }
        else {
            val owned = accountPlaylists.mapTo(HashSet()) { it.id.removePrefix("VL") }
            youTubeHome.asSequence().flatMap { it.cards.asSequence() }
                .filter { (it.kind == YouTubeMusic.Card.Kind.PLAYLIST || (online && it.kind == YouTubeMusic.Card.Kind.MIX)) && it.id.removePrefix("VL") !in owned }
                .distinctBy { it.id.removePrefix("VL") }.toList()
        }
    }
    val albums = remember(youTubeHome, mood) {
        if (mood != null) emptyList() else youTubeHome.flatMap { it.cards }.filter { it.kind == YouTubeMusic.Card.Kind.ALBUM }.distinctBy { it.id }
    }
    val feature = key(cache) {
        rememberOffMain<ua.olex.music.model.MusicCollection?>(library, history, initial = null) {
            val recent = history.mapIndexed { i, track -> track.id to i }.toMap()
            val named = library.albums.filter { album -> album.tracks.any { !it.album.isNullOrBlank() } }
            named.minByOrNull { album -> album.tracks.minOfOrNull { recent[it.id] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }
        }
    }
    val nextFeed = HomeFeed(mood, mood?.let { moods.indexOf(it) + 1 } ?: 0, songs, queue == null || moodView?.loading == true, mixes, albums)
    var ready by remember(cache, online) { mutableStateOf<HomeFeed?>(null) }
    if (queue != null) SideEffect { ready = nextFeed }
    // Keep the last frame until the local ranking finishes. Network sections may arrive later.
    val target = if (queue == null && ready?.key != nextFeed.key) ready ?: nextFeed else nextFeed
    val still = LocalPowerSave.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    Column(modifier.fillMaxSize()) {
        if (moods.isNotEmpty()) MoodBar(moods, mood?.label, onSelect = { label = it })
        AnimatedContent(targetState = target, modifier = Modifier.fillMaxSize(), contentKey = { it.key },
            transitionSpec = {
                if (still) EnterTransition.None togetherWith ExitTransition.None else {
                    val direction = targetState.index.compareTo(initialState.index) * if (rtl) -1 else 1
                    val enter = fadeIn(effects, initialAlpha = 0.78f) + slideInHorizontally(spatial) { direction * it / 14 }
                    val exit = fadeOut(effects) + slideOutHorizontally(spatial) { -direction * it / 24 }
                    enter togetherWith exit
                }
            }, label = "Mood feed") { frame ->
            val shownMood = frame.mood
            val shownSongs = frame.songs
            val hasPersonal = shownMood == null && (playlists.isNotEmpty() || accountPlaylists.isNotEmpty())
            val actionable = if (frame.key == nextFeed.key) Modifier.testTag("home-feed") else Modifier
                .clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            stateHolder.SaveableStateProvider(frame.key) {
                // Keep Home's scroll-to targets inside the touchable viewport above the floating player.
                LazyColumn(Modifier.fillMaxSize().padding(bottom = LocalPlayerInset.current).then(actionable).background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(bottom = 12.dp)) {
                    val contextName = shownMood?.label ?: "Quick picks"
                    if (shownMood == null && (feature != null || shownSongs.isNotEmpty())) item(key = "starts") {
                        val shuffleSongs = library.songs.ifEmpty { shownSongs }
                        fun startFeature() {
                            if (feature != null) onPlay(feature.tracks, 0, feature.name)
                            else if (shownSongs.isNotEmpty()) onPlay(shownSongs, 0, contextName)
                        }
                        HomeStarts(feature, shownSongs.firstOrNull(), shuffleSongs, favourites, currentId, transportPlaying, onToggle,
                            onAlbum = { if (feature != null) onOpenAlbum(feature) else startFeature() },
                            onPlayAlbum = ::startFeature, onShuffle = { onShuffle(shuffleSongs) }, onLiked = { onPlay(favourites, 0, "Liked") })
                    }
                    if (shownSongs.isNotEmpty()) {
                        if (shownMood != null) item(key = "title") { SectionTitle(contextName) }
                        if (shownMood != null) item(key = "spread") {
                            CoverSpread(shownSongs, currentId, { onPlay(shownSongs, it, contextName) }, onTrackActions)
                        }
                        val start = if (shownMood == null) 0 else minOf(3, shownSongs.size)
                        if (start < shownSongs.size) item(key = "picks") {
                            QuickPicks(if (shownMood == null) contextName else "More picks", shownSongs.drop(start), currentId, { _, i -> onPlay(shownSongs, i + start, contextName) }, onTrackActions)
                        }
                    } else if (frame.loading) {
                        item(key = "loading") { PlaceholderRow() }
                    } else if (!hasPersonal && frame.mixes.isEmpty() && frame.albums.isEmpty()) {
                        item(key = "empty") {
                            if (shownMood != null) EmptyState(moodGlyph(shownMood.label), "No songs for this mood", "Pick another mood to keep listening.")
                            else if (onAllowFiles == null) EmptyState(Glyph.NOTE, "No quick picks yet", "Add music to your library to start listening.")
                        }
                    }
                    if (frame.mixes.isNotEmpty()) {
                        item(key = "mixes-title") { SectionTitle(if (shownMood != null) "Mood mixes" else "Made for you") }
                        item(key = "mixes") { MixRail(frame.mixes) }
                    }
                    if (hasPersonal) {
                        item(key = "my-title") { SectionTitle("Your playlists", onAllPlaylists) }
                        item(key = "my-playlists") { PersonalPlaylistRail(playlists, accountPlaylists, onOpenPlaylist) }
                    }
                    if (frame.albums.isNotEmpty()) {
                        item(key = "album-title") { SectionTitle("Albums for you") }
                        item(key = "albums") { YouTubeCards(frame.albums, size = 132.dp) }
                    }
                    if (scanError != null) item(key = "scan-error") { ErrorNotice(scanError, onScanRetry) }
                    if (onAllowFiles != null && shownMood == null) item(key = "permission") {
                        TextButton(onClick = onAllowFiles, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Include music on this device") }
                    }
                }
            }
        }

    }
}

/** Retains the personal queue across tab changes within the same catalogue and account. */
internal class HomeCache {
    var online = false
    var last: List<Track>? = null
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

@Composable
internal fun SectionTitle(text: String, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp).clip(RoundedCornerShape(16.dp))
        .then(if (onSeeAll != null) Modifier.clickable(onClickLabel = "See all $text", onClick = onSeeAll) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
        if (onSeeAll != null) Symbol(Glyph.CHEVRON, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
internal fun HomeSkeleton(modifier: Modifier) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    val pulse = rememberLoadingPulse()
    Column(modifier.fillMaxSize().graphicsLayer { alpha = pulse() }.semantics { contentDescription = "Loading your library" }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.width(168.dp).height(36.dp).clip(RoundedCornerShape(16.dp)).background(tone))
            Box(Modifier.width(88.dp).height(36.dp).clip(RoundedCornerShape(50)).background(tone))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(1.2f).clip(RoundedCornerShape(28.dp)),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1.8f).fillMaxHeight().background(tone))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { Box(Modifier.weight(1f).fillMaxWidth().background(tone)) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Box(Modifier.size(146.dp).clip(RoundedCornerShape(18.dp)).background(tone)) }
        }
    }
}
