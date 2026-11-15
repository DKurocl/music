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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
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
    var pendingTags by remember { mutableStateOf<Pair<List<Track>, TagEditRequest>?>(null) }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = pendingTags
        pendingTags = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) model.writeTags(pending.first, pending.second)
    }
    val context = LocalContext.current
    fun requestTagWrite(tracks: List<Track>, request: TagEditRequest) {
        val uris = tracks.filter { '#' !in it.id.value }.map { Uri.parse(it.uri) }.distinct()
        if (uris.isEmpty()) return
        // Android asks the owner once for the whole batch (or not at all with "manage media"); written after "Allow".
        pendingTags = tracks to request
        val pending = android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
        writeRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
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
    val activity = LocalActivity.current
    val chooseMusicFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::addMusicFolder) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // Three places (Home, Library, Search) and pages over Home (Stats, Duplicates): docs/DESIGN_SYSTEM.md.
    var place by rememberSaveable { mutableStateOf(Place.HOME) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var openInLibrary by remember { mutableStateOf<Pair<String, String>?>(null) }
    val duplicates by model.duplicates.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val haptics = rememberHaptics()
    fun go(to: Place) { if (to != place) haptics.tick(); if (place == Place.SEARCH && to != Place.SEARCH) model.setQuery(""); page = null; place = to }
    BackHandler(page != null) { page = null }
    BackHandler(page == null && place != Place.HOME && !expanded) { go(Place.HOME) }
    var trashing by remember { mutableStateOf<List<Track>?>(null) }
    val trashRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) { haptics.confirm(); model.refresh() }
        trashing = null
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSnackbar provides snackbar) {
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            if (place != Place.SEARCH || page != null) TopAppBar(
                title = { Text(page?.title ?: if (place == Place.HOME) greeting() else place.title, style = MaterialTheme.typography.headlineMediumEmphasized) }, scrollBehavior = scroll,
                navigationIcon = { if (page != null) IconButton(onClick = { page = null }, modifier = Modifier.semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) } },
                actions = {
                    if (place == Place.LIBRARY && page == null) IconButton(onClick = { go(Place.SEARCH) }, modifier = Modifier.semantics { contentDescription = "Search" }) { Symbol(Glyph.SEARCH) }
                    IconButton(onClick = { equalizerOpen = true }, modifier = Modifier.semantics { contentDescription = "Audio" }) { Symbol(Glyph.TUNE) }
                    if (model.allowed && place == Place.HOME) IconButton(onClick = model::rescanManually, enabled = !model.scanning,
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
                if (playing.hasTrack) MiniPlayer(playing, position = { model.positionMs }, onOpen = { expanded = true }, onToggle = model::toggle, onNext = model::next, onPrevious = model::previous)
                PlacesBar(place.takeIf { page == null }, ::go)
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
            page == Page.STATS -> StatsPage(requireNotNull(library), songStats, Modifier.padding(insets), model::play)
            page == Page.DUPLICATES -> DuplicatesPage(duplicates, playing.mediaId, Modifier.padding(insets), onPlay = model::play) { tracks ->
                val uris = tracks.filter { '#' !in it.id.value }.map { Uri.parse(it.uri) }
                if (uris.isNotEmpty()) {
                    trashing = tracks
                    val pending = android.provider.MediaStore.createTrashRequest(context.contentResolver, uris, true)
                    trashRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
                }
            }
            place == Place.HOME -> HomePage(requireNotNull(library), personal.history.mapNotNull { it.track }, songStats, smart, playing.mediaId,
                Modifier.padding(insets), onPlay = model::play, onShuffle = model::playShuffled,
                onOpenAlbum = { openInLibrary = "ALBUMS" to it.id; place = Place.LIBRARY },
                onOpenArtist = { openInLibrary = "ARTISTS" to it.id; place = Place.LIBRARY },
                onTrackActions = { actionTrack = it }, duplicates = duplicates.size,
                onStats = { page = Page.STATS }, onDuplicates = { page = Page.DUPLICATES })
            else -> LibraryBrowser(
                library = requireNotNull(library), insets = insets,
                query = if (place == Place.SEARCH) query else "", search = search, onQueryChange = model::setQuery,
                mode = if (place == Place.SEARCH) BrowserMode.SEARCH else BrowserMode.LIBRARY,
                open = openInLibrary, onOpened = { openInLibrary = null },
                recentSearches = model.recentSearches, onSearchUsed = model::addRecentSearch, onClearRecent = model::clearRecentSearches,
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
    }

    // The player follows the finger down from the top of its page and closes past a quarter of the
    // screen or on a quick flick; predictive back shrinks it toward the mini player.
    val dismiss = remember { androidx.compose.animation.core.Animatable(0f) }
    var backProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var pageHeight by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settle = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(expanded) { if (expanded) { dismiss.snapTo(0f); backProgress = 0f } }
    androidx.activity.compose.PredictiveBackHandler(expanded) { events ->
        try { events.collect { backProgress = it.progress }; haptics.threshold(); expanded = false }
        catch (e: kotlin.coroutines.cancellation.CancellationException) { backProgress = 0f; throw e }
    }
    val dragToClose = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < 0 && dismiss.value > 0f) { val used = maxOf(available.y, -dismiss.value); scope.launch { dismiss.snapTo(dismiss.value + used) }; return androidx.compose.ui.geometry.Offset(0f, used) }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y > 0 && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) { scope.launch { dismiss.snapTo(dismiss.value + available.y) }; return available.copy(x = 0f) }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (dismiss.value <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                if (dismiss.value > pageHeight * 0.25f || available.y > 2_500f) { haptics.threshold(); expanded = false }
                else dismiss.animateTo(0f, settle)
                return available
            }
        }
    }
    AnimatedVisibility(expanded && playing.hasTrack, enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it },
        exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it }) {
        Box(Modifier.fillMaxSize().onSizeChanged { pageHeight = it.height.coerceAtLeast(1) }.nestedScroll(dragToClose)
            .graphicsLayer {
                translationY = dismiss.value + backProgress * 120.dp.toPx()
                val s = 1f - backProgress * 0.08f; scaleX = s; scaleY = s
                shape = RoundedCornerShape((backProgress * 40 + (dismiss.value / pageHeight).coerceIn(0f, 1f) * 40).dp); clip = true
            }) {
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
            onSaveLyricTiming = model::saveLyricTiming, onResetLyricTiming = model::resetLyricTiming,
            favourite = playing.mediaId in personal.favouriteByTrackId,
            onFavourite = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { model.toggleFavourite(track) } },
            onShuffle = model::toggleShuffle, onRepeat = model::cycleRepeat,
            onQueuePlay = model::playQueueItem, onQueueMove = model::moveQueueItem, onQueueRemove = model::removeQueueItem,
            onMore = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { actionTrack = track } },
        )
        }
    }
    if (equalizerOpen) EqualizerSheet(playing.equalizer, playing.connected, playing.equalizerError,
        onApply = model::setEqualizer, onDismiss = { equalizerOpen = false },
        loudness = playing.replayGain, onLoudness = model::setReplayGain)
    actionTrack?.let { track ->
        val saved = track.id.value in personal.favouriteByTrackId
        TrackActionsSheet(track, saved, rating = stats[track.key.value]?.rating ?: 0, onRate = { stars ->
            model.setRating(track, stars)
            if (model.ratingsToFiles) requestTagWrite(listOf(track), TagEditRequest(mapOf("RATING" to if (stars == 0) "" else (stars * 20).toString()), quiet = true))
        }, ratingsToFiles = model.ratingsToFiles, onRatingsToFiles = model::changeRatingsToFiles,
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
        TagEditorDialog(tracks, onDismiss = { editingTags = null }) { request ->
            editingTags = null
            requestTagWrite(tracks, request)
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
    onSaveLyricTiming: ((ua.olex.music.lyrics.Lyrics) -> Unit)? = null, onResetLyricTiming: () -> Unit = {},
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
        onFindOnline = onFindLyricsOnline, onlineStatus = onlineLyricsStatus,
        onSaveTiming = onSaveLyricTiming, onResetTiming = if (lyricsState.edited && lyricsState.mediaId == state.mediaId) onResetLyricTiming else null)
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


/** The three places of the app, in the navigation bar. */
internal enum class Place(val title: String, val glyph: Glyph) { HOME("Home", Glyph.HOME), LIBRARY("Library", Glyph.LIBRARY), SEARCH("Search", Glyph.SEARCH) }

/** Pages opened over Home. */
internal enum class Page(val title: String) { STATS("Your listening"), DUPLICATES("Duplicates") }

/** The navigation bar: Home, Library, Search (M3E short navigation bar). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlacesBar(current: Place?, onGo: (Place) -> Unit) {
    androidx.compose.material3.ShortNavigationBar {
        Place.entries.forEach { p ->
            val selected = current == p
            androidx.compose.material3.ShortNavigationBarItem(
                selected = selected, onClick = { onGo(p) },
                icon = { Symbol(p.glyph, filled = selected, bold = selected) }, label = { Text(p.title) },
            )
        }
    }
}

/** Home's title: a greeting for the time of day, as Google's apps greet. */
internal fun greeting(hour: Int = java.time.LocalTime.now().hour) = when (hour) {
    in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Late night"
}
