@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.toShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
import kotlinx.coroutines.launch
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
    val library by model.catalogue.collectAsStateWithLifecycle()
    val tracks = library?.songs
    val query = model.searchText
    val personal by model.personal.collectAsStateWithLifecycle()
    val stats by model.stats.collectAsStateWithLifecycle()
    val musicFolders by model.musicFolders.collectAsStateWithLifecycle()
    val youTubeOn by LocalContext.current.graph.youTubeOn.collectAsStateWithLifecycle()
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
    // Lyrics found online go into the song's file as well, after Android asks once.
    model.lyricsForFile?.let { (track, text) ->
        LaunchedEffect(track.id, text) {
            model.lyricsForFileHandled()
            requestTagWrite(listOf(track), TagEditRequest(mapOf("LYRICS" to text), quiet = true))
        }
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
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val chooseMusicFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::addMusicFolder) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // Three places (Home, Library, Search) and pages over Home (Stats, Duplicates): docs/DESIGN_SYSTEM.md.
    var place by rememberSaveable { mutableStateOf(Place.HOME) }
    var statsBackCommitted by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var openPlaylist by rememberSaveable { mutableStateOf<Long?>(null) }
    var openInLibrary by remember { mutableStateOf<Pair<String, String>?>(null) }
    var collectionTitle by remember { mutableStateOf<String?>(null) }
    var openTile by remember { mutableStateOf<String?>(null) }
    var closeCollection by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val haptics = rememberHaptics()
    var focusSearch by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var searchVisit by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    // Where Back goes after something opened from elsewhere (an artist from Home): there, not to Search's tiles.
    var returnTo by rememberSaveable { mutableStateOf<Place?>(null) }
    val onlinePageScope by context.graph.youTubeAccount.cacheScope.collectAsStateWithLifecycle()
    val tabHistory = androidx.compose.runtime.key(onlinePageScope, youTubeOn) {
        rememberSaveable(saver = OnlineTabHistory.saver) { OnlineTabHistory() }
    }
    val onlineStack = tabHistory[place]
    // The screen under an online page keeps its state (scroll, open artist) while the page is over
    // it, so Back lands exactly where the owner was.
    val baseStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val onlineStates = androidx.compose.runtime.key(onlinePageScope, youTubeOn) {
        androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    }
    fun clearOnline(destination: Place) {
        for (depth in 1..tabHistory[destination].size) onlineStates.removeState("${destination.name}/$depth")
        tabHistory.clear(destination)
    }
    fun popOnline() {
        onlineStates.removeState("${place.name}/${tabHistory[place].size}")
        tabHistory.back(place)
    }
    // An online page's title moves into the top bar once its header has scrolled away.
    var onlineTitleShown by remember { mutableStateOf(false) }
    val onlineTop = onlineStack.lastOrNull()
    androidx.compose.runtime.LaunchedEffect(onlineTop) { onlineTitleShown = false }
    // YouTube Music's moods for Home; the usual set when it is off or unreachable (they still play
    // from the library by genre).
    // Both start from what was last seen (read off the main thread), so Home is whole at once and
    // only refreshes; the network answer replaces them quietly.
    val network by context.graph.network.collectAsStateWithLifecycle()
    val moods = androidx.compose.runtime.key(onlinePageScope, youTubeOn) { androidx.compose.runtime.produceState(YouTubeMusic.DEFAULT_MOODS, network) {
        if (!youTubeOn) return@produceState
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.graph.youTube.storedMoods() }?.let { value = it }
        if (network) context.graph.youTube.moods().takeIf { result -> result.any { it.params != null } }?.let { value = it }
    }.value }
    // The owner's YouTube Music home (their own once signed in); nothing while YouTube Music is off.
    val youTubeSignedIn by context.graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    // A different account (or none) never sees the last one's playlists, home or moods.
    androidx.compose.runtime.LaunchedEffect(youTubeSignedIn) { context.graph.youTube.forgetAccount() }
    val youTubeHome = androidx.compose.runtime.key(youTubeOn, onlinePageScope) { androidx.compose.runtime.produceState(emptyList<YouTubeMusic.Shelf>(), network) {
        if (!youTubeOn) { value = emptyList(); return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.graph.youTube.storedHome() }
        if (network) runCatching { context.graph.youTube.home() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { value = it }
    }.value }
    val homePlaylists = androidx.compose.runtime.key(onlinePageScope, youTubeOn) {
        androidx.compose.runtime.produceState(emptyList<YouTubeMusic.Card>(), network, place == Place.HOME) {
            if (!youTubeOn || onlinePageScope == "public" || place != Place.HOME) return@produceState
            try {
                value = context.graph.youTube.accountPlaylists(offline = true)
                if (network) value = context.graph.youTube.accountPlaylists()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the saved shelf on failure. The Playlists screen also offers a manual retry.
            }
        }.value
    }
    val homeCache = remember(onlinePageScope, youTubeOn) { HomeCache() }
    val excluded = model.excluded
    // A download asked for before the Soulseek account was set: it starts once the owner signs in.
    var afterSignIn by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun go(to: Place) {
        if (to == place || to == Place.SEARCH) clearOnline(to)
        returnTo = null
        if (to == Place.SEARCH) {
            val focus = place == Place.SEARCH && page == null
            baseStates.removeState("place-SEARCH-$searchVisit")
            searchVisit++
            model.setQuery("")
            openInLibrary = null
            openTile = null
            focusSearch = if (focus) focusSearch + 1 else 0
        }
        if (to != place) haptics.tick()
        collectionTitle = null
        page = null
        place = to
    }
    /** Opens the album or artist of a song in the Library, from the player or a song's actions. */
    val openScope = androidx.compose.runtime.rememberCoroutineScope()
    fun openOf(track: Track, artist: Boolean) = openScope.launch {
        val index = library ?: return@launch
        // Every album or artist is walked to find the song's: off the main thread, so a tap never stalls.
        val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (artist) index.artists.firstOrNull { a -> a.tracks.any { it.id == track.id } && a.name.equals(track.albumArtist ?: track.artist, true) }
                ?: index.artists.firstOrNull { a -> a.tracks.any { it.id == track.id } }
            else index.albums.firstOrNull { a -> a.tracks.any { it.id == track.id } }
        } ?: return@launch
        haptics.tick(); expanded = false; actionTrack = null; page = null
        if (place != Place.SEARCH) returnTo = place
        model.setQuery("")
        clearOnline(Place.SEARCH)
        if (artist && youTubeOn) tabHistory.open(Place.SEARCH, OnlineTarget.Artist(target.name))
        else openInLibrary = (if (artist) "ARTISTS" else "ALBUMS") to target.id
        place = Place.SEARCH
    }
    var trashing by remember { mutableStateOf<List<Track>?>(null) }
    val trashRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) { haptics.confirm(); model.refresh() }
        trashing = null
    }
    /**
     * Moves the owner's files to Android's trash (recoverable for 30 days in Files), after Android's
     * own confirmation. A song that is playing is skipped first, so nothing plays a vanishing file.
     */
    val moveToTrash: (List<Track>) -> Unit = { tracks ->
        val files = tracks.filter { '#' !in it.id.value && !YouTubeMusic.isOnline(it) && it.uri.startsWith("content://") }
        if (files.isNotEmpty()) {
            if (files.any { it.id.value == playing.mediaId }) model.next()
            trashing = files
            val pending = android.provider.MediaStore.createTrashRequest(context.contentResolver, files.map { Uri.parse(it.uri) }, true)
            trashRequest.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    // In an open collection the bar shows a back arrow, and the name once the header has scrolled away.
    val inCollection = page == null && place != Place.HOME && collectionTitle != null
    // YouTube Music pages (an artist, an album) open over the current place, one on another; Back closes the top one.
    var destinationBackProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var destinationBackDirection by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val backMotion = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var restoreBack by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val systemOpening = model.systemPlayerRequested
    androidx.activity.compose.BackHandler(systemOpening || expanded && !playing.hasTrack) {
        expanded = false
        model.dismissSystemPlayerRequest()
    }
    PredictiveBackHandler(!expanded && !systemOpening && (page != null || onlineStack.isNotEmpty() || place != Place.HOME)) { events ->
        restoreBack?.cancel()
        try {
            events.collect { event ->
                destinationBackDirection = if (event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f
                destinationBackProgress = event.progress
            }
            when {
                onlineStack.isNotEmpty() -> popOnline()
                page != null -> {
                    statsBackCommitted = page == Page.STATS && destinationBackProgress > 0f
                    page = if (page == Page.DUPLICATES || page == Page.LIBRARY_OVERVIEW) Page.SETTINGS else null
                }
                else -> go(Place.HOME)
            }
            destinationBackProgress = 0f
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            restoreBack = scope.launch {
                androidx.compose.animation.core.animate(destinationBackProgress, 0f, animationSpec = backMotion) { value, _ ->
                    destinationBackProgress = value.coerceIn(0f, 1f)
                }
            }
            throw cancelled
        }
    }
    val onlineNav = remember(model, tabHistory) {
        OnlineNav(
            open = { target ->
                expanded = false; actionTrack = null; page = null
                tabHistory.open(place, target)
            },
            openDownload = model::requestDownloads, play = model::playFrom,
            actions = { actionTrack = it },
            openLocalArtist = { artist -> model.setQuery(""); clearOnline(Place.SEARCH); returnTo = null; openInLibrary = "ARTISTS" to artist.id; place = Place.SEARCH },
            download = { start -> if (context.graph.soulseek.account == null) afterSignIn = start else start() },
            playMood = { mood, moodPage -> model.startMood(mood, moodPage) },
            playMix = { id, title -> model.startPlaylistRadio(id, title) },
            playTrackMix = model::playSelectionMix,
        )
    }
    val powerSave = rememberPowerSave()
    // The Search shortcut: Search with the field focused.
    androidx.compose.runtime.LaunchedEffect(model.searchRequest) { if (model.searchRequest > 0) { expanded = false; clearOnline(Place.SEARCH); page = null; place = Place.SEARCH; focusSearch++; model.consumeSearchRequest() } }
    androidx.compose.runtime.LaunchedEffect(model.playerRequest, playing.hasTrack) {
        if (model.playerRequest > 0 && playing.hasTrack) {
            expanded = true
            model.consumePlayerRequest()
        }
    }
    // Play left songs out (or found nothing to play) because the phone is offline: say so once.
    androidx.compose.runtime.LaunchedEffect(model.offlineNotice) {
        if (model.offlineNotice > 0) snackbar.showSnackbar("Offline: playing your files and saved songs only")
    }
    // After a crash: say so once and offer the report (the stack, the app and Android versions;
    // nothing personal) to share, then forget it.
    LaunchedEffect(Unit) {
        val file = java.io.File(context.filesDir, ua.olex.music.MusicApp.CRASH_FILE)
        val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (file.exists()) runCatching { file.readText() }.getOrNull().also { file.delete() } else null }
            ?: return@LaunchedEffect
        if (snackbar.showSnackbar("Music closed unexpectedly", actionLabel = "Share report", withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            runCatching {
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, report), "Share crash report"))
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSnackbar provides snackbar, LocalIsPlaying provides playing.isPlaying, LocalOnlineNav provides onlineNav,
        LocalEnqueue provides remember(model) { { track: Track, next: Boolean -> model.enqueue(listOf(track), next) } },
        LocalPlayingTrack provides playing.track,
        LocalOwnedSources provides if (model.allowed) personal.localCopies else emptyMap(),
        LocalNetwork provides network,
        LocalPowerSave provides powerSave) {
    afterSignIn?.let { pending ->
        SoulseekDialog(null, onDismiss = { afterSignIn = null }) { account ->
            context.graph.soulseek.account = account
            afterSignIn = null
            if (account != null) pending()
        }
    }
    val travel = remember(scope) { PlayerTravel(scope, initiallyOpen = expanded) }
    LifecycleStartEffect(travel) {
        travel.settle(expanded, instant = true)
        onStopOrDispose { travel.settle(expanded, instant = true) }
    }
    val directOpen = systemOpening && playing.hasTrack && playing.connected
    LaunchedEffect(directOpen) {
        if (directOpen) {
            travel.settle(open = true, instant = true)
            expanded = true
            model.dismissSystemPlayerRequest()
        }
    }
    val openPlayer = (expanded || directOpen) && playing.hasTrack
    // The screen stays open during a service reconnect. Only the user's expand/collapse changes travel.
    LaunchedEffect(expanded, powerSave) { travel.settle(expanded, powerSave) }
    var lastPlayerPreview by remember { mutableStateOf<ua.olex.music.playback.LastPlayedPreview?>(null) }
    if (playing.hasTrack) androidx.compose.runtime.SideEffect {
        lastPlayerPreview = ua.olex.music.playback.LastPlayedPreview(playing.title, playing.artist, playing.artworkUri)
    }
    val openingSurface = systemOpening && !directOpen || expanded && !playing.hasTrack
    // A control tapped while the service was still connecting runs as soon as the queue is there.
    var whenConnected by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(whenConnected, playing.connected, playing.hasTrack) {
        val action = whenConnected
        if (action != null && playing.connected && playing.hasTrack) { whenConnected = null; action() }
    }
    PlayerCoverLayout(travel, playing.artworkUri, directOpen = directOpen) {
    if (!openingSurface) Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection)
            .graphicsLayer { alpha = if (systemOpening) 0f else 1f }
            .then(if (systemOpening || expanded && playing.hasTrack) Modifier.clearAndSetSemantics {} else Modifier),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            if (onlineTop != null || place != Place.SEARCH || page != null) TopAppBar(
                title = {
                    // An online page (an album, a playlist, an artist, a mood) has the same bar as the
                    // library's pages: Back, and its name once the header is out of sight. Never Home's greeting.
                    if (onlineTop != null) androidx.compose.animation.AnimatedVisibility(onlineTitleShown,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 }) {
                        Text(when (onlineTop) { is OnlineTarget.Album -> onlineTop.title; is OnlineTarget.Artist -> onlineTop.name },
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else if (inCollection) Text(collectionTitle.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (place == Place.HOME && (page == null || page == Page.STATS)) {
                        androidx.compose.animation.Crossfade(page == Page.STATS,
                            animationSpec = if (powerSave) androidx.compose.animation.core.snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
                            label = "Listening title") { listening ->
                            Text(if (listening) Page.STATS.title else Place.HOME.title, style = MaterialTheme.typography.headlineMediumEmphasized)
                        }
                    } else Text(page?.title ?: place.title, style = MaterialTheme.typography.headlineMediumEmphasized)
                }, scrollBehavior = scroll,
                colors = if (onlineTop == null && place == Place.HOME && page == null) {
                    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface)
                } else TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    if (onlineTop != null) KickIconButton(onClick = { popOnline() }, modifier = Modifier.semantics { contentDescription = "Back" }) { k -> Symbol(Glyph.BACK, kick = k) }
                    else if (page != null) KickIconButton(onClick = { page = if (page == Page.DUPLICATES || page == Page.LIBRARY_OVERVIEW) Page.SETTINGS else null }, modifier = Modifier.semantics { contentDescription = "Back" }) { k -> Symbol(Glyph.BACK, kick = k) }
                    else if (inCollection) KickIconButton(onClick = { closeCollection++ }, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { k -> Symbol(Glyph.BACK, kick = k) }
                },
                actions = {
                    // Settings as a round tonal button, where Google's apps keep the account circle: it belongs there, not squeezed in.
                    if (onlineTop == null && place == Place.HOME && page == null) {
                        HomeActions(onStats = { statsBackCommitted = false; page = Page.STATS },
                            onSettings = { settingsQuery = ""; page = Page.SETTINGS })
                    }
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
                PlacesBar(place.takeIf { page == null }, ::go)
            }
        },
    ) { insets ->
        val list = tracks
        // The mini player floats over the page: the page runs under it to the navigation bar, with a
        // soft fade (not a solid band) so it reads as floating, as Google's own players do.
        val playerInset = if (playing.hasTrack) 84.dp else 0.dp
        // Only the page follows Back; the floating mini player stays where it is.
        Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val back = if (page == Page.STATS) 0f else destinationBackProgress
            translationX = destinationBackDirection * 24.dp.toPx() * back
            scaleX = 1f - 0.04f * back
            scaleY = scaleX
            if (back > 0f) {
                shape = RoundedCornerShape(24.dp * back)
                clip = true
            } else clip = false
        }) {
        CompositionLocalProvider(LocalPlayerInset provides playerInset) {
        val askForFiles: () -> Unit = {
            if (permissionRequested && activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO) == false) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            } else {
                permissionRequested = true
                ask.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        // Without access to the files, YouTube Music still works: the permission opens the owner's own
        // files, it doesn't lock the app. With YouTube Music off there is nothing to show but the ask.
        val withoutFiles = !model.allowed && youTubeOn
        val library = library ?: if (place == Place.SEARCH || withoutFiles || (youTubeOn && model.scanError != null))
            ua.olex.music.model.LibraryIndex.EMPTY else null
        // Where the owner is: a new place fades through, a page opened from it slides in from the side.
        val route = when {
            onlineStack.isNotEmpty() -> "online:${onlineStack.size}:${onlineStack.last()}"
            page == Page.STATS -> "place:${Place.HOME.name}"
            page != null -> "page:${page!!.name}"
            else -> "place:${place.name}"
        }
        Arrival(route, place, depth = onlineStack.size + if (page != null && page != Page.STATS) 1 else 0) {
        when {
            !model.allowed && !youTubeOn && page != Page.SETTINGS -> Notice(insets, "Your music", "Allow access to the audio files on this phone to play them.", "Allow", askForFiles)
            model.scanError != null && list.isNullOrEmpty() && !youTubeOn && page != Page.SETTINGS -> Notice(insets, "Couldn't scan music", model.scanError.orEmpty(), "Retry", model::refresh)
            // Home's own shape while the library loads: the first real frame then lands in place.
            library == null && page != Page.SETTINGS -> HomeSkeleton(Modifier.padding(insets))
            onlineStack.isNotEmpty() -> when (val top = onlineStack.last()) {
                // Each page in the stack keeps its own scroll, so Back to a previous online page lands where it was.
                is OnlineTarget.Artist -> onlineStates.SaveableStateProvider("${place.name}/${onlineStack.size}") { OnlineArtistPage(top, requireNotNull(library), playing.mediaId, insets) { onlineTitleShown = it } }
                is OnlineTarget.Album -> onlineStates.SaveableStateProvider("${place.name}/${onlineStack.size}") { OnlineAlbumPage(top, requireNotNull(library), playing.mediaId, insets) { onlineTitleShown = it } }
            }
            page == Page.SETTINGS -> SettingsPage(Modifier.padding(insets), musicFolders.map { it.path }, model.scanning, model.ratingsToFiles,
                onRatingsToFiles = model::changeRatingsToFiles, onEqualizer = { equalizerOpen = true }, onRescan = model::rescanManually,
                onAddFolder = { chooseMusicFolder.launch(null) }, onClearFolders = model::clearMusicFolders, onDownloads = { model.requestDownloads(0) }, duplicateCount = model.duplicates.collectAsStateWithLifecycle().value.size, onDuplicates = { page = Page.DUPLICATES },
                onLibraryOverview = { page = Page.LIBRARY_OVERVIEW }, initialQuery = settingsQuery)
            page == Page.LIBRARY_OVERVIEW -> LibraryOverview(list.orEmpty(), Modifier.padding(insets),
                onDuplicates = { page = Page.DUPLICATES }, onStorage = { settingsQuery = "cache"; page = Page.SETTINGS }, onTrash = moveToTrash)
            page == Page.DUPLICATES -> DuplicatesPage(model.duplicates.collectAsStateWithLifecycle().value, playing.mediaId, Modifier.padding(insets), onPlay = model::play) { tracks ->
                moveToTrash(tracks)
            }
            place == Place.HOME -> HomeListeningTransition(page == Page.STATS, statsBackCommitted, Modifier.padding(insets),
                backProgress = { if (powerSave) 0f else destinationBackProgress * destinationBackDirection }) { listening ->
                if (listening) StatsPage(requireNotNull(library), Modifier, model::play, context.graph.userLibrary::historySince)
                else baseStates.SaveableStateProvider("place-HOME") { HomePage(requireNotNull(library),
                    remember(personal.history) { personal.history.mapNotNull { it.track } }, stats, playing.mediaId, Modifier,
                    onPlay = model::playFrom, onTrackActions = { actionTrack = it }, favourites = remember(personal.favourites) { personal.favourites.mapNotNull { it.track } },
                    playlists = personal.playlists, accountPlaylists = homePlaylists, onShuffle = model::playShuffled,
                    homeActive = page == null,
                    onOpenAlbum = { album ->
                        clearOnline(Place.SEARCH); model.setQuery(""); returnTo = Place.HOME
                        openInLibrary = "ALBUMS" to album.id; place = Place.SEARCH
                    },
                    onOpenPlaylist = { id -> clearOnline(Place.LIBRARY); go(Place.LIBRARY); returnTo = Place.HOME; openPlaylist = id },
                    onAllPlaylists = { clearOnline(Place.LIBRARY); go(Place.LIBRARY) },
                    moods = moods, onAllowFiles = askForFiles.takeIf { withoutFiles }, scanError = model.scanError, onScanRetry = model::refresh,
                    youTubeHome = youTubeHome, excluded = excluded, onlineEnabled = youTubeOn, cache = homeCache) }
            }
            else -> baseStates.SaveableStateProvider(if (place == Place.SEARCH) "place-SEARCH-$searchVisit" else "place-${place.name}") { LibraryBrowser(
                library = requireNotNull(library), insets = insets,
                query = if (place == Place.SEARCH) query else "",
                search = model.search.collectAsStateWithLifecycle().value, onQueryChange = model::setQuery,
                mode = if (place == Place.SEARCH) BrowserMode.SEARCH else BrowserMode.LIBRARY,
                open = openInLibrary, onOpened = { openInLibrary = null; openTile = null },
                onLeave = returnTo?.let { back -> { returnTo = null; collectionTitle = null; place = back } },
                online = model.online.collectAsStateWithLifecycle().value, onlineOn = youTubeOn,
                suggestions = model.suggestions.collectAsStateWithLifecycle().value,
                onCollection = { collectionTitle = it }, closeCollection = closeCollection, onCollectionClosed = { closeCollection = 0 },
                focusSearch = if (place == Place.SEARCH) focusSearch else 0, onSearchFocused = { focusSearch = 0 }, openTile = openTile.takeIf { place == Place.SEARCH },
                recentSearches = model.recentSearches, onSearchUsed = model::addRecentSearch, onClearRecent = model::clearRecentSearches,
                currentId = playing.mediaId, onPlay = model::play, onShuffle = model::playShuffled,
                smart = model.smart.collectAsStateWithLifecycle().value, onSaveSmart = model::saveSmartPlaylist, onDeleteSmart = model::deleteSmartPlaylist,
                onEditTags = { editingTags = it }, onTrash = moveToTrash,
                scanError = model.scanError, onRescan = model::refresh,
                onRetrySearch = model::retrySearch,
                openPlaylist = openPlaylist.takeIf { place == Place.LIBRARY }, onOpenedPlaylist = { openPlaylist = null }, onPlayWithOrigin = model::playFrom,
                personal = personal, personalActions = model.personalActions, sortOrders = model.sortOrders.collectAsStateWithLifecycle().value, onSortOrder = model::setSortOrder, onEnqueue = model::enqueue, stats = stats,
                onTrackActions = { actionTrack = it }, onAddToPlaylist = { playlistTracks = it },
            ) }
        }
        }
        }
        }
        if (playing.hasTrack) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding()).fillMaxWidth()) {
            MiniPlayer(playing, position = { model.positionMs }, onOpen = { expanded = true }, onToggle = model::toggle, onNext = model::next, onPrevious = model::previous, covered = expanded)
        }
        }
    }

    // The player follows the finger down from the top of its page and closes past a quarter of the
    // screen or on a quick flick; predictive back shrinks it toward the mini player.
    var pageHeight by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val latestPowerSave by androidx.compose.runtime.rememberUpdatedState(powerSave)
    androidx.activity.compose.PredictiveBackHandler(openPlayer && !systemOpening) { events ->
        val start = travel.fraction
        try {
            events.collect { travel.drag(start * (1f - it.progress) - travel.fraction) }
            haptics.threshold()
            expanded = false
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            travel.settle(true, latestPowerSave)
            throw e
        }
    }
    val dragToClose = remember(travel) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var dragging = false
            private fun move(y: Float): androidx.compose.ui.geometry.Offset {
                val before = travel.fraction
                travel.drag(-y / pageHeight)
                dragging = true
                return androidx.compose.ui.geometry.Offset(0f, (before - travel.fraction) * pageHeight)
            }
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
                if (dragging && available.y < 0 && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) move(available.y)
                else androidx.compose.ui.geometry.Offset.Zero
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
                if (expanded && available.y > 0 && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) move(available.y)
                else androidx.compose.ui.geometry.Offset.Zero
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!dragging) return androidx.compose.ui.unit.Velocity.Zero
                dragging = false
                if (travel.fraction < 0.75f || available.y > 2500f) { haptics.threshold(); expanded = false }
                else travel.settle(true, latestPowerSave)
                return androidx.compose.ui.unit.Velocity(0f, available.y)
            }
        }
    }
    val travelling by remember { androidx.compose.runtime.derivedStateOf { travel.fraction > 0f } }
    if (openPlayer || playing.hasTrack && travelling) {
        val settled by remember { androidx.compose.runtime.derivedStateOf { travel.fraction == 1f } }
        var videoReady by remember { mutableStateOf(false) }
        LaunchedEffect(settled) { if (settled) videoReady = true }
        Box(Modifier.fillMaxSize().then(if (!openPlayer) Modifier.clearAndSetSemantics {} else Modifier)
            .onSizeChanged { pageHeight = it.height.coerceAtLeast(1) }.playerTransform(travel).nestedScroll(dragToClose)) {
        val motionCover by model.motionCover.collectAsStateWithLifecycle()
        val onlineAlbumId = rememberOnlineAlbumId(playing.mediaId, onlinePageScope)
        val rawOrigin = playing.playbackContext ?: model.playingFrom
        val origin = PlaybackOrigin.decode(rawOrigin)
        val openOrigin: (() -> Unit)? = origin?.let { source ->
            when (source.kind) {
                "playlist" -> source.id.toLongOrNull()?.takeIf { id -> personal.playlists.any { it.id == id } }?.let { id ->
                    { clearOnline(Place.LIBRARY); go(Place.LIBRARY); openPlaylist = id; expanded = false }
                }
                "remote-playlist", "album" -> ({ onlineNav.open(OnlineTarget.Album(source.id, source.title, playlist = source.kind == "remote-playlist")) })
                "artist" -> ({ onlineNav.open(OnlineTarget.Artist(source.title, id = source.id)) })
                else -> null
            }
        }
        val currentTrack = model.currentTrack(playing)
        val input = playing.inputFormat
        val file = currentTrack?.format
        val format = remember(file, input) {
            (file ?: input)?.let { reported ->
                val matching = input?.takeIf { it.codec.equals(reported.codec, ignoreCase = true) }
                val source = reported.copy(sampleRateHz = reported.sampleRateHz ?: matching?.sampleRateHz, bitrateKbps = null)
                listOfNotNull(source.label, source.sampleRateHz?.takeUnless { source.lossless }?.let(::formatKhz),
                    (reported.bitrateKbps ?: matching?.bitrateKbps)?.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ")
            }
        }
        NowPlaying(
            playing, format = format,
            from = origin?.title ?: model.mixLabel ?: rawOrigin,
            onOpenOrigin = openOrigin,
            originIsMix = model.mixLabel != null || origin?.kind == "mix",
            position = { model.positionMs }, onClose = { expanded = false },
            motionCover = motionCover?.takeIf { videoReady && it.owner == currentTrack?.let(::motionCoverOwner) }?.video,
            onToggle = model::toggle, onNext = model::next, onPrevious = model::previous, onSeek = model::seekTo,
            onSleepTimer = model::setSleepTimer, onExtendSleep = model::extendSleepTimer,
            onClearNext = model::clearUpNext,
            // The queue as songs (files from the library, YouTube Music songs as streams), for the playlist picker.
            onSaveQueue = {
                playlistTracks = playing.queue.mapNotNull { item ->
                    library?.byId?.get(TrackId(item.mediaId)) ?: if (item.mediaId.startsWith("yt:")) ua.olex.music.model.Track(TrackId(item.mediaId),
                        "${ua.olex.music.playback.OnlineStreams.SCHEME}://${item.mediaId.removePrefix("yt:")}", item.title, item.artist, artworkUri = item.artworkUri) else null
                }.takeIf { it.isNotEmpty() }
            },
            onEqualizer = { equalizerOpen = true }, onPlaybackParameters = model::setPlaybackParameters,
            lyricsState = model.localLyrics.collectAsStateWithLifecycle().value, lyricOffset = model.lyricOffset.collectAsStateWithLifecycle().value, onLyricOffset = { offset -> lyricOwner?.let { model.setLyricOffset(it, offset) } },
            musicFolders = musicFolders.map { it.path }, onChooseMusicFolder = { chooseMusicFolder.launch(null) },
            onClearMusicFolders = model::clearMusicFolders, onReloadLyrics = model::reloadLyrics,
            onFindLyricsOnline = model::findLyricsOnline, onlineLyricsStatus = model.onlineLyricsStatus,
            // Timing belongs to the recording, including a streamed source.
            onSaveLyricTiming = model::saveLyricTiming, onResetLyricTiming = model::resetLyricTiming,
            favourite = (currentTrack?.recordingId ?: playing.mediaId) in personal.favouriteByTrackId,
            // The heart works the same for a file and a YouTube Music song.
            onFavourite = currentTrack?.let { track -> { model.toggleFavourite(track) } },
            onShuffle = model::toggleShuffle, onRepeat = model::cycleRepeat,
            onQueuePlay = model::playQueueItem, onQueueMove = model::moveQueueItem, onQueueRemove = model::removeQueueItem,
            onMore = currentTrack?.let { track -> { actionTrack = track } },
            mixFrom = origin?.takeIf { it.kind == "mix" }?.title ?: model.mixLabel,
            onOpenAlbum = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { openOf(track, artist = false) } }
                // A YouTube Music song: its album page, when its row named one.
                ?: onlineAlbumId?.let { albumId -> { onlineNav.open(OnlineTarget.Album(albumId, playing.album ?: playing.title, cover = playing.artworkUri, seed = currentTrack)) } },
            onOpenArtist = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { openOf(track, artist = true) } }
                ?: playing.artist.substringBefore(",").trim().takeIf { it.isNotEmpty() && playing.mediaId?.startsWith("yt:") == true }
                    ?.let { artist -> { onlineNav.open(currentTrack?.let(::onlineArtistTarget) ?: OnlineTarget.Artist(artist)) } },
            fileRateHz = file?.sampleRateHz ?: input?.sampleRateHz,
        )
        }

    }
    if (openingSurface) {
        val preview by androidx.compose.runtime.produceState(lastPlayerPreview) {
            if (value == null) value = model.lastPlayedPreview()
        }
        // The restore screen never waits on a lost connection: it asks the service again every
        // 1.5 s (a few times), so a player that is already playing shows its real controls.
        LaunchedEffect(Unit) {
            repeat(4) {
                kotlinx.coroutines.delay(1_500)
                if (!playing.connected || !playing.hasTrack) model.connectPlayer()
            }
        }
        PlayerOpening(preview, empty = playing.connected && playing.queueRestored && !playing.hasTrack,
            error = playing.error.takeIf { !playing.connected && !playing.connecting },
            onClose = { expanded = false; model.dismissSystemPlayerRequest() }, onRetry = model::connectPlayer,
            onPlay = { whenConnected = { if (!playing.playWhenReady) model.toggle() }; model.connectPlayer() },
            onNext = { whenConnected = { model.next() }; model.connectPlayer() },
            onPrevious = { whenConnected = { model.previous() }; model.connectPlayer() })
    }
    // Only when a mix takes a moment; it drops in from the top and leaves the same way.
    val mixWaiting = rememberPatient(model.mixLoading && !systemOpening)
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        androidx.compose.animation.AnimatedVisibility(mixWaiting,
            enter = if (powerSave) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                androidx.compose.animation.slideInVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it / 2 },
            exit = if (powerSave) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                androidx.compose.animation.slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it / 2 }) {
        androidx.compose.material3.Surface(Modifier.padding(top = 68.dp), shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!powerSave) androidx.compose.material3.LoadingIndicator(Modifier.size(24.dp))
                Text("Preparing mix…", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        }
    }
    } // PlayerCoverLayout
    } // App composition locals
    model.downloadRequest?.let { requested ->
        DownloadsSheet(context.graph.soulseek, requested, onDismiss = model::dismissDownloads)
    }
    var videoTrack by remember { mutableStateOf<Track?>(null) }
    videoTrack?.let { track -> MusicVideoSheet(track) { videoTrack = null } }
    if (equalizerOpen) EqualizerSheet(playing.equalizer, playing.connected, playing.equalizerError,
        onApply = model::setEqualizer, onDismiss = { equalizerOpen = false },
        loudness = playing.replayGain, onLoudness = model::setReplayGain)
    actionTrack?.let { track ->
        val online = YouTubeMusic.isOnline(track)
        val saved = (track.recordingId ?: track.id.value) in personal.favouriteByTrackId
        val onlineAlbumId = rememberOnlineAlbumId(track.id.value, onlinePageScope)
        TrackActionsSheet(track, saved, rating = stats[track.statsKey]?.rating ?: 0, onRate = { stars ->
            model.setRating(track, stars)
            if (model.ratingsToFiles) requestTagWrite(listOf(track), TagEditRequest(mapOf("RATING" to if (stars == 0) "" else (stars * 20).toString()), quiet = true))
        },
            plays = stats[track.statsKey]?.plays ?: 0, onDismiss = { actionTrack = null }, actions = listOfNotNull(
            // A mix grown from this song, as YouTube Music's "Start radio", local files first.
            TrackAction(Glyph.RADIO, "Start mix") { haptics.spin(); model.startMix(track) },
            TrackAction(Glyph.PLAY_NEXT, "Play next") { model.enqueue(listOf(track), true) },
            // Never offered again in mixes, autoplay and Home; out of the queue now.
            if (online) TrackAction(Glyph.HIDDEN, "Don't recommend this song") {
                model.dontRecommend(track); scope.launch { snackbar.showSnackbar("${track.title} won't be recommended again") }
            } else null,
            // An online song's album and artist open their pages (the owner's own artist page when he has them).
            if (online) onlineAlbumId?.let { id -> TrackAction(Glyph.ALBUM, "Go to album") { actionTrack = null; onlineNav.open(OnlineTarget.Album(id, track.album ?: track.title, cover = track.artworkUri, seed = track)) } }
                else TrackAction(Glyph.ALBUM, "Go to album") { openOf(track, artist = false) },
            if (online) TrackAction(Glyph.PERSON, "Go to artist") { actionTrack = null; onlineNav.open(onlineArtistTarget(track)) }
                else TrackAction(Glyph.PERSON, "Go to artist") { openOf(track, artist = true) },
            // A song from YouTube Music, kept for good: Soulseek finds a lossless copy and the library plays it from then on.
            if (online) {
                val artist = track.artist.substringBefore(",").trim()
                val jobs by context.graph.soulseek.jobs.collectAsStateWithLifecycle()
                val downloadKey = ua.olex.music.soulseek.SoulseekDownloads.songKey(artist, track.title)
                val job = jobs.lastOrNull { it.key == downloadKey }
                when {
                    job?.running == true || job?.done == true -> TrackAction(Glyph.DOWNLOAD, "View download") { job?.let { model.requestDownloads(it.id) } }
                    job != null && (job.failed || job.cancelled) -> TrackAction(Glyph.REFRESH, "Try the download again") {
                        context.graph.soulseek.retry(job); scope.launch { snackbar.showSnackbar("Looking on Soulseek again") }
                    }
                    else -> TrackAction(Glyph.DOWNLOAD, "Find a lossless copy") {
                        onlineNav.download {
                            context.graph.soulseek.song(artist, track.title, track.album, track.durationMs)
                            scope.launch { snackbar.showSnackbar("Looking for a lossless copy on Soulseek") }
                        }
                    }
                }
            } else null,
            TrackAction(Glyph.ADD_TO_QUEUE, "Add to queue") { model.enqueue(listOf(track), false) },
            TrackAction(Glyph.FAVOURITE, if (saved) "Remove from favourites" else "Add to favourites", filled = saved) { model.toggleFavourite(track) },
            TrackAction(Glyph.PLAYLIST_ADD, "Add to playlist") { playlistTracks = listOf(track) },
            if (online) null else TrackAction(Glyph.EDIT, "Edit tags") { editingTags = listOf(track) },
            if (online || '#' in track.id.value) null else TrackAction(Glyph.DELETE, "Move to trash") { moveToTrash(listOf(track)) },
            if (youTubeOn && network) TrackAction(Glyph.VIDEO, "Find music video") { actionTrack = null; videoTrack = track } else null,
        ))
    }
    editingTags?.let { tracks ->
        TagEditorDialog(tracks, onDismiss = { editingTags = null }) { request ->
            editingTags = null
            requestTagWrite(tracks, request)
        }
    }
    model.tagMessage?.let { message ->
        AppDialog(onDismissRequest = model::dismissTagMessage, title = { Text("Tags") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissTagMessage) { Text("OK") } })
    }
    playlistTracks?.let { PlaylistPicker(personal.playlists, it, model.personalActions) { playlistTracks = null } }
    playing.queueError?.let { message ->
        AppDialog(onDismissRequest = model::dismissQueueError, title = { Text("Couldn't update queue") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissQueueError) { Text("OK") } })
    }
    model.mixFailure?.let { failure ->
        AppDialog(onDismissRequest = model::dismissMixFailure, title = { Text("Couldn't start mix") },
            text = { Text(failure.message) }, dismissButton = { TextButton(onClick = model::dismissMixFailure) { Text("Close") } },
            confirmButton = { TextButton(onClick = failure.retry) { Text("Try again") } })
    }
    model.userError?.let { message ->
        AppDialog(onDismissRequest = model::dismissUserError, title = { Text("Couldn't save changes") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissUserError) { Text("OK") } })
    }
}

@Composable
internal fun ErrorNotice(message: String, onRetry: () -> Unit) {
    // A quiet tonal card, as Google's apps show a failed section: the reason, and Retry beside it.
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(Glyph.CLOUD, size = 20.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry) { Text("Retry", maxLines = 1) }
        }
    }
}

@Composable
internal fun Notice(insets: PaddingValues, title: String, body: String, action: String?, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
        EmptyState(if (action != null) Glyph.LIBRARY else Glyph.NOTE, title, body, loading = action == null) {
            if (action != null) Button(onClick = onAction, modifier = Modifier.height(52.dp)) { Text(action) }
        }
    }
}

@Composable
private fun NowPlaying(
    state: PlayerState, format: String?, position: () -> Long, onClose: () -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    onSleepTimer: (Int, Boolean) -> Unit, from: String? = null, onExtendSleep: ((Long) -> Unit)? = null,
    onSaveQueue: (() -> Unit)? = null, onClearNext: (() -> Unit)? = null,
    onEqualizer: () -> Unit, onPlaybackParameters: (Float, Float) -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit,
    onQueuePlay: (String) -> Unit, onQueueMove: (String, Int) -> Unit, onQueueRemove: (String) -> Unit,
    onMore: (() -> Unit)?,
    mixFrom: String? = null,
    lyricsState: LocalLyricsState, lyricOffset: Long, onLyricOffset: (Long) -> Unit,
    musicFolders: List<String>, onChooseMusicFolder: () -> Unit, onClearMusicFolders: () -> Unit, onReloadLyrics: () -> Unit,
    onFindLyricsOnline: () -> Unit = {}, onlineLyricsStatus: String? = null,
    onSaveLyricTiming: ((ua.olex.music.lyrics.Lyrics) -> Unit)? = null, onResetLyricTiming: () -> Unit = {},
    favourite: Boolean, onFavourite: (() -> Unit)?,
    onOpenOrigin: (() -> Unit)? = null, originIsMix: Boolean = false,
    onOpenAlbum: (() -> Unit)? = null, onOpenArtist: (() -> Unit)? = null, fileRateHz: Int? = null, motionCover: MotionArtwork? = null,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val monitor = context.graph.output
    LifecycleStartEffect(monitor) { monitor.start(); onStopOrDispose { monitor.stop() } }
    val route by monitor.route.collectAsStateWithLifecycle()
    val lyricsPrefs = remember { context.getSharedPreferences("lyrics_display", android.content.Context.MODE_PRIVATE) }
    var lyricsScale by remember { androidx.compose.runtime.mutableFloatStateOf(lyricsPrefs.getFloat("text_scale", 1f)) }
    var lyricsSynced by remember { mutableStateOf(lyricsPrefs.getBoolean("synced", true)) }
    var lyricsRomanized by remember { mutableStateOf(lyricsPrefs.getBoolean("romanized", false)) }
    var lyricsTranslated by remember { mutableStateOf(lyricsPrefs.getBoolean("translated", false)) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    // A song without lyrics: with the player open, they are looked up online once, by themselves
    // (word-timed first), instead of waiting for the owner to find the button.
    val asked = remember { mutableSetOf<String>() }
    val online = LocalNetwork.current
    LaunchedEffect(state.mediaId, lyricsState.mediaId, lyricsState.lyrics.lines.isEmpty(), online) {
        val id = state.mediaId ?: return@LaunchedEffect
        if (online && lyricsState.mediaId == id && lyricsState.lyrics.lines.isEmpty() && asked.add(id)) onFindLyricsOnline()
    }
    if (lyricsOpen) LyricsSheet(if (lyricsState.mediaId == state.mediaId) lyricsState.lyrics else ua.olex.music.lyrics.Lyrics.EMPTY,
        state, lyricOffset, position, onSeek, onLyricOffset, onDismiss = { lyricsOpen = false },
        source = if (lyricsState.mediaId == state.mediaId) lyricsState.source else "Loading local lyrics...",
        note = lyricsState.note.takeIf { lyricsState.mediaId == state.mediaId }, folders = musicFolders,
        onChooseFolder = onChooseMusicFolder, onClearFolders = onClearMusicFolders, onReload = onReloadLyrics,
        onFindOnline = onFindLyricsOnline, onlineStatus = onlineLyricsStatus,
        textScale = lyricsScale, onTextScale = { lyricsScale = it; lyricsPrefs.edit().putFloat("text_scale", it).apply() },
        synced = lyricsSynced, onSynced = { lyricsSynced = it; lyricsPrefs.edit().putBoolean("synced", it).apply() },
        romanized = lyricsRomanized, onRomanized = { lyricsRomanized = it; lyricsPrefs.edit().putBoolean("romanized", it).apply() },
        translated = lyricsTranslated, onTranslated = { lyricsTranslated = it; lyricsPrefs.edit().putBoolean("translated", it).apply() },
        onSaveTiming = onSaveLyricTiming, onResetTiming = if (lyricsState.edited && lyricsState.mediaId == state.mediaId) onResetLyricTiming else null)
    var timerDialog by rememberSaveable { mutableStateOf(false) }
    var queueSheet by rememberSaveable { mutableStateOf(false) }
    var audioPath by rememberSaveable { mutableStateOf(false) }
    var devicesOpen by rememberSaveable { mutableStateOf(false) }
    // Straight to Android's own output picker, as Google's apps do; the app's sheet only when the
    // system can't show it.
    val changeOutput: () -> Unit = {
        audioPath = false
        if (!runCatching { monitor.showOutputSwitcher() }.getOrDefault(false)) devicesOpen = true
    }
    if (devicesOpen) PlaybackDevices(route, monitor::showOutputSwitcher) { devicesOpen = false }
    if (audioPath) AudioPathSheet(format, route, state.audio, onDismiss = { audioPath = false }, onOutput = changeOutput, onEqualizer = { audioPath = false; onEqualizer() },
        sourceLabel = if (state.track?.uri?.startsWith("ytm://") == true || state.mediaId?.startsWith("yt:") == true)
            "YouTube Music input" else "File input",
        fileRateHz = fileRateHz, processing = listOfNotNull(
            state.equalizer.settings.takeIf { it.enabled }?.let { "Equalizer: ${it.profile}" },
            state.equalizer.settings.channels.takeIf { !it.neutral }?.let { c -> listOfNotNull(if (c.mono) "mono" else null, if (c.swap) "left/right swapped" else null,
                if (c.balance != 0f) "balance ${(c.balance * 100).toInt()}%" else null).joinToString(", ").replaceFirstChar(Char::uppercase) },
            state.replayGain.mode.takeIf { it != ua.olex.music.dsp.ReplayGainMode.OFF }?.let { "ReplayGain: ${it.name.lowercase()}" },
        ), controls = { TempoAndPitch(state, onPlaybackParameters) })
    if (queueSheet) QueueSheet(state, onDismiss = { queueSheet = false }, onPlay = onQueuePlay, onMove = onQueueMove, onRemove = onQueueRemove, mixFrom = mixFrom,
        onSave = onSaveQueue?.let { save -> { queueSheet = false; save() } }, onClearNext = onClearNext)
    if (timerDialog) {
        SleepTimerDialog(
            deadline = state.sleepDeadlineMs,
            durationMs = state.sleepDurationMs,
            finishingTrack = state.sleepFinishesTrack,
            active = state.sleepDeadlineMs != null,
            onDismiss = { timerDialog = false },
            onSet = { minutes, finishTrack -> onSleepTimer(minutes, finishTrack); timerDialog = false },
            onExtend = onExtendSleep?.let { extend -> { ms: Long -> extend(ms); timerDialog = false } },
        )
    }

    PlayerPage(state, format, route, favourite, position, onClose, onToggle, onNext, onPrevious, onSeek,
        onShuffle, onRepeat, onFavourite, onMore, onOutput = changeOutput, onQueue = { queueSheet = true },
        onLyrics = { lyricsOpen = true }, onAudioPath = { audioPath = true }, onTimer = { timerDialog = true },
        queueOpen = queueSheet, lyricsSheetOpen = lyricsOpen,
        // "Playing from" names a playlist or mix when there is one; a tap then must not open the song's
        // album under that name, so it opens the album only when the line shows the album.
        onOpenAlbum = onOpenOrigin ?: if (originIsMix) ({ queueSheet = true }) else onOpenAlbum.takeIf { from == null }, onOpenArtist = onOpenArtist, motionCover = motionCover, onLyricsTools = { lyricsOpen = true }, from = from,
        inlineLyrics = { mod ->
            InlineLyrics(if (lyricsState.mediaId == state.mediaId) lyricsState.lyrics else ua.olex.music.lyrics.Lyrics.EMPTY, state, lyricOffset, position, onSeek,
                onTools = { lyricsOpen = true }, onFindOnline = onFindLyricsOnline, onlineStatus = onlineLyricsStatus,
                // Words fill at 60 frames a second: smooth to the eye, without holding the panel at 120 Hz.
                modifier = mod.preferredFrameRate(60f), textScale = lyricsScale, synced = lyricsSynced)
        })

}

/** A running countdown and its stop behavior stay visible until the owner edits or cancels it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SleepTimerDialog(
    active: Boolean,
    onDismiss: () -> Unit,
    onSet: (Int, Boolean) -> Unit,
    deadline: Long? = null,
    onExtend: ((Long) -> Unit)? = null,
    durationMs: Long? = null,
    finishingTrack: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val end = ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK
    val runningEnd = active && deadline == ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK_DEADLINE
    val preferences = LocalContext.current.getSharedPreferences("sleep_timer", android.content.Context.MODE_PRIVATE)
    var minutes by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(30) }
    var endOfSong by rememberSaveable { mutableStateOf(false) }
    var finishTrack by rememberSaveable {
        mutableStateOf(if (active && !runningEnd) finishingTrack else preferences.getBoolean("finish_track", true))
    }
    var changed by rememberSaveable { mutableStateOf(!active) }
    // A running timer counts down on the dial, once a second.
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(active, deadline, changed) {
        while (active && deadline != null && !runningEnd && !changed) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val leftMs = if (active && deadline != null && !runningEnd) (deadline - now).coerceAtLeast(0) else null
    val showingRun = active && !changed
    val runningSpan = durationMs ?: remember(deadline) { leftMs?.coerceAtLeast(1) ?: 1 }
    fun selectMinutes(value: Int) {
        val bounded = value.coerceIn(5, 120)
        if (bounded != minutes || endOfSong) haptics.tick()
        minutes = bounded
        endOfSong = false
        changed = true
    }
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.fillMaxWidth())
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The dial: drag around it in 5 minute steps (up to 2 hours), as Google's Clock sets a timer.
                SleepDial(
                    fraction = when {
                        showingRun && leftMs != null -> (leftMs.toFloat() / runningSpan).coerceIn(0f, 1f)
                        showingRun && runningEnd || endOfSong -> 1f
                        else -> minutes / 120f
                    },
                    enabled = !showingRun,
                    onDrag = { fraction -> selectMinutes(kotlin.math.round(fraction * 24).toInt() * 5) },
                ) {
                    when {
                        showingRun && leftMs != null -> {
                            Text("%d:%02d".format(leftMs / 60_000, leftMs / 1_000 % 60), style = MaterialTheme.typography.displayMediumEmphasized)
                            Text("left", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                        }
                        (showingRun && runningEnd) || endOfSong -> {
                            Symbol(Glyph.NOTE, size = 44.dp, filled = true, color = cs.primary)
                            Text(if (showingRun) "Finishing this song" else "End of this song",
                                style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                        }
                        else -> {
                            Text("$minutes", style = MaterialTheme.typography.displayLargeEmphasized)
                            val at = java.time.LocalTime.now().plusMinutes(minutes.toLong())
                            Text("min · until ${"%02d:%02d".format(at.hour, at.minute)}", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                        }
                    }
                    if (showingRun) TextButton(onClick = {
                        minutes = leftMs?.let { ((it + 150_000L) / 300_000L * 5).toInt().coerceIn(5, 120) } ?: 30
                        endOfSong = runningEnd
                        if (!runningEnd) finishTrack = finishingTrack
                        changed = true
                        haptics.tick()
                    }) { Text("Edit timer") }
                    else if (!endOfSong) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { selectMinutes(minutes - 5) }, enabled = minutes > 5,
                            modifier = Modifier.semantics { contentDescription = "Subtract five minutes" }) { Text("−5 min") }
                        TextButton(onClick = { selectMinutes(minutes + 5) }, enabled = minutes < 120,
                            modifier = Modifier.semantics { contentDescription = "Add five minutes" }) { Text("+5 min") }
                    }
                }
                // Quick lengths as one connected group; End of song last.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    val options = listOf(15, 30, 45, 60, end)
                    options.forEachIndexed { i, m ->
                        val checked = !showingRun && if (m == end) endOfSong else !endOfSong && minutes == m
                        androidx.compose.material3.ToggleButton(checked = checked, onCheckedChange = {
                            haptics.tick(); changed = true
                            if (m == end) endOfSong = true else { endOfSong = false; minutes = m }
                        }, shapes = when (i) { 0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes() },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = if (m == end) "When this song ends" else "$m minutes" }) {
                            if (m == end) Symbol(Glyph.NOTE, size = 20.dp, filled = checked) else Text("$m")
                        }
                    }
                }
                if (showingRun && !runningEnd) {
                    Text(if (finishingTrack) "The song playing when time is up will finish." else "Music fades out before the timer ends.",
                        modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                } else if (!showingRun && !endOfSong) {
                    fun changeFinishTrack(checked: Boolean) {
                        haptics.toggle(checked)
                        finishTrack = checked
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(cs.surfaceContainerHigh)
                        .then(Modifier.toggleable(finishTrack, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = ::changeFinishTrack))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Finish the song", style = MaterialTheme.typography.titleMedium)
                            Text(if (finishTrack) "When the countdown ends" else "Fade out when the countdown ends",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        AppSwitch(checked = finishTrack, onCheckedChange = null)
                    }
                }
            }
            if (showingRun) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (leftMs != null) androidx.compose.material3.FilledTonalButton(onClick = {
                    haptics.confirm()
                    onExtend?.invoke(10 * 60_000L) ?: onSet((leftMs / 60_000 + 10).toInt(), finishingTrack)
                },
                    modifier = Modifier.weight(1f).height(56.dp)) { Text("+10 min") }
                Button(onClick = { haptics.toggle(false); onSet(0, false) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Turn off") }
            } else Button(onClick = {
                haptics.confirm()
                if (!endOfSong) preferences.edit().putBoolean("finish_track", finishTrack).apply()
                onSet(if (endOfSong) end else minutes, !endOfSong && finishTrack)
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (active) "Change timer" else "Start", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * A round dial: a thick track, the set part in the accent from the top clockwise, and a handle to
 * drag around. The content sits in the middle.
 */
@Composable
private fun SleepDial(fraction: Float, enabled: Boolean, onDrag: (Float) -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val latestFraction by androidx.compose.runtime.rememberUpdatedState(fraction)
    val update by androidx.compose.runtime.rememberUpdatedState(onDrag)
    var dragging by remember { mutableStateOf(false) }
    val shown by androidx.compose.animation.core.animateFloatAsState(fraction,
        if (dragging || LocalPowerSave.current) androidx.compose.animation.core.snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "dial")
    Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().semantics {
            if (enabled) {
                contentDescription = "Timer length in minutes"
                progressBarRangeInfo = ProgressBarRangeInfo((fraction * 120f).coerceIn(5f, 120f), 5f..120f, 22)
                setProgress { value -> onDrag(value / 120f); true }
            }
        }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun angle(o: androidx.compose.ui.geometry.Offset): Float {
                    val dx = o.x - size.width / 2f; val dy = o.y - size.height / 2f
                    val a = (Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat() + 360f) % 360f
                    return (a / 360f).coerceIn(0.001f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f - 17.dp.toPx()
                    if (kotlin.math.abs((down.position - centre).getDistance() - radius) > 24.dp.toPx()) return@awaitEachGesture
                    var previousAngle = angle(down.position)
                    var value = when {
                        previousAngle < 0.06f && latestFraction > 0.75f -> 1f
                        previousAngle > 0.94f && latestFraction < 0.25f -> 0f
                        else -> previousAngle
                    }
                    dragging = true
                    down.consume()
                    try {
                        update(value)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isConsumed) break
                            val angle = angle(change.position)
                            val delta = angle - previousAngle
                            value = (value + when {
                                delta > 0.5f -> delta - 1f
                                delta < -0.5f -> delta + 1f
                                else -> delta
                            }).coerceIn(0f, 1f)
                            previousAngle = angle
                            update(value)
                            change.consume()
                        }
                    } finally {
                        dragging = false
                    }
                }
            }) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(cs.surfaceContainerHighest, 0f, 360f, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(cs.primary, -90f, 360f * shown, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val r = box.width / 2
            val a = Math.toRadians((360.0 * shown) - 90.0)
            val c = androidx.compose.ui.geometry.Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat())
            drawCircle(cs.onPrimary, stroke / 2 + 3.dp.toPx(), c)
            drawCircle(cs.primary, stroke / 2 - 3.dp.toPx(), c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** The three places of the app, in the navigation bar. */
internal enum class Place(val title: String) { HOME("Home"), LIBRARY("Playlists"), SEARCH("Search") }

/** Pages opened over Home. */
internal enum class Page(val title: String) { STATS("Your listening"), DUPLICATES("Duplicates"), LIBRARY_OVERVIEW("Library overview"), SETTINGS("Settings") }

/** The navigation bar: Home, Library, Search (M3E short navigation bar). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlacesBar(current: Place?, onGo: (Place) -> Unit) {
    androidx.compose.material3.ShortNavigationBar {
        Place.entries.forEach { p ->
            val selected = current == p
            // Only a tap animates, and only the tapped icon (as Gboard does): each icon has its own small gesture.
            var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            androidx.compose.material3.ShortNavigationBarItem(
                interactionSource = interaction, selected = selected, onClick = { taps++; onGo(p) },
                icon = { DestinationIcon(p, selected, taps, pressed) },
                label = { Text(p.title) },
            )
        }
    }
}

/** Shared toolbar for Home and its rendered design previews. */
@Composable
internal fun HomeActions(onStats: () -> Unit, onSettings: () -> Unit) {
    val haptics = rememberHaptics()
    KickIconButton(onClick = { haptics.tick(); onStats() }, modifier = Modifier.semantics { contentDescription = "Listening stats" }) { k -> Symbol(Glyph.STATS, kick = k) }
    // The gear turns as it opens Settings.
    var gear by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.material3.FilledTonalIconButton(onClick = { gear++; onSettings() },
    shapes = androidx.compose.material3.IconButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp).size(44.dp).semantics { contentDescription = "Settings" }) { Symbol(Glyph.SETTINGS, size = 22.dp, kick = gear) }
}

/** Move within a hierarchy and keep peer tabs anchored to the same viewport. */
@Composable
internal fun Modifier.arrivalMotion(route: Any?, section: Any, depth: Int): Modifier {
    val still = LocalPowerSave.current
    var previousRoute by remember { mutableStateOf(route) }
    var previousSection by remember { mutableStateOf(section) }
    var previousDepth by remember { androidx.compose.runtime.mutableIntStateOf(depth) }
    val direction = remember(route, section) { if (section == previousSection) depth.compareTo(previousDepth) else 0 }
    val progress = remember(route, section) {
        val switchingTabs = section != previousSection && depth == 0
        androidx.compose.animation.core.Animatable(if (route == previousRoute && section == previousSection || still || switchingTabs) 1f else 0f)
    }
    androidx.compose.runtime.SideEffect { previousRoute = route; previousSection = section; previousDepth = depth }
    val spec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(route, section, still) {
        if (still) progress.snapTo(1f) else if (progress.value != 1f) progress.animateTo(1f, spec)
    }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    return graphicsLayer {
        val remaining = if (still) 0f else 1f - progress.value
        alpha = 1f - 0.10f * remaining
        translationX = remaining * (if (rtl) -direction else direction) * 20.dp.toPx()
    }
}

@Composable
internal fun Arrival(route: Any, place: Place, depth: Int, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().arrivalMotion(route, place, depth)) { content() }
}
