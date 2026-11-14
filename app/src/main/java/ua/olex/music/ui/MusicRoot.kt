@file:OptIn(ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import ua.olex.music.graph
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.playback.OutputRoute
import ua.olex.music.playback.PlayerState

/**
 * The first working shape of the app: the songs on the phone, a bar for what is playing, and the
 * player page. It proves the path from a file to the earbuds; the designed app (navigation, the
 * expressive player, lyrics) is built over it (docs/ROADMAP.md).
 */
@Composable
fun MusicRoot(model: LibraryViewModel) {
    val library by model.library.collectAsStateWithLifecycle()
    val tracks = library?.songs
    val query by model.query.collectAsStateWithLifecycle()
    val search by model.search.collectAsStateWithLifecycle()
    val personal by model.personal.collectAsStateWithLifecycle()
    val lyrics by model.embeddedLyrics.collectAsStateWithLifecycle()
    val lyricOffset by model.lyricOffset.collectAsStateWithLifecycle()
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    val playing by model.playing.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Library") }, scrollBehavior = scroll,
                actions = {
                    if (model.allowed) TextButton(onClick = model::refresh, enabled = !model.scanning) {
                        Text(if (model.scanning) "Scanning…" else "Rescan")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                playing.error?.let { message ->
                    ErrorNotice(message) { if (playing.connected) model.toggle() else model.connectPlayer() }
                }
                if (playing.hasTrack) MiniPlayer(playing, onOpen = { expanded = true }, onToggle = model::toggle, onNext = model::next)
            }
        },
    ) { insets ->
        val list = tracks
        when {
            !model.allowed -> Notice(
                insets, "Your music", "Allow access to the audio files on this phone to play them.", "Allow",
            ) {
                if (permissionRequested && activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO) == false) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                } else {
                    permissionRequested = true
                    ask.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            model.scanError != null && list.isNullOrEmpty() -> Notice(insets, "Couldn't scan music", model.scanError.orEmpty(), "Retry", model::refresh)
            list == null -> Notice(insets, "Your music", "Loading your library…", null) {}
            else -> LibraryBrowser(
                library = requireNotNull(library), insets = insets,
                query = query, search = search, onQueryChange = model::setQuery,
                currentId = playing.mediaId, onPlay = model::play,
                scanError = model.scanError, onRescan = model::refresh,
                onRetrySearch = model::retrySearch,
                personal = personal, personalActions = model.personalActions,
                onTrackActions = { actionTrack = it }, onAddToPlaylist = { playlistTracks = it },
            )
        }
    }

    AnimatedVisibility(expanded && playing.hasTrack, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        NowPlaying(
            playing, format = playing.mediaId?.let { library?.byId?.get(TrackId(it))?.format?.label },
            position = { model.positionMs }, onClose = { expanded = false },
            onToggle = model::toggle, onNext = model::next, onPrevious = model::previous, onSeek = model::seekTo,
            onSleepTimer = model::setSleepTimer,
            lyrics = lyrics, lyricOffset = lyricOffset, onLyricOffset = model::setLyricOffset,
            favourite = playing.mediaId in personal.favouriteByTrackId,
            onFavourite = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { model.toggleFavourite(track) } },
            onShuffle = model::toggleShuffle, onRepeat = model::cycleRepeat,
            onQueuePlay = model::playQueueItem, onQueueMove = model::moveQueueItem, onQueueRemove = model::removeQueueItem,
            onMore = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { actionTrack = track } },
        )
    }
    actionTrack?.let { track ->
        AlertDialog(onDismissRequest = { actionTrack = null }, title = { Text(track.title) }, text = {
            Column {
                TextButton(onClick = { model.toggleFavourite(track); actionTrack = null }) {
                    Text(if (track.id.value in personal.favouriteByTrackId) "Remove from favourites" else "Add to favourites")
                }
                TextButton(onClick = { playlistTracks = listOf(track); actionTrack = null }) { Text("Add to playlist") }
                TextButton(onClick = { model.enqueue(listOf(track), true); actionTrack = null }) { Text("Play next") }
                TextButton(onClick = { model.enqueue(listOf(track), false); actionTrack = null }) { Text("Add to queue") }
            }
        }, confirmButton = { TextButton(onClick = { actionTrack = null }) { Text("Close") } })
    }
    playlistTracks?.let { PlaylistPicker(personal.playlists, it, model.personalActions) { playlistTracks = null } }
    playing.queueError?.let { message ->
        AlertDialog(onDismissRequest = model::dismissQueueError, title = { Text("Couldn't update queue") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissQueueError) { Text("OK") } })
    }
    model.userError?.let { message ->
        AlertDialog(onDismissRequest = model::dismissUserError, title = { Text("Couldn't save changes") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissUserError) { Text("OK") } })
    }
    BackHandler(expanded) { expanded = false }
}

@Composable
internal fun ErrorNotice(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun Notice(insets: PaddingValues, title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(insets).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun TrackRow(track: Track, current: Boolean, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) cs.primary else cs.onSurface) },
        supportingContent = {
            Text(listOfNotNull(track.artist, track.format?.label).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Artwork(track.artworkUri, Modifier.size(48.dp), 8.dp) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current) Text("Current", style = MaterialTheme.typography.labelSmall)
                if (onMore != null) TextButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = "Actions for ${track.title}" }) { Text("More") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = cs.surface),
        modifier = Modifier.clickable(onClick = onClick).semantics { selected = current },
    )
}

@Composable
internal fun Artwork(uri: String?, modifier: Modifier, corner: Dp) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(corner)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Note, null, tint = cs.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.4f))
        if (uri != null) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open player", onClick = onOpen)) {
        Row(
            Modifier.navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(state.artworkUri, Modifier.size(44.dp), 8.dp)
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(enabled = state.connected, onClick = onToggle, modifier = Modifier.semantics { contentDescription = if (state.playWhenReady) "Pause" else "Play" }) {
                Icon(if (state.playWhenReady) AppIcons.Pause else AppIcons.Play, null)
            }
            IconButton(enabled = state.hasNext, onClick = onNext, modifier = Modifier.semantics { contentDescription = "Next" }) { Icon(AppIcons.Next, null) }
        }
    }
}

@Composable
private fun NowPlaying(
    state: PlayerState, format: String?, position: () -> Long, onClose: () -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    onSleepTimer: (Int) -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit,
    onQueuePlay: (String) -> Unit, onQueueMove: (String, Int) -> Unit, onQueueRemove: (String) -> Unit,
    onMore: (() -> Unit)?,
    lyrics: ua.olex.music.lyrics.Lyrics, lyricOffset: Long, onLyricOffset: (Long) -> Unit,
    favourite: Boolean, onFavourite: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val monitor = LocalContext.current.graph.output
    LifecycleStartEffect(monitor) { monitor.start(); onStopOrDispose { monitor.stop() } }
    val route by monitor.route.collectAsStateWithLifecycle()
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    if (lyricsOpen) LyricsSheet(lyrics, state, lyricOffset, position, onSeek, onLyricOffset) { lyricsOpen = false }
    var timerDialog by rememberSaveable { mutableStateOf(false) }
    var queueSheet by rememberSaveable { mutableStateOf(false) }
    var audioPath by rememberSaveable { mutableStateOf(false) }
    var outputUnavailable by rememberSaveable { mutableStateOf(false) }
    val changeOutput: () -> Unit = { outputUnavailable = !monitor.showOutputSwitcher() }
    if (audioPath) AudioPathSheet(format, route, state.audio, onDismiss = { audioPath = false }, onOutput = changeOutput)
    if (queueSheet) QueueSheet(state, onDismiss = { queueSheet = false }, onPlay = onQueuePlay, onMove = onQueueMove, onRemove = onQueueRemove)
    if (timerDialog) {
        SleepTimerDialog(
            active = state.sleepDeadlineMs != null,
            onDismiss = { timerDialog = false },
            onSet = { minutes -> onSleepTimer(minutes); timerDialog = false },
        )
    }

    PlayerPage(state, format, route, favourite, position, onClose, onToggle, onNext, onPrevious, onSeek,
        onShuffle, onRepeat, onFavourite, onMore, onOutput = changeOutput, onQueue = { queueSheet = true },
        onLyrics = { lyricsOpen = true }, onAudioPath = { audioPath = true }, onTimer = { timerDialog = true })
    if (outputUnavailable) AlertDialog(onDismissRequest = { outputUnavailable = false }, title = { Text("Choose output") },
        text = { Text("Open Android's media output controls to choose a device.") },
        confirmButton = { TextButton(onClick = { outputUnavailable = false }) { Text("OK") } })
}

/** The player surface is independent of service/route bindings so its real layouts can be reviewed in tests. */
@Composable
internal fun PlayerPage(
    state: PlayerState, format: String?, route: OutputRoute?, favourite: Boolean, position: () -> Long,
    onClose: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onFavourite: (() -> Unit)?, onMore: (() -> Unit)?,
    onOutput: () -> Unit, onQueue: () -> Unit, onLyrics: () -> Unit, onAudioPath: () -> Unit, onTimer: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close player" }) { Icon(AppIcons.Down, null) }
                Text("Now playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = onQueue, modifier = Modifier.semantics { contentDescription = "Open queue" }) { Icon(AppIcons.Queue, null) }
            }
            Spacer(Modifier.height(12.dp))
            Artwork(state.artworkUri, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f), 32.dp)
            Spacer(Modifier.height(24.dp))
            Text(state.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(state.artist, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            state.error?.let { Text(it, color = cs.error, modifier = Modifier.padding(top = 8.dp)) }
            TextButton(onClick = onAudioPath) {
                Text(listOfNotNull(format, route?.label()).joinToString("  →  ").ifEmpty { "Audio path" }, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            }
            PlaybackProgress(state, position, onSeek)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(enabled = state.connected, onClick = onShuffle, modifier = Modifier.semantics {
                    contentDescription = "Shuffle"; selected = state.shuffle; stateDescription = if (state.shuffle) "On" else "Off"
                }) { Icon(AppIcons.Shuffle, null, tint = if (state.shuffle) cs.primary else cs.onSurfaceVariant) }
                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp).semantics { contentDescription = "Previous" }) { Icon(AppIcons.Previous, null, Modifier.size(30.dp)) }
                FilledIconButton(enabled = state.connected, onClick = onToggle, shape = RoundedCornerShape(28.dp), modifier = Modifier.size(88.dp).semantics {
                    contentDescription = if (state.playWhenReady) "Pause" else "Play"
                }) { Icon(if (state.playWhenReady) AppIcons.Pause else AppIcons.Play, null, Modifier.size(42.dp)) }
                IconButton(enabled = state.hasNext, onClick = onNext, modifier = Modifier.size(52.dp).semantics { contentDescription = "Next" }) { Icon(AppIcons.Next, null, Modifier.size(30.dp)) }
                IconButton(enabled = state.connected, onClick = onRepeat, modifier = Modifier.semantics {
                    contentDescription = "Repeat"; stateDescription = state.repeat.name.lowercase()
                }) { Icon(if (state.repeat == ua.olex.music.playback.RepeatMode.ONE) AppIcons.RepeatOne else AppIcons.Repeat, null,
                    tint = if (state.repeat == ua.olex.music.playback.RepeatMode.OFF) cs.onSurfaceVariant else cs.primary) }
            }
            Spacer(Modifier.height(24.dp))
            Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = onLyrics, modifier = Modifier.semantics { contentDescription = "Lyrics" }) { Icon(AppIcons.Lyrics, null) }
                    IconButton(enabled = onFavourite != null, onClick = { onFavourite?.invoke() }, modifier = Modifier.semantics {
                        contentDescription = if (favourite) "Remove from favourites" else "Add to favourites"; selected = favourite
                    }) { Icon(if (favourite) AppIcons.Favourite else AppIcons.FavouriteOutline, null, tint = if (favourite) cs.primary else cs.onSurface) }
                    IconButton(onClick = onOutput, modifier = Modifier.semantics { contentDescription = "Change output" }) { Icon(AppIcons.Output, null) }
                    IconButton(onClick = onTimer, enabled = state.connected, modifier = Modifier.semantics {
                        contentDescription = "Sleep timer"; stateDescription = if (state.sleepDeadlineMs != null) "On" else "Off"
                    }) { Icon(AppIcons.Timer, null) }
                    if (onMore != null) IconButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = "Song actions" }) { Icon(AppIcons.More, null) }
                }
            }
            state.sleepTimerError?.let { Text(it, color = cs.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun PlaybackProgress(state: PlayerState, position: () -> Long, onSeek: (Long) -> Unit) {
    // Keep the frequent position updates out of artwork, metadata and controls. Composition may
    // survive screen-off, so the poller also follows lifecycle rather than only visibility.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var at by remember(state.mediaId) { mutableLongStateOf(position()) }
    var dragged by remember(state.mediaId) { mutableStateOf<Float?>(null) }
    LaunchedEffect(lifecycle, state.isPlaying, state.mediaId, state.positionMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { at = position(); if (state.isPlaying) delay(250) } while (state.isPlaying)
        }
    }
    val duration = state.durationMs.coerceAtLeast(1)
    val cs = MaterialTheme.colorScheme
    Column {
        Slider(
            value = dragged ?: (at.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragged = it },
            onValueChangeFinished = { dragged?.let { onSeek((it * duration).toLong()); at = (it * duration).toLong() }; dragged = null },
            enabled = state.durationMs > 0,
            modifier = Modifier.semantics {
                contentDescription = "Position"
                stateDescription = "${clock(at)} of ${clock(state.durationMs)}"
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(dragged?.let { (it * duration).toLong() } ?: at), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(clock(state.durationMs), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** Timer choices send a single command; there is no UI countdown or background polling loop. */
@Composable
internal fun SleepTimerDialog(active: Boolean, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Pause music after:")
                listOf(5, 15, 30, 60, 90).forEach { minutes ->
                    TextButton(onClick = { onSet(minutes) }, modifier = Modifier.fillMaxWidth()) { Text("$minutes minutes") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (active) TextButton(onClick = { onSet(0) }) { Text("Cancel timer") } },
    )
}

private fun OutputRoute.label(): String = when (kind) {
    OutputRoute.Kind.SPEAKER -> "Phone speaker"
    OutputRoute.Kind.BLUETOOTH -> name.ifEmpty { "Bluetooth" }
    OutputRoute.Kind.LE_AUDIO -> name.ifEmpty { "LE Audio" }
    OutputRoute.Kind.USB -> name.ifEmpty { "USB audio" }
    OutputRoute.Kind.WIRED -> "Headphones"
    OutputRoute.Kind.REMOTE, OutputRoute.Kind.OTHER -> name.ifEmpty { "Other output" }
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
