@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.MusicCollection
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track

/** A YouTube Music page to show over the current place: an artist (by name or id) or an album. */
internal sealed interface OnlineTarget {
    /** An artist; [full] opens the YouTube Music page even when the library has them ("See all"). */
    data class Artist(val name: String, val id: String? = null, val full: Boolean = false) : OnlineTarget
    /** An album, or a playlist when [playlist] (no album download for a playlist of many artists). */
    data class Album(val id: String, val title: String, val playlist: Boolean = false, val cover: String? = null, val subtitle: String? = null) : OnlineTarget
    data class Mood(val mood: YouTubeMusic.Mood) : OnlineTarget
}

/**
 * The library's own page for an online target, when there is one: an artist the owner has opens his
 * artist page (which carries the YouTube Music sections too), so an artist is one page wherever it
 * is opened from. Albums stay online pages: they list every song, the owner's files marked in place.
 */
internal fun libraryTwin(target: OnlineTarget, library: LibraryIndex): Pair<String, String>? = when (target) {
    is OnlineTarget.Artist -> if (target.full) null else TextFold.fold(target.name).let { name -> library.artists.firstOrNull { TextFold.fold(it.name) == name } }?.let { "ARTISTS" to it.id }
    else -> null
}

/** What online pages can do in the app: open another page, play, show a song's actions, open the owner's own artist. */
internal class OnlineNav(
    val open: (OnlineTarget) -> Unit = {},
    /** Plays a list; the last argument names it for the player's "Playing from". */
    val play: (List<Track>, Int, String?) -> Unit = { _, _, _ -> },
    val actions: (Track) -> Unit = {},
    val openLocalArtist: (MusicCollection) -> Unit = {},
    /** Starts a Soulseek download, asking for the account first when there is none. */
    val download: (() -> Unit) -> Unit = { it() },
    /** Plays a mood (its playlist, or a mix of the owner's songs of that mood). */
    val playMood: (YouTubeMusic.Mood, YouTubeMusic.MoodPage?) -> Unit = { _, _ -> },
    /** Plays a YouTube Music mix ("My Mix 1") by its playlist id, endless like a mix. */
    val playMix: (String, String) -> Unit = { _, _ -> },
)

internal val LocalOnlineNav = staticCompositionLocalOf { OnlineNav() }

/**
 * Songs the owner has as files, shown (and played) as those files: quality tag and all. Looked up in
 * the library's index (built off the main thread by LibraryViewModel), once per list and library;
 * when the index is not ready yet it is built on a background thread and the list shows as it came.
 */
@Composable
internal fun rememberOwned(songs: List<Track>, library: LibraryIndex): List<Track> {
    val ready = remember(songs, library) { YouTubeMusic.builtIndex(library)?.let { index -> songs.map { index.copyOf(it) ?: it } } }
    return produceState(ready ?: songs, songs, library) {
        value = ready ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { YouTubeMusic.indexOf(library).let { index -> songs.map { index.copyOf(it) ?: it } } }
    }.value
}

private sealed interface Load<out T> { data object Wait : Load<Nothing>; data object Failed : Load<Nothing>; data class Done<T>(val value: T) : Load<T> }

/** Loads a page; one opened before this session is there in the first frame ([cached]). */
@Composable
private fun <T> rememberLoad(key: Any, retry: Int, cached: T? = null, load: suspend () -> T?): Load<T> =
    produceState<Load<T>>(cached?.let { Load.Done(it) } ?: Load.Wait, key, retry) {
        if (value is Load.Done && retry == 0) return@produceState
        value = Load.Wait; value = runCatching { load() }.getOrNull()?.let { Load.Done(it) } ?: Load.Failed
    }.value

/** Tells the shell when the page's header has scrolled away, so the top bar takes the title. */
@Composable
private fun ReportTitle(list: androidx.compose.foundation.lazy.LazyListState, onTitleShown: (Boolean) -> Unit) {
    val report by rememberUpdatedState(onTitleShown)
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex > 0 }.collect { report(it) } }
}

/** Starts finding the stream of the first online song while the page is read, so Play sounds sooner. */
@Composable
private fun WarmFirst(songs: List<Track>) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val first = songs.firstOrNull()?.takeIf(YouTubeMusic::isOnline)?.id?.value?.removePrefix("yt:")
    LaunchedEffect(first) { if (first != null) runCatching { graph.youTube.warm(first) } }
}

/** A song row's place while the list loads: the same height as a row, so nothing jumps when it fills. */
@Composable
internal fun PlaceholderRow(cover: Boolean = true) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (cover) 52.dp else 28.dp).clip(RoundedCornerShape(if (cover) 12.dp else 8.dp)).background(tone))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(180.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(tone))
            Box(Modifier.width(110.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = 0.7f)))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.placeholders(cover: Boolean) =
    items(8, key = { "p-$it" }) { PlaceholderRow(cover) }

/**
 * An artist from YouTube Music, joined to the owner's library: their photo and a few lines, the
 * songs people play most (the owner's own files where they have them), albums that open in place,
 * and videos; when the library has the artist too, one tap leads there.
 */
@Composable
internal fun OnlineArtistPage(target: OnlineTarget.Artist, library: LibraryIndex, currentId: String?, insets: PaddingValues, onTitleShown: (Boolean) -> Unit = {}) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val nav = LocalOnlineNav.current
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberLoad(target, retry, target.id?.let(graph.youTube::peekArtist)) {
        val id = target.id ?: graph.youTube.artistId(target.name) ?: return@rememberLoad null
        graph.youTube.artist(id)
    }
    val mine = remember(library, target.name) { library.artists.firstOrNull { TextFold.fold(it.name) == TextFold.fold(target.name) } }
    val cs = MaterialTheme.colorScheme
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportTitle(list, onTitleShown)
    val songs = rememberOwned((state as? Load.Done)?.value?.songs.orEmpty(), library)
    WarmFirst(songs)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        when (state) {
            Load.Wait -> {
                item(key = "head") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(null, Modifier.size(120.dp), 60.dp)
                        Text(target.name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp).weight(1f))
                    }
                }
                placeholders(cover = true)
            }
            Load.Failed -> item {
                OnlineFailed(target.name, Glyph.SEARCH) { retry++ }
            }
            is Load.Done -> {
                val artist = state.value
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(artist.photo, Modifier.size(120.dp), 60.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
                            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                                Text(artist.name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(artist.listeners ?: "YouTube Music", style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val playable = songs.ifEmpty { artist.videos }
                            Button(enabled = playable.isNotEmpty(), onClick = { nav.play(playable, 0, artist.name) }, modifier = Modifier.height(52.dp)) {
                                Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Play")
                            }
                            FilledTonalIconButton(enabled = playable.isNotEmpty(), onClick = { nav.play(playable.shuffled(), 0, artist.name) },
                                modifier = Modifier.size(52.dp).semantics { contentDescription = "Shuffle" }) { Symbol(Glyph.SHUFFLE) }
                            // The owner's own songs by this artist, one tap away.
                            mine?.let { local -> FilledTonalButton(onClick = { nav.openLocalArtist(local) }, modifier = Modifier.height(52.dp)) {
                                Symbol(Glyph.LIBRARY, size = 18.dp); Spacer(Modifier.size(8.dp)); Text("Yours \u00b7 ${local.tracks.size}")
                            } }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                artist.about?.let { about -> item(key = "about") { AboutCard(about) } }
                if (songs.isNotEmpty()) {
                    item(key = "songs-h") { OnlineHeading("Top songs") }
                    itemsIndexed(songs.take(10), key = { _, t -> "s-" + t.id.value }) { i, t ->
                        TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(songs, i, artist.name) }
                    }
                }
                if (artist.albums.isNotEmpty()) {
                    item(key = "albums-h") { OnlineHeading("Albums") }
                    item(key = "albums") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                            items(artist.albums, key = { it.id }) { a -> OnlineCard(a.cover, a.title, a.subtitle) { nav.open(OnlineTarget.Album(a.id, a.title, cover = a.cover, subtitle = a.subtitle)) } }
                        }
                    }
                }
                if (artist.singles.isNotEmpty()) {
                    item(key = "singles-h") { OnlineHeading("Singles and EPs") }
                    item(key = "singles") { YouTubeCards(artist.singles.map { YouTubeMusic.Card(it.id, it.title, it.subtitle, it.cover, YouTubeMusic.Card.Kind.ALBUM) }) }
                }
                if (artist.related.isNotEmpty()) {
                    item(key = "related-h") { OnlineHeading("Fans also like") }
                    item(key = "related") { YouTubeCards(artist.related) }
                }
                if (artist.videos.isNotEmpty()) {
                    item(key = "videos-h") { OnlineHeading("Videos") }
                    item(key = "videos") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                            itemsIndexed(artist.videos, key = { _, v -> v.id.value }) { i, v -> OnlineCard(v.artworkUri, v.title, null) { nav.play(artist.videos, i, artist.name) } }
                        }
                    }
                }
            }
        }
    }
}

/**
 * An album or playlist from YouTube Music: cover, title, Play all and Shuffle, then its songs in
 * order. The header is drawn at once from the card that opened it; the songs fill in under it.
 */
@Composable
internal fun OnlineAlbumPage(target: OnlineTarget.Album, library: LibraryIndex, currentId: String?, insets: PaddingValues, onTitleShown: (Boolean) -> Unit = {}) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val nav = LocalOnlineNav.current
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberLoad(target, retry, graph.youTube.peekAlbum(target.id)) { graph.youTube.album(target.id) }
    // A long playlist arrives in parts: the rest is fetched in the background and added under what is
    // on screen, so nothing moves while the owner reads or plays.
    var grown by remember(target) { mutableStateOf<Pair<YouTubeMusic.OnlineAlbum, List<Track>>?>(null) }
    LaunchedEffect(target, state is Load.Done) {
        if (state !is Load.Done) return@LaunchedEffect
        while (true) { grown = runCatching { graph.youTube.albumMore(target.id) }.getOrNull() ?: break }
    }
    val loaded = grown ?: (state as? Load.Done)?.value
    val songs = rememberOwned(loaded?.second.orEmpty(), library)
    WarmFirst(songs)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportTitle(list, onTitleShown)
    val album = loaded?.first
    val title = album?.title ?: target.title
    val artistName = songs.firstOrNull()?.artist?.substringBefore(",")?.trim().orEmpty()
    // A playlist mixes albums: its rows show covers. An album's rows show track numbers.
    val mixed = target.playlist || songs.mapTo(HashSet()) { it.album }.size > 1
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(album?.cover ?: target.cover, Modifier.size(220.dp), 28.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE)
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                (album?.subtitle ?: target.subtitle)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (!target.playlist && artistName.isNotEmpty()) TextButton(onClick = { nav.open(OnlineTarget.Artist(artistName)) }) { Text(artistName) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = songs.isNotEmpty(), onClick = { nav.play(songs, 0, title) }, modifier = Modifier.height(52.dp)) {
                        Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Play all")
                    }
                    FilledTonalIconButton(enabled = songs.isNotEmpty(), onClick = { nav.play(songs.shuffled(), 0, title) },
                        modifier = Modifier.size(52.dp).semantics { contentDescription = "Shuffle" }) { Symbol(Glyph.SHUFFLE) }
                    // The whole album in lossless from Soulseek, into the library. Nothing to fetch
                    // once every song plays from a file.
                    if (!target.playlist && songs.isNotEmpty()) DownloadButton(ua.olex.music.soulseek.SoulseekDownloads.albumKey(artistName, title),
                        owned = songs.none(YouTubeMusic::isOnline), what = "the album") {
                        nav.download { graph.soulseek.album(artistName, title) }
                    }
                }
                if (!target.playlist && songs.isNotEmpty()) DownloadStatus(ua.olex.music.soulseek.SoulseekDownloads.albumKey(artistName, title))
                // Room between the buttons and the first song, so a playing row's highlight never touches them.
                Spacer(Modifier.height(16.dp))
            }
        }
        when (state) {
            Load.Wait -> placeholders(cover = mixed)
            Load.Failed -> item {
                OnlineFailed(target.title, Glyph.ALBUM) { retry++ }
            }
            is Load.Done -> itemsIndexed(songs, key = { i, t -> "a-$i-" + t.id.value }) { i, t ->
                TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }, number = if (mixed) null else i + 1, albumArtist = if (mixed) null else t.artist) { nav.play(songs, i, title) }
            }
        }
    }
}

/**
 * The YouTube Music half of the owner's artist page (docs/specs/M10_ONLINE.md, D1): their popular
 * songs (the owner's own files where he has them, each with its source mark), the albums and singles
 * the library lacks, and artists like them. One page, two sources; nothing shows while offline.
 */
@Composable
internal fun MoreOnYouTubeMusic(name: String, library: LibraryIndex, currentId: String?) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val on by graph.youTubeOn.collectAsState()
    if (!on) return
    val nav = LocalOnlineNav.current
    val state = rememberLoad(name, 0) { graph.youTube.artistId(name)?.let { graph.youTube.artist(it) } }
    val artist = (state as? Load.Done)?.value
    val popular = rememberOwned(artist?.songs.orEmpty(), library).take(5)
    if (artist == null) return
    // Albums the owner already has open from his library above; only the others are offered here.
    val owned = remember(library) { library.albums.mapTo(HashSet()) { TextFold.fold(it.name) } }
    fun cards(list: List<YouTubeMusic.OnlineAlbum>) = list.filterNot { TextFold.fold(it.title) in owned }
        .map { YouTubeMusic.Card(it.id, it.title, it.subtitle, it.cover, YouTubeMusic.Card.Kind.ALBUM) }
    val albums = cards(artist.albums)
    val singles = cards(artist.singles)
    Column {
        if (popular.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { OnlineHeading("Popular") }
                TextButton(onClick = { nav.open(OnlineTarget.Artist(artist.name, artist.id, full = true)) }) { Text("See all") }
            }
            popular.forEachIndexed { i, t -> TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(popular, i, artist.name) } }
        }
        if (albums.isNotEmpty()) { OnlineHeading("More albums"); YouTubeCards(albums) }
        if (singles.isNotEmpty()) { OnlineHeading("Singles and EPs"); YouTubeCards(singles) }
        if (artist.related.isNotEmpty()) { OnlineHeading("Fans also like"); YouTubeCards(artist.related) }
    }
}

/**
 * An online page that didn't load. Offline it says so plainly and opens by itself once the phone is
 * back online; online it offers to try again.
 */
@Composable
private fun OnlineFailed(name: String, glyph: Glyph, retry: () -> Unit) {
    val network = LocalNetwork.current
    val again by rememberUpdatedState(retry)
    // Only when the connection comes back, not on every failure (that would retry in a loop).
    var wasOffline by remember { mutableStateOf(!network) }
    LaunchedEffect(network) { if (network && wasOffline) again(); wasOffline = !network }
    if (!network) EmptyState(Glyph.CLOUD_OFF, "You're offline", "Your own songs still play. $name opens as soon as the phone is back online.")
    else EmptyState(glyph, "Couldn't open $name", "YouTube Music didn't answer. Try again in a moment.",
        actions = { Button(onClick = retry) { Text("Try again") } })
}

@Composable
private fun OnlineHeading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))

@Composable
private fun AboutCard(text: String) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { open = !open }, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
private fun OnlineCard(cover: String?, title: String, subtitle: String?, onClick: () -> Unit) {
    Column(Modifier.width(156.dp).padding(horizontal = 4.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(4.dp).padding(bottom = 8.dp)) {
        Artwork(cover, Modifier.fillMaxWidth().aspectRatio(1f), 16.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

/**
 * A download button that shows where its download is, in place, as Play Store's install button
 * does: an arrow, a spinner while Soulseek searches, a ring filling with the percent, a tick when
 * the files are in the library, and a retry on the error colour when it failed. Tapping while it
 * runs says what it is doing; a failed one tells why and tries again.
 */
@Composable
internal fun DownloadButton(key: String, owned: Boolean, what: String, onStart: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val jobs by graph.soulseek.jobs.collectAsState()
    val job = jobs.lastOrNull { it.key == key }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val done = owned || job?.done == true
    val failed = job?.failed == true
    val description = when {
        done -> "Already in your library"
        failed -> "Download failed: ${job?.status}. Try again"
        job?.running == true -> job.status
        else -> "Download $what"
    }
    FilledTonalIconButton(onClick = {
        when {
            done -> scope.launch { snackbar?.showSnackbar("Already in your library") }
            job?.running == true -> scope.launch { snackbar?.showSnackbar(job.status) }
            failed -> { graph.soulseek.retry(job!!); scope.launch { snackbar?.showSnackbar("${job.status}. Trying again") } }
            else -> onStart()
        }
    }, colors = if (failed) androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer)
        else androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = Modifier.size(52.dp).semantics { contentDescription = description }) {
        androidx.compose.animation.Crossfade(when { done -> 0; failed -> 1; job?.running == true -> 2; else -> 3 }, label = "download") { stage ->
            when (stage) {
                0 -> Symbol(Glyph.CHECK)
                1 -> Symbol(Glyph.REFRESH)
                // As Play Store: a plain spinning ring while it waits, then a wave that fills with the percent.
                2 -> Box(contentAlignment = Alignment.Center) {
                    val p = job?.progress
                    // The track stays visible on the tonal button: a faint full ring the wave fills.
                    val track = cs.onSecondaryContainer.copy(alpha = 0.16f)
                    if (p == null) CircularProgressIndicator(Modifier.size(46.dp), strokeWidth = 3.dp, trackColor = track)
                    else {
                        val shown by androidx.compose.animation.core.animateFloatAsState(p, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "progress")
                        CircularWavyProgressIndicator(progress = { shown }, modifier = Modifier.size(46.dp), trackColor = track)
                    }
                    Symbol(Glyph.DOWNLOAD, size = 20.dp)
                }
                else -> Symbol(Glyph.DOWNLOAD)
            }
        }
    }
}

/** Under the buttons while a download runs or after it failed: what it is doing, in a small pill, as Play Store does. */
@Composable
internal fun DownloadStatus(key: String) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val jobs by graph.soulseek.jobs.collectAsState()
    val job = jobs.lastOrNull { it.key == key }?.takeIf { !it.done }
    val cs = MaterialTheme.colorScheme
    androidx.compose.animation.AnimatedVisibility(job != null,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
        val shown = remember { mutableStateOf(job) }.apply { if (job != null) value = job }.value ?: return@AnimatedVisibility
        Surface(shape = RoundedCornerShape(50), color = if (shown.failed) cs.errorContainer else cs.surfaceContainerHigh,
            contentColor = if (shown.failed) cs.onErrorContainer else cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) {
            Text(shown.status, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize())
        }
    }
}

/** The glyph for a mood, so the row reads at a glance. */
internal fun moodGlyph(label: String): Glyph = when (TextFold.fold(label)) {
    "energize" -> Glyph.ENERGY; "feel good" -> Glyph.HAPPY; "relax", "chill" -> Glyph.RELAX; "party" -> Glyph.PARTY
    "workout" -> Glyph.WORKOUT; "commute" -> Glyph.COMMUTE; "romance" -> Glyph.FAVOURITE; "sad" -> Glyph.RAIN
    "focus" -> Glyph.FOCUS; "sleep" -> Glyph.SLEEP; else -> Glyph.MIX
}

/**
 * The moods along the top of Home, as YouTube Music's chips: a tap filters Home to that mood in place
 * (the chip fills in), a second tap or Back returns to the whole Home.
 */
@Composable
internal fun MoodRow(moods: List<YouTubeMusic.Mood>, modifier: Modifier = Modifier, chosen: String? = null, onSelect: (YouTubeMusic.Mood?) -> Unit = {}) {
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(moods, key = { it.label }) { mood ->
            val on = mood.label == chosen
            val container by androidx.compose.animation.animateColorAsState(if (on) cs.primary else cs.surfaceContainerHigh, MaterialTheme.motionScheme.fastEffectsSpec(), label = "mood")
            val ink by androidx.compose.animation.animateColorAsState(if (on) cs.onPrimary else cs.onSurface, MaterialTheme.motionScheme.fastEffectsSpec(), label = "mood-ink")
            Surface(onClick = { haptics.tick(); onSelect(if (on) null else mood) }, shape = RoundedCornerShape(50), color = container, contentColor = ink,
                modifier = Modifier.height(40.dp).squish(0.04f).semantics { selected = on }) {
                Row(Modifier.padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(moodGlyph(mood.label), size = 20.dp, color = if (on) cs.onPrimary else cs.primary, filled = on)
                    Spacer(Modifier.width(8.dp))
                    Text(mood.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** What Home shows for a mood: YouTube Music's page for it and the owner's songs that fit it. */
internal class MoodView(val page: YouTubeMusic.MoodPage?, val mine: List<Track>, val songs: List<Track>, val loading: Boolean)

/**
 * Home filtered to a mood, worked out off the main thread: YouTube Music's playlists and songs for
 * it (cached for the session) and the owner's songs whose genre suits it. Offline, only his.
 */
@Composable
internal fun rememberMoodView(mood: YouTubeMusic.Mood, library: LibraryIndex): MoodView {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val on by graph.youTubeOn.collectAsState()
    val network = LocalNetwork.current
    val mine by produceState(emptyList<Track>(), mood.label, library) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Mix.moodSongs(mood.label, library.songs).shuffled(java.util.Random(mood.label.hashCode().toLong())).take(30) }
    }
    val cached = graph.youTube.peekMood(mood)
    val page by produceState(cached, mood, on, network) { if (on && network && value == null) value = runCatching { graph.youTube.mood(mood) }.getOrNull() }
    val waiting = page == null && on && network && mood.params != null
    // Spinner time is bounded: after one try the page shows what it has.
    var tried by remember(mood) { mutableStateOf(cached != null) }
    LaunchedEffect(page) { if (page != null) tried = true }
    LaunchedEffect(mood, network) { kotlinx.coroutines.delay(8_000); tried = true }
    return MoodView(page, rememberOwned(mine, library), rememberOwned(page?.songs.orEmpty(), library), waiting && !tried)
}

/** The mood's part of Home: a big Play, YouTube Music's playlists, the owner's fitting songs, more songs. */
internal fun androidx.compose.foundation.lazy.LazyListScope.moodItems(mood: YouTubeMusic.Mood, view: MoodView, currentId: String?) {
    item(key = "mood-play") {
        val nav = LocalOnlineNav.current
        val haptics = rememberHaptics()
        val cs = MaterialTheme.colorScheme
        val empty = !view.loading && view.mine.isEmpty() && view.page?.playlists.isNullOrEmpty() && view.page?.songs.isNullOrEmpty()
        Surface(shape = RoundedCornerShape(32.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(), color = cs.primary, contentColor = cs.onPrimary, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) { Symbol(moodGlyph(mood.label), size = 32.dp, filled = true) }
                }
                Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                    Text(mood.label, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when {
                        view.loading -> "Finding music\u2026"
                        empty -> "Nothing fits yet: no song's genre matches, and YouTube Music isn't reachable."
                        view.page != null -> "YouTube Music's ${mood.label.lowercase()} playlist, your own files where you have them"
                        view.mine.isNotEmpty() -> if (view.mine.size >= 30) "Many of your songs fit" else "${view.mine.size} of your songs fit"
                        else -> "A mix that keeps going as you listen"
                    }, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 2)
                }
                FilledIconButton(onClick = { haptics.confirm(); nav.playMood(mood, view.page) }, enabled = !empty,
                    shapes = androidx.compose.material3.IconButtonDefaults.shapes(), modifier = Modifier.size(56.dp).semantics { contentDescription = "Play ${mood.label}" }) {
                    Symbol(Glyph.PLAY, size = 28.dp, filled = true)
                }
            }
        }
    }
    val playlists = view.page?.playlists.orEmpty()
    if (playlists.isNotEmpty()) {
        item(key = "mood-pl-h") { OnlineHeading("Playlists") }
        item(key = "mood-pl") { YouTubeCards(playlists.map { YouTubeMusic.Card(it.id, it.title, it.subtitle, it.cover, YouTubeMusic.Card.Kind.PLAYLIST) }) }
    } else if (view.loading) {
        item(key = "mood-pl-h") { OnlineHeading("Playlists") }
        item(key = "mood-pl-wait") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
                items(3) { Box(Modifier.size(148.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) }
            }
        }
    }
    if (view.mine.isNotEmpty()) {
        item(key = "mood-mine-h") { OnlineHeading("From your library") }
        itemsIndexed(view.mine.take(8), key = { _, t -> "mood-mine-" + t.id.value }) { i, t ->
            val nav = LocalOnlineNav.current
            TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(view.mine, i, mood.label) }
        }
    }
    // YouTube Music's songs for the mood that aren't already shown as the owner's own above.
    val shownMine = view.mine.take(8).mapTo(HashSet()) { it.id }
    val songs = view.songs.filterNot { it.id in shownMine }
    if (songs.isNotEmpty()) {
        item(key = "mood-songs-h") { OnlineHeading("More for ${mood.label.lowercase()}") }
        itemsIndexed(songs.take(10), key = { _, t -> "mood-s-" + t.id.value }) { i, t ->
            val nav = LocalOnlineNav.current
            TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(songs, i, mood.label) }
        }
    }
}

/**
 * A mood: its name, Play, YouTube Music's playlists for it and any songs it lists. Play works even
 * when YouTube Music lists nothing here: it grows a mix from the owner's songs of that mood.
 */
@Composable
internal fun MoodPage(target: OnlineTarget.Mood, currentId: String?, insets: PaddingValues, library: LibraryIndex = LibraryIndex.EMPTY, onTitleShown: (Boolean) -> Unit = {}) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val on by graph.youTubeOn.collectAsState()
    val state = rememberLoad(target, 0) { if (on) graph.youTube.mood(target.mood) ?: YouTubeMusic.MoodPage(emptyList(), emptyList()) else YouTubeMusic.MoodPage(emptyList(), emptyList()) }
    val page = (state as? Load.Done)?.value
    val moodSongs = rememberOwned(page?.songs.orEmpty(), library)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportTitle(list, onTitleShown)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Surface(shape = androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) { Symbol(moodGlyph(target.mood.label), size = 36.dp, filled = true) }
                }
                Spacer(Modifier.height(16.dp))
                Text(target.mood.label, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(16.dp))
                Button(enabled = state !is Load.Wait, onClick = { haptics.confirm(); nav.playMood(target.mood, page) }, modifier = Modifier.height(52.dp)) {
                    Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Play")
                }
            }
        }
        when {
            state is Load.Wait -> { item { Spacer(Modifier.height(16.dp)) }; placeholders(cover = true) }
            page == null || (page.playlists.isEmpty() && page.songs.isEmpty()) -> item {
                Text(if (on) "YouTube Music lists nothing for this mood here. Play makes a mix from your own ${target.mood.label.lowercase()} songs."
                    else "Play makes a mix from your own ${target.mood.label.lowercase()} songs. Turn on YouTube Music in Settings for its playlists.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            else -> {
                if (page.playlists.isNotEmpty()) {
                    item(key = "pl-h") { OnlineHeading("Playlists") }
                    item(key = "pl") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(page.playlists, key = { it.id }) { pl ->
                                Column(Modifier.width(160.dp).clip(RoundedCornerShape(20.dp)).clickable { nav.open(OnlineTarget.Album(pl.id, pl.title, playlist = true, cover = pl.cover, subtitle = pl.subtitle)) }) {
                                    Artwork(pl.cover, Modifier.size(160.dp), 20.dp)
                                    Text(pl.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                                    pl.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)) }
                                }
                            }
                        }
                    }
                }
                if (page.songs.isNotEmpty()) {
                    item(key = "s-h") { OnlineHeading("Songs") }
                    itemsIndexed(moodSongs, key = { _, t -> "m-" + t.id.value }) { i, t -> TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(moodSongs, i, target.mood.label) } }
                }
            }
        }
    }
}

/**
 * A YouTube Music artist or album in Search, drawn as the library's own result rows (same size,
 * same left edge) with a cloud after the subtitle, so both sources read as one list.
 */
@Composable
internal fun OnlineResultRow(card: YouTubeMusic.Card, onOpened: () -> Unit = {}) {
    val nav = LocalOnlineNav.current
    val artist = card.kind == YouTubeMusic.Card.Kind.ARTIST
    ListItem(
        headlineContent = { Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                Symbol(Glyph.CLOUD, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = "On YouTube Music" })
            }
        },
        leadingContent = {
            if (artist) Artwork(card.cover, Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape), 28.dp)
            else Artwork(card.cover, Modifier.size(56.dp), 12.dp)
        },
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).clickable {
            onOpened()
            if (artist) nav.open(OnlineTarget.Artist(card.title, card.id))
            else nav.open(OnlineTarget.Album(card.id, card.title, cover = card.cover, subtitle = card.subtitle))
        },
    )
}

/**
 * Cards of a YouTube Music home shelf, each doing what it is: an album or playlist opens, an
 * artist (round, as YouTube Music draws them) opens their page, a mix or a song plays at once.
 */
@Composable
internal fun YouTubeCards(cards: List<YouTubeMusic.Card>, size: androidx.compose.ui.unit.Dp = 148.dp) {
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.id }) { card ->
            val round = card.kind == YouTubeMusic.Card.Kind.ARTIST
            Column(Modifier.width(size).clip(RoundedCornerShape(20.dp)).clickable {
                when (card.kind) {
                    YouTubeMusic.Card.Kind.ALBUM -> nav.open(OnlineTarget.Album(card.id, card.title, cover = card.cover, subtitle = card.subtitle))
                    YouTubeMusic.Card.Kind.PLAYLIST -> nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle))
                    YouTubeMusic.Card.Kind.ARTIST -> nav.open(OnlineTarget.Artist(card.title, card.id))
                    YouTubeMusic.Card.Kind.MIX -> { haptics.spin(); nav.playMix(card.id, card.title) }
                    YouTubeMusic.Card.Kind.SONG -> nav.play(listOf(Track(ua.olex.music.model.TrackId("yt:${card.id}"), "${ua.olex.music.playback.OnlineStreams.SCHEME}://${card.id}",
                        card.title, YouTubeMusic.artistOf(card.subtitle), artworkUri = card.cover)), 0, null)
                }
            }, horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
                if (round) Artwork(card.cover, Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape), size / 2)
                else Artwork(card.cover, Modifier.size(size), if (size < 140.dp) 16.dp else 20.dp)
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}
