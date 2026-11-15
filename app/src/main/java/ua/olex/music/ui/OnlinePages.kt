@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.animateContentSize
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
    data class Artist(val name: String, val id: String? = null) : OnlineTarget
    /** An album, or a playlist when [playlist] (no album download for a playlist of many artists). */
    data class Album(val id: String, val title: String, val playlist: Boolean = false) : OnlineTarget
    data class Mood(val mood: YouTubeMusic.Mood) : OnlineTarget
}

/** What online pages can do in the app: open another page, play, show a song's actions, open the owner's own artist. */
internal class OnlineNav(
    val open: (OnlineTarget) -> Unit = {},
    val play: (List<Track>, Int) -> Unit = { _, _ -> },
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

/** Songs the owner has as files are shown (and played) as those files: quality tag and all. */
private fun owned(songs: List<Track>, library: LibraryIndex) = songs.map { YouTubeMusic.localCopy(it, library) ?: it }

private sealed interface Load<out T> { data object Wait : Load<Nothing>; data object Failed : Load<Nothing>; data class Done<T>(val value: T) : Load<T> }

@Composable
private fun <T> rememberLoad(key: Any, retry: Int, load: suspend () -> T?): Load<T> =
    produceState<Load<T>>(Load.Wait, key, retry) { value = Load.Wait; value = runCatching { load() }.getOrNull()?.let { Load.Done(it) } ?: Load.Failed }.value

/**
 * An artist from YouTube Music, joined to the owner's library: their photo and a few lines, the
 * songs people play most (the owner's own files where they have them), albums that open in place,
 * and videos; when the library has the artist too, one tap leads there.
 */
@Composable
internal fun OnlineArtistPage(target: OnlineTarget.Artist, library: LibraryIndex, currentId: String?, insets: PaddingValues, onBack: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val nav = LocalOnlineNav.current
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberLoad(target, retry) {
        val id = target.id ?: graph.youTube.artistId(target.name) ?: return@rememberLoad null
        graph.youTube.artist(id)
    }
    val mine = remember(library, target.name) { library.artists.firstOrNull { TextFold.fold(it.name) == TextFold.fold(target.name) } }
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "back") { IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) } }
        when (state) {
            Load.Wait -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            Load.Failed -> item {
                EmptyState(Glyph.SEARCH, "Couldn't open ${target.name}", "YouTube Music didn't answer. Check the connection and try again.",
                    actions = { Button(onClick = { retry++ }) { Text("Try again") } })
            }
            is Load.Done -> {
                val artist = state.value
                val songs = owned(artist.songs, library)
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(artist.photo, Modifier.size(120.dp), 60.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
                            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                                Text(artist.name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("YouTube Music", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val playable = songs.ifEmpty { artist.videos }
                            Button(enabled = playable.isNotEmpty(), onClick = { nav.play(playable, 0) }, modifier = Modifier.height(52.dp)) {
                                Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Play")
                            }
                            FilledTonalIconButton(enabled = playable.isNotEmpty(), onClick = { nav.play(playable.shuffled(), 0) },
                                modifier = Modifier.size(52.dp).semantics { contentDescription = "Shuffle" }) { Symbol(Glyph.SHUFFLE) }
                            // The owner's own songs by this artist, one tap away.
                            mine?.let { local -> FilledTonalButton(onClick = { nav.openLocalArtist(local) }, modifier = Modifier.height(52.dp)) {
                                Symbol(Glyph.LIBRARY, size = 18.dp); Spacer(Modifier.size(8.dp)); Text("Yours · ${local.tracks.size}")
                            } }
                        }
                    }
                }
                artist.about?.let { about -> item(key = "about") { AboutCard(about) } }
                if (songs.isNotEmpty()) {
                    item(key = "songs-h") { OnlineHeading("Top songs") }
                    itemsIndexed(songs.take(10), key = { _, t -> "s-" + t.id.value }) { i, t ->
                        TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(songs, i) }
                    }
                }
                if (artist.albums.isNotEmpty()) {
                    item(key = "albums-h") { OnlineHeading("Albums and singles") }
                    item(key = "albums") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                            items(artist.albums, key = { it.id }) { a -> OnlineCard(a.cover, a.title, a.subtitle) { nav.open(OnlineTarget.Album(a.id, a.title)) } }
                        }
                    }
                }
                if (artist.videos.isNotEmpty()) {
                    item(key = "videos-h") { OnlineHeading("Videos") }
                    item(key = "videos") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                            itemsIndexed(artist.videos, key = { _, v -> v.id.value }) { i, v -> OnlineCard(v.artworkUri, v.title, null) { nav.play(artist.videos, i) } }
                        }
                    }
                }
            }
        }
    }
}

/** An album from YouTube Music: cover, title, Play and Shuffle, then its songs in order. */
@Composable
internal fun OnlineAlbumPage(target: OnlineTarget.Album, library: LibraryIndex, currentId: String?, insets: PaddingValues, onBack: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val nav = LocalOnlineNav.current
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberLoad(target, retry) { graph.youTube.album(target.id) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "back") { IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) } }
        when (state) {
            Load.Wait -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            Load.Failed -> item {
                EmptyState(Glyph.ALBUM, "Couldn't open ${target.title}", "YouTube Music didn't answer. Check the connection and try again.",
                    actions = { Button(onClick = { retry++ }) { Text("Try again") } })
            }
            is Load.Done -> {
                val (album, raw) = state.value
                val songs = owned(raw, library)
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(album.cover, Modifier.size(220.dp), 28.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE)
                        Spacer(Modifier.height(16.dp))
                        Text(album.title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        album.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        songs.firstOrNull()?.artist?.let { artistName ->
                            TextButton(onClick = { nav.open(OnlineTarget.Artist(artistName)) }) { Text(artistName) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = songs.isNotEmpty(), onClick = { nav.play(songs, 0) }, modifier = Modifier.height(52.dp)) {
                                Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.size(8.dp)); Text("Play all")
                            }
                            FilledTonalIconButton(enabled = songs.isNotEmpty(), onClick = { nav.play(songs.shuffled(), 0) },
                                modifier = Modifier.size(52.dp).semantics { contentDescription = "Shuffle" }) { Symbol(Glyph.SHUFFLE) }
                            // The whole album in lossless from Soulseek, into the library. Nothing to fetch
                            // once every song plays from a file.
                            val artistName = songs.firstOrNull()?.artist?.substringBefore(",")?.trim().orEmpty()
                            if (!target.playlist) DownloadButton(ua.olex.music.soulseek.SoulseekDownloads.albumKey(artistName, album.title),
                                owned = songs.isNotEmpty() && songs.none { it.id.value.startsWith("yt:") }, what = "the album") {
                                nav.download { graph.soulseek.album(artistName, album.title) }
                            }
                        }
                        if (!target.playlist) DownloadStatus(ua.olex.music.soulseek.SoulseekDownloads.albumKey(songs.firstOrNull()?.artist?.substringBefore(",")?.trim().orEmpty(), album.title))
                    }
                }
                itemsIndexed(songs, key = { _, t -> "a-" + t.id.value }) { i, t ->
                    TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }, number = i + 1, albumArtist = t.artist) { nav.play(songs, i) }
                }
            }
        }
    }
}

/**
 * Under the owner's own artist page: the artist's most played songs on YouTube Music that the
 * library does not have, and a way into the full YouTube Music page. Nothing shows while offline.
 */
@Composable
internal fun MoreOnYouTubeMusic(name: String, library: LibraryIndex, currentId: String?) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val on by graph.youTubeOn.collectAsState()
    if (!on) return
    val nav = LocalOnlineNav.current
    val state = rememberLoad(name, 0) { graph.youTube.artistId(name)?.let { graph.youTube.artist(it) } }
    val artist = (state as? Load.Done)?.value ?: return
    val missing = artist.songs.filter { YouTubeMusic.localCopy(it, library) == null }.take(5)
    Column {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { OnlineHeading("More on YouTube Music") }
            TextButton(onClick = { nav.open(OnlineTarget.Artist(artist.name, artist.id)) }) { Text("Open") }
        }
        missing.forEachIndexed { i, t -> TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(missing, i) } }
    }
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

/** The moods along the top of Home: one tap opens the mood; its page plays it. */
@Composable
internal fun MoodRow(moods: List<YouTubeMusic.Mood>, modifier: Modifier = Modifier) {
    val nav = LocalOnlineNav.current
    val haptics = rememberHaptics()
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(moods, key = { it.label }) { mood ->
            Surface(onClick = { haptics.tick(); nav.open(OnlineTarget.Mood(mood)) }, shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.height(40.dp).squish(0.04f)) {
                Row(Modifier.padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(moodGlyph(mood.label), size = 20.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(mood.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * A mood: its name, Play, YouTube Music's playlists for it and any songs it lists. Play works even
 * when YouTube Music lists nothing here: it grows a mix from the owner's songs of that mood.
 */
@Composable
internal fun MoodPage(target: OnlineTarget.Mood, currentId: String?, insets: PaddingValues, onBack: () -> Unit) {
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val on by graph.youTubeOn.collectAsState()
    val state = rememberLoad(target, 0) { if (on) graph.youTube.mood(target.mood) ?: YouTubeMusic.MoodPage(emptyList(), emptyList()) else YouTubeMusic.MoodPage(emptyList(), emptyList()) }
    val page = (state as? Load.Done)?.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding() + 24.dp + LocalPlayerInset.current)) {
        item(key = "back") { IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) } }
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
            state is Load.Wait -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
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
                                Column(Modifier.width(160.dp).clip(RoundedCornerShape(20.dp)).clickable { nav.open(OnlineTarget.Album(pl.id, pl.title, playlist = true)) }) {
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
                    itemsIndexed(page.songs, key = { _, t -> "m-" + t.id.value }) { i, t -> TrackRow(t, current = t.id.value == currentId, onMore = { nav.actions(t) }) { nav.play(page.songs, i) } }
                }
            }
        }
    }
}

/**
 * Cards of a YouTube Music home shelf, each doing what it is: an album or playlist opens, an
 * artist (round, as YouTube Music draws them) opens their page, a mix or a song plays at once.
 */
@Composable
internal fun YouTubeCards(cards: List<YouTubeMusic.Card>) {
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cards, key = { it.id }) { card ->
            val round = card.kind == YouTubeMusic.Card.Kind.ARTIST
            Column(Modifier.width(148.dp).clip(RoundedCornerShape(20.dp)).clickable {
                when (card.kind) {
                    YouTubeMusic.Card.Kind.ALBUM -> nav.open(OnlineTarget.Album(card.id, card.title))
                    YouTubeMusic.Card.Kind.PLAYLIST -> nav.open(OnlineTarget.Album(card.id, card.title, playlist = true))
                    YouTubeMusic.Card.Kind.ARTIST -> nav.open(OnlineTarget.Artist(card.title, card.id))
                    YouTubeMusic.Card.Kind.MIX -> { haptics.spin(); nav.playMix(card.id, card.title) }
                    YouTubeMusic.Card.Kind.SONG -> nav.play(listOf(Track(ua.olex.music.model.TrackId("yt:${card.id}"), "${ua.olex.music.playback.OnlineStreams.SCHEME}://${card.id}",
                        card.title, YouTubeMusic.artistOf(card.subtitle), artworkUri = card.cover)), 0)
                }
            }, horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
                if (round) Artwork(card.cover, Modifier.size(148.dp).clip(androidx.compose.foundation.shape.CircleShape), 74.dp)
                else Artwork(card.cover, Modifier.size(148.dp), 20.dp)
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}
