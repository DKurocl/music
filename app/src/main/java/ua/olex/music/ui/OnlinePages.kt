@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    data class Artist(val name: String, val id: String? = null, val full: Boolean = false, val seed: Track? = null,
        val photo: String? = null) : OnlineTarget
    /** An album, or a playlist when [playlist] (no album download for a playlist of many artists). */
    data class Album(val id: String, val title: String, val playlist: Boolean = false, val cover: String? = null, val subtitle: String? = null, val seed: Track? = null) : OnlineTarget
}

internal fun onlineArtistTarget(track: Track): OnlineTarget.Artist {
    val credit = YouTubeMusic.artistOfTrack(track)
    return OnlineTarget.Artist(credit?.second ?: YouTubeMusic.artistCredits(track).firstOrNull().orEmpty(),
        id = credit?.first, seed = track.takeIf(YouTubeMusic::isOnline))
}

/** What online pages can do in the app: open another page, play, show a song's actions, open the owner's own artist. */
internal class OnlineNav(
    val open: (OnlineTarget) -> Unit = {},
    /** Plays a list; the last argument names it for the player's "Playing from". */
    val play: (List<Track>, Int, String?) -> Unit = { _, _, _ -> },
    val actions: (Track) -> Unit = {},
    val openDownload: (Int) -> Unit = {},
    val openLocalArtist: (MusicCollection) -> Unit = {},
    /** Starts a Soulseek download, asking for the account first when there is none. */
    val download: (() -> Unit) -> Unit = { it() },
    /** Plays a mood (its playlist, or a mix of the owner's songs of that mood). */
    val playMood: (YouTubeMusic.Mood, YouTubeMusic.MoodPage?) -> Unit = { _, _ -> },
    /** Plays a YouTube Music mix ("My Mix 1") by its playlist id, endless like a mix. */
    val playMix: (String, String) -> Unit = { _, _ -> },
    /** Plays an individually selected recommendation or search result as the seed of a mix. */
    val playTrackMix: ((Track) -> Unit)? = null,
)

internal val LocalOnlineNav = staticCompositionLocalOf { OnlineNav() }

/**
 * A value worked out from the library on a background thread: [initial] until it is ready, then the
 * result, recomputed when a key changes. For anything that walks every song, album or artist.
 */
@Composable
internal fun <T> rememberOffMain(vararg keys: Any?, initial: T, compute: () -> T): T =
    produceState(initial, *keys) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { compute() } }.value

@Composable
internal fun rememberOnlineAlbumId(mediaId: String?, accountScope: String): String? {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    return key(mediaId, accountScope) {
        produceState<String?>(null) {
            value = mediaId?.takeIf { it.startsWith("yt:") }?.let { graph.youTube.savedAlbumId(it) }
        }.value
    }
}

private sealed interface Load<out T> { data object Wait : Load<Nothing>; data object Failed : Load<Nothing>; data class Done<T>(val value: T) : Load<T> }

/** Loads a page; one opened before this session is there in the first frame ([cached]). */
@Composable
private fun <T> rememberLoad(key: Any, retry: Int, cached: T? = null, load: suspend () -> T?): Load<T> =
    androidx.compose.runtime.key(key) {
        produceState<Load<T>>(cached?.let { Load.Done(it) } ?: Load.Wait, retry) {
            if (cached != null && retry == 0) return@produceState
            if (value !is Load.Done) value = Load.Wait
            val loaded = try { load() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            value = loaded?.let { Load.Done(it) } ?: Load.Failed
        }.value
    }

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
    val owned = LocalOwnedSources.current
    val first = songs.firstOrNull()?.takeIf(YouTubeMusic::isOnline)?.takeUnless { it.uri in owned }?.id?.value?.removePrefix("yt:")
    val network = LocalNetwork.current
    LaunchedEffect(first, network) { if (network && first != null) runCatching { graph.youTube.warm(first) } }
}

/** A song row's place while the list loads: the same height as a row, so nothing jumps when it fills. */
@Composable
internal fun PlaceholderRow(cover: Boolean = true) {
    val loadingPulse = rememberLoadingPulse()
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    // The row keeps its place at once (nothing moves when content lands) but only shows after a
    // moment, so a page that answers quickly never flashes grey bars.
    val shown = rememberPatient(true, delayMs = 200)
    val appear by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f,
        MaterialTheme.motionScheme.slowEffectsSpec(), label = "placeholder")
    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 24.dp).graphicsLayer { alpha = loadingPulse() * appear }, verticalAlignment = Alignment.CenterVertically) {
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
    var retry by remember(target) { mutableIntStateOf(0) }
    val network = LocalNetwork.current
    val accountScope by graph.youTubeAccount.cacheScope.collectAsStateWithLifecycle()
    val state = key(target, accountScope, network) {
        produceState<Load<YouTubeMusic.OnlineArtist>>(Load.Wait, retry) {
            if (value is Load.Failed) value = Load.Wait
            val artist = try {
                graph.youTube.artistPage(target.name, target.id, target.seed, offline = !network, full = target.full) { value = Load.Done(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (artist != null) value = Load.Done(artist)
            else if (value !is Load.Done) value = Load.Failed
        }.value
    }
    val local = key(target.name) { rememberOffMain(library, initial = null as MusicCollection?) {
        val name = TextFold.fold(target.name)
        library.artists.firstOrNull { TextFold.fold(it.name) == name }
    } }
    val artist = (state as? Load.Done)?.value
    val onlineTracks = artist?.songs.orEmpty().ifEmpty { artist?.videos.orEmpty() }
    val localTracks = local?.tracks.orEmpty()
    val extraTracks = key(target, artist, local) {
        rememberOffMain(artist, local, initial = emptyList<Track>()) {
            if (onlineTracks.isEmpty()) localTracks else localOnlyTracks(onlineTracks, localTracks)
        }
    }
    val onDevice = if (onlineTracks.isEmpty()) localTracks else extraTracks
    val playable = remember(onlineTracks, onDevice) { onlineTracks + onDevice }
    val name = artist?.name ?: target.name
    val origin = (artist?.id ?: target.id)?.takeIf { it.isNotEmpty() }?.let { PlaybackOrigin("artist", it, name).encode() } ?: name
    val cs = MaterialTheme.colorScheme
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportTitle(list, onTitleShown)
    WarmFirst(onlineTracks)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                // As YouTube Music opens an artist: the photo across the page, the name on it, then the actions.
                val subtitle = artist?.listeners ?: if (artist != null || state is Load.Wait) "YouTube Music" else if (playable.isNotEmpty()) "From your library" else null
                ArtistHero(artist?.photo ?: target.photo ?: local?.artworkUri, name, subtitle)
                Spacer(Modifier.height(16.dp))
                PageActions(shuffleFirst = true, enabled = playable.isNotEmpty(),
                    onPlay = { nav.play(playable, 0, origin) }, onShuffle = { nav.play(playable.shuffled(), 0, origin) })
            }
        }
        if (onlineTracks.isNotEmpty()) {
            item(key = "songs-h") { OnlineHeading(if (artist?.partial == true) "Songs from YouTube Music" else "Top songs") }
            itemsIndexed(onlineTracks, key = { _, t -> "yt-" + t.id.value }, contentType = { _, _ -> "song" }) { i, track ->
                TrackRow(track, current = track.id.value == currentId, onMore = { nav.actions(track) }) { nav.play(playable, i, origin) }
            }
        }
        if (artist != null) {
            val releases = artist.albums
            if (releases.isNotEmpty()) {
                item(key = "albums-h") {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { OnlineHeading("Albums") }
                        if (!target.full && !artist.partial && artist.id.isNotEmpty()) {
                            TextButton(onClick = { nav.open(target.copy(id = artist.id, full = true, photo = artist.photo ?: target.photo)) }) { Text("See all") }
                        }
                    }
                }
                item(key = "albums") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(releases, key = { it.id }) { album ->
                            OnlineCard(album.cover, album.title, album.subtitle) {
                                nav.open(OnlineTarget.Album(album.id, album.title, cover = album.cover, subtitle = album.subtitle))
                            }
                        }
                    }
                }
            }
            if (artist.singles.isNotEmpty()) {
                item(key = "singles-h") { OnlineHeading("Singles and EPs") }
                item(key = "singles") { YouTubeCards(artist.singles.map { YouTubeMusic.Card(it.id, it.title, it.subtitle, it.cover, YouTubeMusic.Card.Kind.ALBUM) }) }
            }
        }
        if (onDevice.isNotEmpty()) {
            item(key = "local-heading") { OnlineHeading("From your library") }
            itemsIndexed(onDevice, key = { _, track -> "local-" + track.id.value }, contentType = { _, _ -> "song" }) { i, track ->
                TrackRow(track, current = track.id.value == currentId, onMore = { nav.actions(track) }) {
                    nav.play(playable, onlineTracks.size + i, origin)
                }
            }
        }
        when {
            state is Load.Wait && playable.isEmpty() -> placeholders(cover = true)
            state is Load.Failed && !network -> if (playable.isEmpty()) item(key = "offline") {
                Text("You're offline. Connect to open this artist.", Modifier.padding(20.dp))
            }
            state is Load.Failed -> item(key = "retry-artist") {
                if (playable.isEmpty()) OnlineFailed(name, Glyph.SEARCH, target.id) { retry++ }
                else ErrorNotice("YouTube Music is unavailable.") { retry++ }
            }
            artist?.partial == true -> item(key = "retry-artist") { ErrorNotice("The full artist page couldn't load.") { retry++ } }
        }
        if (artist != null) {
            artist.about?.let { about -> item(key = "about") { AboutCard(about) } }
            if (artist.related.isNotEmpty()) {
                item(key = "related-h") { OnlineHeading("Fans also like") }
                item(key = "related") { YouTubeCards(artist.related) }
            }
            if (artist.songs.isNotEmpty() && artist.videos.isNotEmpty()) {
                item(key = "videos-h") { OnlineHeading("Videos") }
                item(key = "videos") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        itemsIndexed(artist.videos, key = { _, video -> video.id.value }) { i, video ->
                            OnlineCard(video.artworkUri, video.title, null) { nav.play(artist.videos, i, name) }
                        }
                    }
                }
            }
        }
    }
}

private fun localOnlyTracks(provider: List<Track>, local: List<Track>): List<Track> {
    if (provider.isEmpty() || local.isEmpty()) return local
    val index = YouTubeMusic.Companion.Index(local)
    val represented = provider.mapNotNullTo(HashSet()) { index.copyOf(it)?.id }
    val providerUris = provider.mapTo(HashSet()) { it.uri }
    return local.filterNot { it.id in represented || it.uri in providerUris || it.sources.any { source -> source.uri in providerUris } }
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
    val network = LocalNetwork.current
    val accountScope by graph.youTubeAccount.cacheScope.collectAsStateWithLifecycle()
    val state = rememberLoad(Triple(target, accountScope, network), retry, graph.youTube.peekAlbum(target.id)) {
        graph.youTube.albumPage(YouTubeMusic.OnlineAlbum(target.id, target.title, target.subtitle, target.cover), target.seed, offline = !network)
    }
    // A long playlist arrives in parts: the rest is fetched in the background and added under what is
    // on screen, so nothing moves while the owner reads or plays.
    var grown by remember(state) { mutableStateOf<Pair<YouTubeMusic.OnlineAlbum, List<Track>>?>(null) }
    var pageRetry by remember(target, accountScope) { mutableIntStateOf(0) }
    var moreFailed by remember(target, accountScope) { mutableStateOf(false) }
    var loadingMore by remember(target, accountScope) { mutableStateOf(false) }
    LaunchedEffect(target, accountScope, network, state, pageRetry) {
        loadingMore = false
        moreFailed = false
        if (!network || state !is Load.Done) return@LaunchedEffect
        while (graph.youTube.hasAlbumContinuation(target.id)) {
            loadingMore = true
            val next = try { graph.youTube.albumMore(target.id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (next == null) { moreFailed = graph.youTube.hasAlbumContinuation(target.id); break }
            grown = next
        }
        loadingMore = false
    }
    val loaded = grown ?: (state as? Load.Done)?.value
    val localRelease = rememberOffMain<MusicCollection?>(target, library, initial = null) {
        if (target.playlist) return@rememberOffMain null
        val title = TextFold.fold(target.title)
        val artist = TextFold.fold(target.seed?.artist ?: YouTubeMusic.artistOf(target.subtitle))
        if (artist.isBlank()) return@rememberOffMain null
        library.albums.singleOrNull { TextFold.fold(it.name) == title && TextFold.fold(it.subtitle.orEmpty()) == artist }
    }
    val localFallback = loaded?.second.isNullOrEmpty() && localRelease != null
    val songs = loaded?.second.orEmpty().ifEmpty { localRelease?.tracks.orEmpty() }
    WarmFirst(songs)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    ReportTitle(list, onTitleShown)
    val album = loaded?.first
    val title = album?.title ?: target.title
    val origin = PlaybackOrigin(if (target.playlist) "remote-playlist" else "album", target.id, title).encode()
    val artistName = songs.firstOrNull()?.artist?.substringBefore(",")?.trim().orEmpty()
    // A playlist mixes albums: its rows show covers. An album's rows show track numbers.
    val mixed = target.playlist || remember(songs) { songs.any { it.album != songs.firstOrNull()?.album } }
    val incomplete = loadingMore || moreFailed || album?.partial == true
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(album?.cover ?: target.cover ?: localRelease?.artworkUri, Modifier.size(220.dp), 28.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE)
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                (album?.subtitle ?: target.subtitle)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (!target.playlist && artistName.isNotEmpty()) TextButton(onClick = { nav.open(onlineArtistTarget(songs.first())) }) { Text(artistName) }
                Spacer(Modifier.height(12.dp))
                PageActions(shuffleFirst = false, enabled = songs.isNotEmpty(), playLabel = if (incomplete) "Play loaded" else "Play",
                    onPlay = { nav.play(songs, 0, origin) }, onShuffle = { nav.play(songs.shuffled(), 0, origin) }) {
                    // The whole album in lossless from Soulseek, into the library. Nothing to fetch
                    // once every song plays from a file.
                    if (!target.playlist && songs.isNotEmpty() && album?.partial != true && !localFallback) DownloadButton(ua.olex.music.soulseek.SoulseekDownloads.albumKey(artistName, title),
                        owned = songs.none(YouTubeMusic::isOnline), what = "the album") {
                        nav.download { graph.soulseek.album(artistName, title) }
                    }
                }
                if (incomplete && songs.isNotEmpty()) {
                    Text(if (loadingMore) "${songCount(songs.size)} loaded · Loading more…" else "${songCount(songs.size)} available · Partial list",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!target.playlist && songs.isNotEmpty()) DownloadStatus(ua.olex.music.soulseek.SoulseekDownloads.albumKey(artistName, title))
                // Room between the buttons and the first song, so a playing row's highlight never touches them.
                Spacer(Modifier.height(16.dp))
            }
        }
        when {
            loaded != null || localFallback -> {
                if (localFallback) item(key = "from-library") { OnlineHeading("From your library") }
                if (!network && songs.isNotEmpty() && !localFallback) item(key = "offline") {
                    Text("Offline · ${songCount(songs.size)} in the saved list", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                if (album?.partial == true && !loadingMore && !moreFailed) item(key = "retry-album") {
                    ErrorNotice("Showing available tracks. The full list couldn't load.") { retry++ }
                }
                itemsIndexed(songs, key = { i, track -> "a-$i-" + track.id.value }, contentType = { _, _ -> "song" }) { i, track ->
                    TrackRow(track, modifier = Modifier.animateItem(), current = track.id.value == currentId, onMore = { nav.actions(track) },
                        number = if (mixed || album?.partial == true) null else i + 1, albumArtist = if (mixed) null else track.artist) { nav.play(songs, i, origin) }
                }
                if (localFallback && network && state is Load.Failed) item(key = "retry-local-album") {
                    ErrorNotice("The YouTube Music album couldn't load.") { retry++ }
                }
                else if (moreFailed) item(key = "retry-page") { ErrorNotice("Couldn't load the rest of this list.") { pageRetry++ } }
                else if (loadingMore) item(key = "next-page") { PlaceholderRow(cover = mixed) }
                else if (songs.isEmpty()) item { EmptyState(Glyph.NOTE, "No songs available", "There are no playable songs in this list.") }
            }
            state is Load.Wait -> placeholders(cover = mixed)
            else -> item { OnlineFailed(target.title, Glyph.ALBUM, target.id) { retry++ } }
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
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? ua.olex.music.MusicApp)?.graph ?: return
    val on by graph.youTubeOn.collectAsStateWithLifecycle()
    if (!on) return
    val nav = LocalOnlineNav.current
    val network = LocalNetwork.current
    val account by graph.youTubeAccount.cacheScope.collectAsStateWithLifecycle()
    var retry by remember(name) { mutableIntStateOf(0) }
    val state = rememberLoad(Triple(name, network, account), retry) {
        graph.youTube.artistId(name, offline = !network)?.let { graph.youTube.artist(it, offline = !network, knownName = name) }
    }
    val artist = (state as? Load.Done)?.value
    if (artist == null) {
        when (state) {
            Load.Wait -> PlaceholderRow()
            Load.Failed -> ErrorNotice(if (network) "Couldn't load this artist from YouTube Music." else "YouTube Music is unavailable offline.") { retry++ }
            else -> Unit
        }
        return
    }
    val popular = artist.songs
    // Albums the owner already has open from his library above; only the others are offered here.
    val owned = key(name) { rememberOffMain(library, initial = emptySet()) {
        val artistName = TextFold.fold(name)
        library.albums.asSequence().filter { TextFold.fold(it.subtitle.orEmpty()) == artistName }.map { TextFold.fold(it.name) }.toSet()
    } }
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
private fun OnlineFailed(name: String, glyph: Glyph, id: String?, retry: () -> Unit) {
    val network = LocalNetwork.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val link = id?.let(YouTubeMusic::catalogueUrl)?.toString()
    val again by rememberUpdatedState(retry)
    // Only when the connection comes back, not on every failure (that would retry in a loop).
    var wasOffline by remember { mutableStateOf(!network) }
    LaunchedEffect(network) { if (network && wasOffline) again(); wasOffline = !network }
    if (!network) EmptyState(Glyph.CLOUD_OFF, "You're offline", "Your own songs still play. $name opens as soon as the phone is back online.")
    else EmptyState(glyph, "Couldn't open $name", "The page couldn't be loaded in Music.",
        actions = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = retry) { Text("Try again") }
                if (link != null) TextButton(onClick = { uriHandler.openUri(link) }) { Text("Open in YouTube Music") }
            }
        })
}

@Composable
private fun OnlineHeading(text: String) = SectionHeader(text)

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
private fun OnlineCard(cover: String?, title: String, subtitle: String?, onClick: () -> Unit) = MediaCard(cover, title, subtitle, onClick = onClick)

/**
 * A download button that shows where its download is, in place, as Play Store's install button
 * does: an arrow, a spinner while Soulseek searches, a ring filling with the percent, a tick when
 * the files are in the library, and a retry on the error colour when it failed. Tapping while it
 * runs says what it is doing; a failed one tells why and tries again.
 */
@Composable
internal fun DownloadButton(key: String, owned: Boolean, what: String, onStart: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val jobs by graph.soulseek.jobs.collectAsStateWithLifecycle()
    val job = jobs.lastOrNull { it.key == key }
    val snackbar = LocalSnackbar.current
    val nav = LocalOnlineNav.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val done = owned || job?.done == true
    val failed = job?.failed == true || job?.cancelled == true
    val description = when {
        job?.done == true -> "View completed download"
        done -> "Already in your library"
        failed -> "Download failed: ${job?.status}. Try again"
        job?.running == true -> "View download: ${job.status}"
        else -> "Download $what"
    }
    FilledTonalIconButton(onClick = {
        when {
            job != null && (job.running || job.done) -> nav.openDownload(job.id)
            done -> scope.launch { snackbar?.showSnackbar("Already in your library") }
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
    val jobs by graph.soulseek.jobs.collectAsStateWithLifecycle()
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
    "focus", "study", "concentration" -> Glyph.FOCUS; "sleep", "bedtime" -> Glyph.SLEEP; else -> Glyph.NOTE
}

/** A local mood queue can play while its account-scoped recommendations arrive. */
internal data class MoodView(val page: YouTubeMusic.MoodPage?, val loading: Boolean)

@Composable
internal fun rememberMoodView(mood: YouTubeMusic.Mood, online: Boolean): MoodView {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val cached = if (online) graph.youTube.peekMood(mood) else null
    val fetch = online && mood.params != null
    return produceState(MoodView(cached, cached == null && fetch), mood, online) {
        if (fetch) {
            val page = cached ?: runCatching { graph.youTube.mood(mood) }.getOrNull()
            value = MoodView(page, false)
        }
    }.value
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
            if (artist) nav.open(OnlineTarget.Artist(card.title, card.id, photo = card.cover))
            else nav.open(OnlineTarget.Album(card.id, card.title, playlist = card.kind == YouTubeMusic.Card.Kind.PLAYLIST, cover = card.cover, subtitle = card.subtitle))
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
    // A rail comes to rest with a whole card on the 16dp edge, never one cut through its corners.
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state, androidx.compose.foundation.gestures.snapping.SnapPosition.Start)) {
        items(cards, key = { it.id }) { card ->
            MediaCard(card.cover, card.title, card.subtitle, size = size, round = card.kind == YouTubeMusic.Card.Kind.ARTIST) {
                when (card.kind) {
                    YouTubeMusic.Card.Kind.ALBUM -> nav.open(OnlineTarget.Album(card.id, card.title, cover = card.cover, subtitle = card.subtitle))
                    YouTubeMusic.Card.Kind.PLAYLIST -> nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle))
                    YouTubeMusic.Card.Kind.ARTIST -> nav.open(OnlineTarget.Artist(card.title, card.id, photo = card.cover))
                    YouTubeMusic.Card.Kind.MIX -> { haptics.spin(); nav.playMix(card.id, card.title) }
                    YouTubeMusic.Card.Kind.SONG -> {
                        val song = card.song()
                        nav.playTrackMix?.invoke(song) ?: nav.play(listOf(song), 0, null)
                    }
                }
            }
        }
    }
}

/** The one rail of the app: cards 16dp from the edge, 12dp apart, as YouTube Music lays its shelves. */
@Composable
internal fun <T> CardRail(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state, androidx.compose.foundation.gestures.snapping.SnapPosition.Start)) {
        items(items, key = key) { card(it) }
    }
}

/**
 * The one card of the app (albums, playlists, artists, mixes; local or online): a square cover
 * (round for an artist) and room for a two-line title and a subtitle, so every card in a row has
 * one height whatever its text.
 */
@Composable
internal fun MediaCard(cover: String?, title: String, subtitle: String?, modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp? = 148.dp, round: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val textBlock = rememberCardTextHeight()
    // A fixed width in a rail; the cell's width in a grid (size null).
    val frame = if (size != null) Modifier.size(size) else Modifier.fillMaxWidth().aspectRatio(1f)
    Column(modifier.then(if (size != null) Modifier.width(size) else Modifier).squish(0.03f).clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open $title", onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        if (round) Artwork(cover, frame.clip(androidx.compose.foundation.shape.CircleShape), (size ?: 148.dp) / 2, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
        else Artwork(cover, frame, 16.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
        Column(Modifier.height(textBlock), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (round) androidx.compose.ui.text.style.TextAlign.Center else null,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (round) androidx.compose.ui.text.style.TextAlign.Center else null, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
    }
}

/**
 * The one action row of album, playlist and artist pages: two wide buttons of equal width (the
 * main action filled, the other tonal) and any further actions as round tonal buttons after them.
 * Albums and playlists lead with Play, artists with Shuffle, as YouTube Music does.
 */
@Composable
internal fun PageActions(shuffleFirst: Boolean, enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit,
    playLabel: String = "Play", extras: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun play(filled: Boolean) {
            val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text(playLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (filled) Button(enabled = enabled, onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp), content = content)
            else FilledTonalButton(enabled = enabled, onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp), content = content)
        }
        @Composable fun shuffle(filled: Boolean) {
            val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                Symbol(Glyph.SHUFFLE, size = 20.dp); Spacer(Modifier.size(8.dp)); Text("Shuffle", maxLines = 1)
            }
            if (filled) Button(enabled = enabled, onClick = onShuffle, modifier = Modifier.weight(1f).height(52.dp), content = content)
            else FilledTonalButton(enabled = enabled, onClick = onShuffle, modifier = Modifier.weight(1f).height(52.dp), content = content)
        }
        if (shuffleFirst) { shuffle(true); play(false) } else { play(true); shuffle(false) }
        extras()
    }
}

/** The artist's photo across the page, the name on a soft shade at its foot, as YouTube Music draws it. */
@Composable
private fun ArtistHero(photo: String?, name: String, line: String?) =
    ArtistHero(name, line) { Artwork(photo, Modifier.fillMaxSize(), 0.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE) }

@Composable
internal fun ArtistHero(name: String, line: String?, picture: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        picture()
        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            0.45f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(name, style = MaterialTheme.typography.displaySmallEmphasized, color = androidx.compose.ui.graphics.Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            line?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The height under a card's picture: two title lines and a subtitle line, at the current font size. */
@Composable
internal fun rememberCardTextHeight(): androidx.compose.ui.unit.Dp {
    val type = MaterialTheme.typography
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(type, density) { with(density) { type.titleSmall.lineHeight.toDp() * 2 + type.bodySmall.lineHeight.toDp() + 8.dp } }
}

/**
 * A slow breath for loading shapes (0.55 to 1 alpha), read while drawing so nothing recomposes;
 * steady in Battery Saver.
 */
@Composable
internal fun rememberLoadingPulse(): () -> Float {
    if (LocalPowerSave.current) return { 1f }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading").animateFloat(0.55f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    return { pulse.value }
}
