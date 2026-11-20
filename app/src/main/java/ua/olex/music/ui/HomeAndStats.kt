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
import ua.olex.music.model.SongStats
import ua.olex.music.model.Track

/** Both sides of a filter transition retain the exact recommendations and queue that were drawn. */
private class HomeFeed(
    val mood: YouTubeMusic.Mood?, val index: Int, val songs: List<Track>, val loading: Boolean,
    val mixes: List<YouTubeMusic.Card>, val albums: List<YouTubeMusic.Card>, val provider: List<YouTubeMusic.Shelf>,
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
    homeActive: Boolean = true,
    onOpenAlbum: (ua.olex.music.model.MusicCollection) -> Unit = {}, onShuffle: (List<Track>) -> Unit = {},
    onAllowFiles: (() -> Unit)? = null, scanError: String? = null, onScanRetry: () -> Unit = {},
    cache: HomeCache = remember { HomeCache() },
) {
    val playTrackMix = LocalOnlineNav.current.playTrackMix
    fun playPick(tracks: List<Track>, index: Int, title: String) {
        playTrackMix?.invoke(tracks[index]) ?: onPlay(tracks, index, title)
    }
    var label by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val mood = moods.firstOrNull { it.label == label }
    androidx.activity.compose.BackHandler(homeActive && mood != null) { label = null }
    var choosingLiked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val network = LocalNetwork.current
    val online = onlineEnabled && network
    val revision = if (!online) ua.olex.music.playback.CacheBudget.revision.collectAsStateWithLifecycle().value else 0L
    val day = java.time.LocalDate.now().toEpochDay()
    val moodView = if (mood != null) key(cache, mood, online) { rememberMoodView(mood, online) } else null
    // A visible shelf is a listening session. Re-rank on entry or filter changes, and allow
    // initially empty history and stats to arrive after Room opens.
    val seeds = remember(cache, day, mood, history.isEmpty()) { history }
    val weights = remember(cache, day, mood, stats.isEmpty()) { stats }
    val queue = key(cache, online, mood?.label) {
        produceState(if (mood == null && cache.online == online && cache.day == day) cache.last else null,
            library, seeds, weights, favourites, youTubeHome, excluded, day, revision, moodView?.page) {
            val previous = cache.last.orEmpty().takeIf { mood == null && cache.online == online && cache.day == day }.orEmpty()
            val picks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val retained = (seeds + favourites).filter { YouTubeMusic.isOnline(it) }
                val catalogue = library.songs + retained
                val candidates = if (mood == null) catalogue else Mix.moodSongs(mood.label, catalogue)
                val suggested = if (mood != null) moodView?.page?.songs.orEmpty() else youTubeHome.flatMap { shelf ->
                    shelf.songs + shelf.cards.filter { it.kind == YouTubeMusic.Card.Kind.SONG }.map { it.song() }
                }
                fun playable(track: Track) = online || !YouTubeMusic.isOnline(track) ||
                    ua.olex.music.playback.CacheBudget.isStreamCached(track.uri)
                fun hidden(track: Track) = excluded.isNotEmpty() && (track.id.value in excluded || Mix.songKey(track) in excluded)
                val ranked = ua.olex.music.model.HomePicks.select(candidates, suggested, seeds, favourites, weights,
                    day + (mood?.label?.hashCode() ?: 0), ::playable, ::hidden)
                val byId = (suggested + catalogue).associateBy { it.id }
                val kept = previous.mapNotNull { byId[it.id] }.filter { playable(it) && !hidden(it) }
                (kept + ranked).distinctBy { it.recordingId ?: it.id.value }.take(24)
            }
            value = picks
            if (mood == null) { cache.online = online; cache.day = day; cache.last = picks }
        }.value
    }
    val cacheRevision = ua.olex.music.playback.CacheBudget.revision.collectAsStateWithLifecycle().value
    val cachedExtras = rememberOffMain(history, favourites, youTubeHome, cacheRevision, initial = emptyList<Track>()) {
        val candidates = history + favourites + youTubeHome.flatMap { shelf ->
            shelf.songs + shelf.cards.filter { it.kind == YouTubeMusic.Card.Kind.SONG }.map { it.song() }
        }
        candidates.filter { YouTubeMusic.isOnline(it) && ua.olex.music.playback.CacheBudget.isStreamCached(it.uri) }
            .distinctBy { it.recordingId ?: it.id.value }
    }
    val shuffleTracks = rememberOffMain(library, cachedExtras, initial = library.songs) {
        (library.songs + cachedExtras).distinctBy { it.recordingId ?: it.id.value }
    }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val songs = queue.orEmpty()
    val provider = remember(youTubeHome, mood, moodView?.page, online) {
        if (!online) emptyList() else if (mood == null) youTubeHome else moodView?.page?.shelves.orEmpty()
    }
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
    // Freeze the local selection for this catalogue. Skipping songs must not rebuild the album shelf.
    val localAlbums = key(cache) {
        produceState(cache.albums.orEmpty(), library) {
            val previous = cache.albums.orEmpty()
            val initialTrack = currentId
            val picks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val all = library.albums.associateBy { it.id }
                val retained = previous.mapNotNull { all[it.id] }
                if (retained.size >= 6) retained else {
                    val recent = history.take(40).mapTo(HashSet()) { it.artist }
                    val ranked = library.albums.asSequence().filter { album -> album.tracks.any { !it.album.isNullOrBlank() } }
                        .sortedByDescending { album ->
                            (if (album.tracks.any { it.id.value == initialTrack }) 0 else 4) +
                                (if (album.tracks.size > 1) 2 else 0) + (if (album.subtitle in recent) 1 else 0)
                        }
                    (retained.asSequence() + ranked).distinctBy { it.id }.take(6).toList()
                }
            }
            cache.albums = picks
            value = picks
        }.value
    }
    val albumPicks = remember(localAlbums, albums, online) {
        val picks = buildList<HomeAlbumPick> {
            for (i in 0 until 6) {
                localAlbums.getOrNull(i)?.let { add(HomeAlbumPick(it.id, it.name, it.subtitle, it.artworkUri, it)) }
                if (online) albums.getOrNull(i)?.let { add(HomeAlbumPick(it.id, it.title, it.subtitle, it.cover)) }
            }
        }
        picks.distinctBy { it.title.lowercase() to it.artist.orEmpty().lowercase() }.take(6)
    }
    val loading = queue == null || moodView?.loading == true
    val moodIndex = mood?.let { moods.indexOf(it) + 1 } ?: 0
    val nextFeed = remember(mood, moodIndex, songs, loading, mixes, albums, provider) {
        HomeFeed(mood, moodIndex, songs, loading, mixes, albums, provider)
    }
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
                LazyColumn(Modifier.fillMaxSize().then(actionable).background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(bottom = 12.dp + LocalPlayerInset.current)) {
                    val contextName = shownMood?.label ?: "Quick picks"
                    if (shownMood == null && (albumPicks.isNotEmpty() || shuffleTracks.isNotEmpty() || favourites.isNotEmpty() || shownSongs.isNotEmpty())) item(key = "starts") {
                        HomeStarts(albumPicks, shuffleTracks.size, favourites, onOpenAlbum,
                            onShuffle = { onShuffle(shuffleTracks) }, onLiked = { onPlay(favourites, 0, "Liked") },
                            onChooseLiked = { choosingLiked = true })
                    }
                    if (frame.provider.isNotEmpty()) {
                        frame.provider.forEachIndexed { index, shelf ->
                            val serverSongs = (shelf.songs + if (shelf.cards.all { it.kind == YouTubeMusic.Card.Kind.SONG })
                                shelf.cards.map { it.song() } else emptyList())
                                .distinctBy { it.id }.filter { it.id.value !in excluded && Mix.songKey(it) !in excluded }
                            val cards = shelf.cards.filter { it.kind != YouTubeMusic.Card.Kind.SONG || it.song().let { song ->
                                song.id.value !in excluded && Mix.songKey(song) !in excluded
                            } }
                            if (serverSongs.isNotEmpty()) item(key = "provider-songs-$index") {
                                QuickPicks(shelf.title, serverSongs, currentId,
                                    { _, i -> playPick(serverSongs, i, shelf.title) }, onTrackActions)
                            }
                            if (shelf.cards.any { it.kind != YouTubeMusic.Card.Kind.SONG } && cards.isNotEmpty()) {
                                item(key = "provider-cards-$index") {
                                    if (serverSongs.isEmpty()) SectionTitle(shelf.title)
                                    YouTubeCards(cards)
                                }
                            }
                        }
                    } else if (shownSongs.isNotEmpty() && (shownMood == null || !frame.loading)) {
                        if (shownMood != null) item(key = "title") { SectionTitle(contextName) }
                        if (shownMood != null) item(key = "spread") {
                            CoverSpread(shownSongs, currentId, { playPick(shownSongs, it, contextName) }, onTrackActions)
                        }
                        val start = if (shownMood == null) 0 else minOf(3, shownSongs.size)
                        if (start < shownSongs.size) item(key = "picks") {
                            QuickPicks(if (shownMood == null) contextName else "More picks", remember(shownSongs, start) { if (start == 0) shownSongs else shownSongs.drop(start) }, currentId, { _, i -> playPick(shownSongs, i + start, contextName) }, onTrackActions)
                        }
                    } else if (frame.loading) {
                        item(key = "loading") { PlaceholderRow() }
                    } else if (!hasPersonal && frame.mixes.isEmpty() && frame.albums.isEmpty()) {
                        item(key = "empty") {
                            if (shownMood != null) EmptyState(moodGlyph(shownMood.label), "No songs for this mood", "Pick another mood to keep listening.")
                            else if (onAllowFiles == null) EmptyState(Glyph.NOTE, "No quick picks yet", "Add music to your library to start listening.")
                        }
                    }
                    if (frame.provider.isEmpty() && frame.mixes.isNotEmpty()) {
                        item(key = "mixes-title") { SectionTitle(if (shownMood != null) "Mood mixes" else "Made for you") }
                        item(key = "mixes") { MixRail(frame.mixes) }
                    }
                    if (hasPersonal) {
                        item(key = "my-title") { SectionTitle("Your playlists", onAllPlaylists) }
                        item(key = "my-playlists") { PersonalPlaylistRail(playlists, accountPlaylists, onOpenPlaylist) }
                    }
                    if (frame.provider.isEmpty() && frame.albums.isNotEmpty()) {
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
    if (choosingLiked && homeActive) AppSheet(onDismiss = { choosingLiked = false }) {
        SectionTitle("Liked")
        if (favourites.isEmpty()) Text("No liked songs yet", Modifier.padding(16.dp))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("liked-picker"), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(favourites.size) { index ->
                val song = favourites[index]
                TrackRow(song, song.id.value == currentId, showQuality = false) {
                    choosingLiked = false
                    onPlay(favourites, index, "Liked")
                }
            }
        }
    }
}

/** Retains the personal queue across tab changes within the same catalogue and account. */
internal class HomeCache {
    var online = false
    var day = Long.MIN_VALUE
    var last: List<Track>? = null
    var albums: List<ua.olex.music.model.MusicCollection>? = null
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
