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
import androidx.compose.foundation.combinedClickable
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
    val smart by model.smart.collectAsStateWithLifecycle()
    val songStats by model.stats.collectAsStateWithLifecycle()
    val stats by model.stats.collectAsStateWithLifecycle()
    val sortOrders by model.sortOrders.collectAsStateWithLifecycle()
    val lyricsState by model.localLyrics.collectAsStateWithLifecycle()
    val musicFolders by model.musicFolders.collectAsStateWithLifecycle()
    val lyricOffset by model.lyricOffset.collectAsStateWithLifecycle()
    var actionTrack by remember { mutableStateOf<Track?>(null) }
    var editingTags by remember { mutableStateOf<List<Track>?>(null) }
    var pendingTags by remember { mutableStateOf<Pair<List<Track>, Map<String, String>>?>(null) }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = pendingTags
        pendingTags = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) model.writeTags(pending.first, pending.second)
    }
    var equalizerOpen by rememberSaveable { mutableStateOf(false) }
    var playlistTracks by remember { mutableStateOf<List<Track>?>(null) }
    val playing by model.playing.collectAsStateWithLifecycle()
    val lyricOwner = playing.mediaId
    val currentArtwork = playing.mediaId?.let { library?.byId?.get(TrackId(it))?.artworkUri }
    LaunchedEffect(playing.connected, playing.mediaId, currentArtwork) {
        val id = playing.mediaId
        if (playing.connected && id != null && currentArtwork != null) model.updateCurrentArtwork(id, currentArtwork)
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val chooseMusicFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::addMusicFolder) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Library", style = MaterialTheme.typography.headlineMediumEmphasized) }, scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { equalizerOpen = true }, modifier = Modifier.semantics { contentDescription = "Audio" }) { Symbol(Glyph.TUNE) }
                    if (model.allowed) IconButton(onClick = model::rescanManually, enabled = !model.scanning,
                        modifier = Modifier.semantics { contentDescription = if (model.scanning) "Scanning" else "Rescan" }) { Symbol(Glyph.REFRESH) }
                },
            )
        },
        bottomBar = {
            Column {
                playing.error?.let { message ->
                    ErrorNotice(message) { if (playing.connected) model.toggle() else model.connectPlayer() }
                }
                playing.queueError?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = model::dismissQueueError) { Text("Dismiss") }
                    }
                }
                if (playing.hasTrack) MiniPlayer(playing, position = { model.positionMs }, onOpen = { expanded = true }, onToggle = model::toggle, onNext = model::next)
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
                currentId = playing.mediaId, onPlay = model::play, onShuffle = model::playShuffled,
                smart = smart, onSaveSmart = model::saveSmartPlaylist, onDeleteSmart = model::deleteSmartPlaylist,
                onEditTags = { editingTags = it },
                scanError = model.scanError, onRescan = model::refresh,
                onRetrySearch = model::retrySearch,
                personal = personal, personalActions = model.personalActions, sortOrders = sortOrders, onSortOrder = model::setSortOrder, onEnqueue = model::enqueue, stats = songStats,
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
            onEqualizer = { equalizerOpen = true },
            lyricsState = lyricsState, lyricOffset = lyricOffset, onLyricOffset = { offset -> lyricOwner?.let { model.setLyricOffset(it, offset) } },
            musicFolders = musicFolders.map { it.path }, onChooseMusicFolder = { chooseMusicFolder.launch(null) },
            onClearMusicFolders = model::clearMusicFolders, onReloadLyrics = model::reloadLyrics,
            onFindLyricsOnline = model::findLyricsOnline, onlineLyricsStatus = model.onlineLyricsStatus,
            favourite = playing.mediaId in personal.favouriteByTrackId,
            onFavourite = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { model.toggleFavourite(track) } },
            onShuffle = model::toggleShuffle, onRepeat = model::cycleRepeat,
            onQueuePlay = model::playQueueItem, onQueueMove = model::moveQueueItem, onQueueRemove = model::removeQueueItem,
            onMore = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { actionTrack = track } },
        )
    }
    if (equalizerOpen) EqualizerSheet(playing.equalizer, playing.connected, playing.equalizerError,
        onApply = model::setEqualizer, onDismiss = { equalizerOpen = false },
        loudness = playing.replayGain, onLoudness = model::setReplayGain)
    actionTrack?.let { track ->
        val saved = track.id.value in personal.favouriteByTrackId
        TrackActionsSheet(track, saved, rating = stats[track.key.value]?.rating ?: 0, onRate = { model.setRating(track, it) },
            plays = stats[track.key.value]?.plays ?: 0, onDismiss = { actionTrack = null }, actions = listOf(
            TrackAction(Glyph.PLAY_NEXT, "Play next") { model.enqueue(listOf(track), true) },
            TrackAction(Glyph.ADD_TO_QUEUE, "Add to queue") { model.enqueue(listOf(track), false) },
            TrackAction(Glyph.FAVOURITE, if (saved) "Remove from favourites" else "Add to favourites", filled = saved) { model.toggleFavourite(track) },
            TrackAction(Glyph.PLAYLIST_ADD, "Add to playlist") { playlistTracks = listOf(track) },
            TrackAction(Glyph.EDIT, "Edit tags") { editingTags = listOf(track) },
            TrackAction(Glyph.TUNE, "Equalizer") { equalizerOpen = true },
        ))
    }
    editingTags?.let { tracks ->
        TagEditorDialog(tracks, onDismiss = { editingTags = null }) { fields ->
            editingTags = null
            val uris = tracks.filter { '#' !in it.id.value }.map { Uri.parse(it.uri) }.distinct()
            if (uris.isNotEmpty()) {
                // Android asks the owner once for the whole batch; the files are written after "Allow".
                pendingTags = tracks to fields
                val request = android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
                writeRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
            }
        }
    }
    model.tagMessage?.let { message ->
        AlertDialog(onDismissRequest = model::dismissTagMessage, title = { Text("Tags") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissTagMessage) { Text("OK") } })
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
private fun NowPlaying(
    state: PlayerState, format: String?, position: () -> Long, onClose: () -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    onSleepTimer: (Int) -> Unit,
    onEqualizer: () -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit,
    onQueuePlay: (String) -> Unit, onQueueMove: (String, Int) -> Unit, onQueueRemove: (String) -> Unit,
    onMore: (() -> Unit)?,
    lyricsState: LocalLyricsState, lyricOffset: Long, onLyricOffset: (Long) -> Unit,
    musicFolders: List<String>, onChooseMusicFolder: () -> Unit, onClearMusicFolders: () -> Unit, onReloadLyrics: () -> Unit,
    onFindLyricsOnline: () -> Unit = {}, onlineLyricsStatus: String? = null,
    favourite: Boolean, onFavourite: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val monitor = LocalContext.current.graph.output
    LifecycleStartEffect(monitor) { monitor.start(); onStopOrDispose { monitor.stop() } }
    val route by monitor.route.collectAsStateWithLifecycle()
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    if (lyricsOpen) LyricsSheet(if (lyricsState.mediaId == state.mediaId) lyricsState.lyrics else ua.olex.music.lyrics.Lyrics.EMPTY,
        state, lyricOffset, position, onSeek, onLyricOffset, onDismiss = { lyricsOpen = false },
        source = if (lyricsState.mediaId == state.mediaId) lyricsState.source else "Loading local lyrics...",
        note = lyricsState.note.takeIf { lyricsState.mediaId == state.mediaId }, folders = musicFolders,
        onChooseFolder = onChooseMusicFolder, onClearFolders = onClearMusicFolders, onReload = onReloadLyrics,
        onFindOnline = onFindLyricsOnline, onlineStatus = onlineLyricsStatus)
    var timerDialog by rememberSaveable { mutableStateOf(false) }
    var queueSheet by rememberSaveable { mutableStateOf(false) }
    var audioPath by rememberSaveable { mutableStateOf(false) }
    var outputUnavailable by rememberSaveable { mutableStateOf(false) }
    val changeOutput: () -> Unit = { outputUnavailable = !monitor.showOutputSwitcher() }
    if (audioPath) AudioPathSheet(format, route, state.audio, onDismiss = { audioPath = false }, onOutput = changeOutput, onEqualizer = { audioPath = false; onEqualizer() })
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

/** Timer choices send a single command; there is no UI countdown or background polling loop. */
@Composable
internal fun SleepTimerDialog(active: Boolean, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Pause music after:")
                TextButton(onClick = { onSet(ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK) }, modifier = Modifier.fillMaxWidth()) { Text("When this song ends") }
                listOf(5, 15, 30, 60, 90).forEach { minutes ->
                    TextButton(onClick = { onSet(minutes) }, modifier = Modifier.fillMaxWidth()) { Text("$minutes minutes") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (active) TextButton(onClick = { onSet(0) }) { Text("Cancel timer") } },
    )
}

