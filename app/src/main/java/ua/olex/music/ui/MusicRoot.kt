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
    val onlineResults by model.online.collectAsStateWithLifecycle()
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
    var collectionTitle by remember { mutableStateOf<String?>(null) }
    var openTile by remember { mutableStateOf<String?>(null) }
    var closeCollection by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val duplicates by model.duplicates.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val haptics = rememberHaptics()
    var focusSearch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Where Back goes after something opened from elsewhere (an artist from Home): there, not to Search's tiles.
    var returnTo by remember { mutableStateOf<Place?>(null) }
    var onlineStack by remember { mutableStateOf(listOf<OnlineTarget>()) }
    // The screen under an online page keeps its state (scroll, open artist) while the page is over
    // it, so Back lands exactly where the owner was.
    val baseStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // An online page's title moves into the top bar once its header has scrolled away.
    var onlineTitleShown by remember { mutableStateOf(false) }
    val onlineTop = onlineStack.lastOrNull()
    androidx.compose.runtime.LaunchedEffect(onlineTop) { onlineTitleShown = false }
    // YouTube Music's moods for Home; the usual set when it is off or unreachable (they still play
    // from the library by genre).
    val moods by androidx.compose.runtime.produceState(YouTubeMusic.DEFAULT_MOODS, youTubeOn) { if (youTubeOn) value = context.graph.youTube.moods() }
    // The owner's YouTube Music home (their own once signed in); nothing while YouTube Music is off.
    val youTubeSignedIn by context.graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    val youTubeHome by androidx.compose.runtime.produceState(emptyList<YouTubeMusic.Shelf>(), youTubeOn, youTubeSignedIn) {
        value = if (youTubeOn) runCatching { context.graph.youTube.home() }.getOrDefault(emptyList()) else emptyList()
    }
    // A download asked for before the Soulseek account was set: it starts once the owner signs in.
    var afterSignIn by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun go(to: Place) { onlineStack = emptyList(); returnTo = null; baseStates.removeState("place-${to.name}"); if (to == Place.SEARCH && place == Place.SEARCH && page == null) { focusSearch++; return }; if (to != place) haptics.tick(); collectionTitle = null; if (place == Place.SEARCH && to != Place.SEARCH) model.setQuery(""); page = null; place = to }
    BackHandler(page != null) { page = null }
    /** Opens the album or artist of a song in the Library, from the player or a song's actions. */
    fun openOf(track: Track, artist: Boolean) {
        val index = library ?: return
        val target = if (artist) index.artists.firstOrNull { a -> a.tracks.any { it.id == track.id } && a.name.equals(track.albumArtist ?: track.artist, true) }
                ?: index.artists.firstOrNull { a -> a.tracks.any { it.id == track.id } }
            else index.albums.firstOrNull { a -> a.tracks.any { it.id == track.id } }
        target ?: return
        haptics.tick(); expanded = false; actionTrack = null; page = null
        if (place == Place.SEARCH) model.setQuery("") else returnTo = place
        openInLibrary = (if (artist) "ARTISTS" else "ALBUMS") to target.id; place = Place.SEARCH
    }
    BackHandler(page == null && place != Place.HOME && !expanded) { go(Place.HOME) }
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
    BackHandler(onlineStack.isNotEmpty() && !expanded) { onlineStack = onlineStack.dropLast(1) }
    val onlineNav = remember(model) {
        OnlineNav(
            open = { target ->
                expanded = false; actionTrack = null; page = null
                val twin = model.library.value?.let { libraryTwin(target, it) }
                if (twin == null) onlineStack = onlineStack + target
                else {
                    // One artist, one page: the owner's own, with YouTube Music's sections on it.
                    onlineStack = emptyList()
                    if (place == Place.SEARCH) model.setQuery("") else returnTo = place
                    openInLibrary = twin; place = Place.SEARCH
                }
            },
            play = model::playFrom,
            actions = { actionTrack = it },
            openLocalArtist = { artist -> onlineStack = emptyList(); returnTo = null; openInLibrary = "ARTISTS" to artist.id; place = Place.SEARCH },
            download = { start -> if (context.graph.soulseek.account == null) afterSignIn = start else start() },
            playMood = { mood, moodPage -> model.startMood(mood, moodPage) },
            playMix = { id, title -> model.startPlaylistRadio(id, title) },
        )
    }
    val powerSave = rememberPowerSave()
    val network by context.graph.network.collectAsStateWithLifecycle()
    // Play left songs out (or found nothing to play) because the phone is offline: say so once.
    androidx.compose.runtime.LaunchedEffect(model.offlineNotice) {
        if (model.offlineNotice > 0) snackbar.showSnackbar("Offline: playing your files and saved songs only")
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSnackbar provides snackbar, LocalIsPlaying provides playing.isPlaying, LocalOnlineNav provides onlineNav,
        LocalNetwork provides network,
        LocalPowerSave provides powerSave) {
    afterSignIn?.let { pending ->
        SoulseekDialog(null, onDismiss = { afterSignIn = null }) { account ->
            context.graph.soulseek.account = account
            afterSignIn = null
            if (account != null) pending()
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            if (onlineTop != null || place != Place.SEARCH || page != null) TopAppBar(
                title = {
                    // An online page (an album, a playlist, an artist, a mood) has the same bar as the
                    // library's pages: Back, and its name once the header is out of sight. Never Home's greeting.
                    if (onlineTop != null) androidx.compose.animation.AnimatedVisibility(onlineTitleShown,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 }) {
                        Text(when (onlineTop) { is OnlineTarget.Album -> onlineTop.title; is OnlineTarget.Artist -> onlineTop.name; is OnlineTarget.Mood -> onlineTop.mood.label },
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else if (inCollection) Text(collectionTitle.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(page?.title ?: if (place == Place.HOME) greeting() else place.title, style = MaterialTheme.typography.headlineMediumEmphasized)
                }, scrollBehavior = scroll,
                navigationIcon = {
                    if (onlineTop != null) IconButton(onClick = { onlineStack = onlineStack.dropLast(1) }, modifier = Modifier.semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) }
                    else if (page != null) IconButton(onClick = { page = null }, modifier = Modifier.semantics { contentDescription = "Back" }) { Symbol(Glyph.BACK) }
                    else if (inCollection) IconButton(onClick = { closeCollection++ }, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { Symbol(Glyph.BACK) }
                },
                actions = {
                    if (onlineTop == null && place == Place.LIBRARY && page == null && !inCollection) IconButton(onClick = { go(Place.SEARCH) }, modifier = Modifier.semantics { contentDescription = "Search" }) { Symbol(Glyph.SEARCH) }
                    // Settings as a round tonal button, where Google's apps keep the account circle: it belongs there, not squeezed in.
                    if (onlineTop == null && place == Place.HOME && page == null) androidx.compose.material3.FilledTonalIconButton(onClick = { page = Page.SETTINGS },
                        shapes = androidx.compose.material3.IconButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp).size(44.dp).semantics { contentDescription = "Settings" }) { Symbol(Glyph.SETTINGS, size = 22.dp) }
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
        Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPlayerInset provides playerInset) {
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
            onlineStack.isNotEmpty() -> when (val top = onlineStack.last()) {
                is OnlineTarget.Artist -> androidx.compose.runtime.key(top) { OnlineArtistPage(top, requireNotNull(library), playing.mediaId, insets) { onlineTitleShown = it } }
                is OnlineTarget.Album -> androidx.compose.runtime.key(top) { OnlineAlbumPage(top, requireNotNull(library), playing.mediaId, insets) { onlineTitleShown = it } }
                is OnlineTarget.Mood -> androidx.compose.runtime.key(top) { MoodPage(top, playing.mediaId, insets, requireNotNull(library)) { onlineTitleShown = it } }
            }
            page == Page.SETTINGS -> SettingsPage(Modifier.padding(insets), musicFolders.map { it.path }, model.scanning, model.ratingsToFiles,
                onRatingsToFiles = model::changeRatingsToFiles, onEqualizer = { equalizerOpen = true }, onRescan = model::rescanManually,
                onAddFolder = { chooseMusicFolder.launch(null) }, onClearFolders = model::clearMusicFolders)
            page == Page.STATS -> StatsPage(requireNotNull(library), songStats, Modifier.padding(insets), model::play)
            page == Page.DUPLICATES -> DuplicatesPage(duplicates, playing.mediaId, Modifier.padding(insets), onPlay = model::play) { tracks ->
                moveToTrash(tracks)
            }
            place == Place.HOME -> baseStates.SaveableStateProvider("place-HOME") { HomePage(requireNotNull(library), personal.history.mapNotNull { it.track }, songStats, smart, playing.mediaId,
                Modifier.padding(insets), onPlay = model::play, onShuffle = model::playShuffled,
                onOpenAlbum = { returnTo = Place.HOME; openInLibrary = "ALBUMS" to it.id; place = Place.SEARCH },
                onOpenArtist = { returnTo = Place.HOME; openInLibrary = "ARTISTS" to it.id; place = Place.SEARCH },
                onTrackActions = { actionTrack = it }, duplicates = duplicates.size,
                onStats = { page = Page.STATS }, onDuplicates = { page = Page.DUPLICATES },
                favourites = personal.favourites.mapNotNull { it.track },
                moods = moods,
                youTubeHome = youTubeHome.map { shelf -> shelf.copy(songs = shelf.songs.filterNot { it.id.value in model.excluded || Mix.songKey(it) in model.excluded },
                    cards = shelf.cards.filterNot { it.kind == YouTubeMusic.Card.Kind.SONG && "yt:${it.id}" in model.excluded }) },
                onSeeAll = { tab ->
                    // Smart playlists live in Playlists; albums, artists and history are Search tiles.
                    if (tab == "SMART") { returnTo = Place.HOME; openInLibrary = tab to ""; place = Place.LIBRARY } else { returnTo = Place.HOME; openTile = tab; place = Place.SEARCH }
                }) }
            else -> baseStates.SaveableStateProvider("place-${place.name}") { LibraryBrowser(
                library = requireNotNull(library), insets = insets,
                query = if (place == Place.SEARCH) query else "", search = search, onQueryChange = model::setQuery,
                mode = if (place == Place.SEARCH) BrowserMode.SEARCH else BrowserMode.LIBRARY,
                open = openInLibrary, onOpened = { openInLibrary = null; openTile = null },
                onLeave = returnTo?.let { back -> { returnTo = null; collectionTitle = null; place = back } },
                online = onlineResults, onlineOn = youTubeOn && place == Place.SEARCH,
                onCollection = { collectionTitle = it }, closeCollection = closeCollection, focusSearch = focusSearch, openTile = openTile.takeIf { place == Place.SEARCH },
                recentSearches = model.recentSearches, onSearchUsed = model::addRecentSearch, onClearRecent = model::clearRecentSearches,
                currentId = playing.mediaId, onPlay = model::play, onShuffle = model::playShuffled,
                smart = smart, onSaveSmart = model::saveSmartPlaylist, onDeleteSmart = model::deleteSmartPlaylist,
                onEditTags = { editingTags = it }, onTrash = moveToTrash,
                scanError = model.scanError, onRescan = model::refresh,
                onRetrySearch = model::retrySearch,
                personal = personal, personalActions = model.personalActions, sortOrders = sortOrders, onSortOrder = model::setSortOrder, onEnqueue = model::enqueue, stats = songStats,
                onTrackActions = { actionTrack = it }, onAddToPlaylist = { playlistTracks = it },
            ) }
        }
        }
        if (playing.hasTrack) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding()).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Transparent,
                0.55f to MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), 1f to MaterialTheme.colorScheme.surface))) {
            Box(Modifier.padding(top = 20.dp)) {
                MiniPlayer(playing, position = { model.positionMs }, onOpen = { expanded = true }, onToggle = model::toggle, onNext = model::next, onPrevious = model::previous)
            }
        }
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
        val motionCover by model.motionCover.collectAsStateWithLifecycle()
        NowPlaying(
            playing, format = playing.mediaId?.let { library?.byId?.get(TrackId(it))?.format }?.let { f ->
                listOfNotNull(f.label, f.bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" }).joinToString("  ·  ") }
                // A YouTube Music song: the stream's codec and bitrate, once the player picked it.
                // Quality only, the same shape as a file's; nothing until the stream is chosen.
                ?: playing.mediaId?.takeIf { it.startsWith("yt:") }?.let { context.graph.youTube.format(it.removePrefix("yt:")) },
            from = model.mixLabel ?: model.playingFrom,
            position = { model.positionMs }, onClose = { expanded = false }, motionCover = motionCover,
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
            onMore = playing.mediaId?.let { id ->
                library?.byId?.get(TrackId(id)) ?: if (id.startsWith("yt:")) ua.olex.music.model.Track(TrackId(id), "${ua.olex.music.playback.OnlineStreams.SCHEME}://${id.removePrefix("yt:")}",
                    playing.title, playing.artist, album = playing.album, durationMs = playing.durationMs, artworkUri = playing.artworkUri) else null
            }?.let { track -> { actionTrack = track } },
            mixFrom = model.mixLabel,
            onOpenAlbum = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { openOf(track, artist = false) } }
                // A YouTube Music song: its album page, when its row named one.
                ?: playing.mediaId?.takeIf { it.startsWith("yt:") }?.let { id -> Track(TrackId(id), "", playing.title, playing.artist) }
                    ?.let { t -> YouTubeMusic.albumOf(t)?.let { albumId -> { onlineNav.open(OnlineTarget.Album(albumId, playing.album ?: playing.title)) } } },
            onOpenArtist = playing.mediaId?.let { library?.byId?.get(TrackId(it)) }?.let { track -> { openOf(track, artist = true) } }
                ?: playing.mediaId?.takeIf { it.startsWith("yt:") && playing.artist.isNotBlank() }?.let { { onlineNav.open(OnlineTarget.Artist(playing.artist.substringBefore(",").trim())) } }
                ?: playing.mediaId?.takeIf { it.startsWith("yt:") }?.let { { onlineNav.open(OnlineTarget.Artist(playing.artist.substringBefore(",").trim())) } },
            fileRateHz = playing.mediaId?.let { library?.byId?.get(TrackId(it))?.format?.sampleRateHz },
        )
        }
    }
    if (equalizerOpen) EqualizerSheet(playing.equalizer, playing.connected, playing.equalizerError,
        onApply = model::setEqualizer, onDismiss = { equalizerOpen = false },
        loudness = playing.replayGain, onLoudness = model::setReplayGain)
    actionTrack?.let { track ->
        val online = YouTubeMusic.isOnline(track)
        val saved = track.id.value in personal.favouriteByTrackId
        TrackActionsSheet(track, saved, rating = stats[track.key.value]?.rating ?: 0, onRate = { stars ->
            model.setRating(track, stars)
            if (model.ratingsToFiles) requestTagWrite(listOf(track), TagEditRequest(mapOf("RATING" to if (stars == 0) "" else (stars * 20).toString()), quiet = true))
        },
            plays = stats[track.key.value]?.plays ?: 0, onDismiss = { actionTrack = null }, actions = listOfNotNull(
            // A mix grown from this song, as YouTube Music's "Start radio", local files first.
            TrackAction(Glyph.MIX, "Start mix") { haptics.spin(); model.startMix(track); scope.launch { snackbar.showSnackbar("Making a mix from ${track.title}") } },
            TrackAction(Glyph.PLAY_NEXT, "Play next") { model.enqueue(listOf(track), true) },
            // Never offered again in mixes, autoplay and Home; out of the queue now.
            if (online) TrackAction(Glyph.HIDDEN, "Don't recommend this song") {
                model.dontRecommend(track); scope.launch { snackbar.showSnackbar("${track.title} won't be recommended again") }
            } else null,
            // An online song's album and artist open their pages (the owner's own artist page when he has them).
            if (online) YouTubeMusic.albumOf(track)?.let { id -> TrackAction(Glyph.ALBUM, "Go to album") { actionTrack = null; onlineNav.open(OnlineTarget.Album(id, track.album ?: track.title, cover = track.artworkUri)) } }
                else TrackAction(Glyph.ALBUM, "Go to album") { openOf(track, artist = false) },
            if (online) TrackAction(Glyph.PERSON, "Go to artist") { actionTrack = null; onlineNav.open(OnlineTarget.Artist(track.artist.substringBefore(",").trim())) }
                else TrackAction(Glyph.PERSON, "Go to artist") { openOf(track, artist = true) },
            // A song from YouTube Music, kept for good: Soulseek finds a lossless copy and the library plays it from then on.
            if (online) {
                val artist = track.artist.substringBefore(",").trim()
                val job = context.graph.soulseek.job(ua.olex.music.soulseek.SoulseekDownloads.songKey(artist, track.title))
                when {
                    job?.running == true -> TrackAction(Glyph.DOWNLOAD, job.status) { }
                    job?.failed == true -> TrackAction(Glyph.REFRESH, "Try the download again") {
                        context.graph.soulseek.retry(job); scope.launch { snackbar.showSnackbar("Looking on Soulseek again") }
                    }
                    else -> TrackAction(Glyph.DOWNLOAD, "Download") {
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
        AppDialog(onDismissRequest = model::dismissTagMessage, title = { Text("Tags") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissTagMessage) { Text("OK") } })
    }
    playlistTracks?.let { PlaylistPicker(personal.playlists, it, model.personalActions) { playlistTracks = null } }
    playing.queueError?.let { message ->
        AppDialog(onDismissRequest = model::dismissQueueError, title = { Text("Couldn't update queue") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::dismissQueueError) { Text("OK") } })
    }
    model.userError?.let { message ->
        AppDialog(onDismissRequest = model::dismissUserError, title = { Text("Couldn't save changes") }, text = { Text(message) },
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
    onSleepTimer: (Int) -> Unit, from: String? = null,
    onEqualizer: () -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit,
    onQueuePlay: (String) -> Unit, onQueueMove: (String, Int) -> Unit, onQueueRemove: (String) -> Unit,
    onMore: (() -> Unit)?,
    mixFrom: String? = null,
    lyricsState: LocalLyricsState, lyricOffset: Long, onLyricOffset: (Long) -> Unit,
    musicFolders: List<String>, onChooseMusicFolder: () -> Unit, onClearMusicFolders: () -> Unit, onReloadLyrics: () -> Unit,
    onFindLyricsOnline: () -> Unit = {}, onlineLyricsStatus: String? = null,
    onSaveLyricTiming: ((ua.olex.music.lyrics.Lyrics) -> Unit)? = null, onResetLyricTiming: () -> Unit = {},
    favourite: Boolean, onFavourite: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)? = null, onOpenArtist: (() -> Unit)? = null, fileRateHz: Int? = null, motionCover: String? = null,
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
    if (audioPath) AudioPathSheet(format, route, state.audio, onDismiss = { audioPath = false }, onOutput = changeOutput, onEqualizer = { audioPath = false; onEqualizer() },
        fileRateHz = fileRateHz, processing = listOfNotNull(
            state.equalizer.settings.takeIf { it.enabled }?.let { "Equalizer: ${it.profile}" },
            state.equalizer.settings.channels.takeIf { !it.neutral }?.let { c -> listOfNotNull(if (c.mono) "mono" else null, if (c.swap) "left/right swapped" else null,
                if (c.balance != 0f) "balance ${(c.balance * 100).toInt()}%" else null).joinToString(", ").replaceFirstChar(Char::uppercase) },
            state.replayGain.mode.takeIf { it != ua.olex.music.dsp.ReplayGainMode.OFF }?.let { "ReplayGain: ${it.name.lowercase()}" },
        ))
    if (queueSheet) QueueSheet(state, onDismiss = { queueSheet = false }, onPlay = onQueuePlay, onMove = onQueueMove, onRemove = onQueueRemove, mixFrom = mixFrom)
    if (timerDialog) {
        SleepTimerDialog(
            deadline = state.sleepDeadlineMs,
            active = state.sleepDeadlineMs != null,
            onDismiss = { timerDialog = false },
            onSet = { minutes -> onSleepTimer(minutes); timerDialog = false },
        )
    }

    PlayerPage(state, format, route, favourite, position, onClose, onToggle, onNext, onPrevious, onSeek,
        onShuffle, onRepeat, onFavourite, onMore, onOutput = changeOutput, onQueue = { queueSheet = true },
        onLyrics = { lyricsOpen = true }, onAudioPath = { audioPath = true }, onTimer = { timerDialog = true },
        onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist, motionCover = motionCover, onLyricsTools = { lyricsOpen = true }, from = from,
        inlineLyrics = { mod ->
            InlineLyrics(if (lyricsState.mediaId == state.mediaId) lyricsState.lyrics else ua.olex.music.lyrics.Lyrics.EMPTY, state, lyricOffset, position, onSeek,
                onTools = { lyricsOpen = true }, onFindOnline = onFindLyricsOnline, onlineStatus = onlineLyricsStatus,
                // Words fill at 60 frames a second: smooth to the eye, without holding the panel at 120 Hz.
                modifier = mod.preferredFrameRate(60f))
        })
    if (outputUnavailable) AppDialog(onDismissRequest = { outputUnavailable = false }, title = { Text("Choose output") },
        text = { Text("Open Android's media output controls to choose a device.") },
        confirmButton = { TextButton(onClick = { outputUnavailable = false }) { Text("OK") } })
}

/**
 * The sleep timer as a sheet, not a list of buttons in a dialog. While a timer runs, what is left
 * comes first with +10 min and Turn off. Below: the common lengths as large tiles (one tap sets and
 * closes), "When this song ends" as its own wide tile, and a slider for any other length. Each choice
 * says in words what happens, and the timer fades the music out over its last 30 seconds.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SleepTimerDialog(active: Boolean, onDismiss: () -> Unit, onSet: (Int) -> Unit, deadline: Long? = null) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val end = ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK
    var minutes by rememberSaveable { mutableStateOf(30) }
    var endOfSong by rememberSaveable { mutableStateOf(false) }
    var changed by rememberSaveable { mutableStateOf(!active) }
    // A running timer counts down on the dial, once a second.
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(active, deadline) { while (active && deadline != null) { now = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(1_000) } }
    val runningEnd = active && deadline == ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK_DEADLINE
    val leftMs = if (active && deadline != null && !runningEnd) (deadline - now).coerceAtLeast(0) else null
    val showingRun = active && !changed
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.fillMaxWidth())
            // The dial: drag around it in 5 minute steps (up to 2 hours), as Google's Clock sets a timer.
            SleepDial(
                fraction = when { showingRun && leftMs != null -> (leftMs / 60_000f / 120f).coerceIn(0f, 1f); showingRun && runningEnd -> 1f
                    endOfSong -> 1f; else -> minutes / 120f },
                enabled = true,
                onDrag = { f ->
                    val m = ((f * 120f) / 5f).toInt().coerceIn(1, 24) * 5
                    if (m != minutes || endOfSong) haptics.tick()
                    minutes = m; endOfSong = false; changed = true
                },
            ) {
                when {
                    showingRun && leftMs != null -> {
                        Text("%d:%02d".format(leftMs / 60_000, leftMs / 1_000 % 60), style = MaterialTheme.typography.displayMediumEmphasized)
                        Text("left", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    }
                    (showingRun && runningEnd) || endOfSong -> {
                        Symbol(Glyph.NOTE, size = 44.dp, filled = true, color = cs.primary)
                        Text("End of this song", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    }
                    else -> {
                        Text("$minutes", style = MaterialTheme.typography.displayLargeEmphasized)
                        val at = java.time.LocalTime.now().plusMinutes(minutes.toLong())
                        Text("min · until ${"%02d:%02d".format(at.hour, at.minute)}", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                    }
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
            if (showingRun) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (leftMs != null) androidx.compose.material3.FilledTonalButton(onClick = { haptics.confirm(); onSet((leftMs / 60_000 + 10).toInt()) },
                    modifier = Modifier.weight(1f).height(56.dp)) { Text("+10 min") }
                Button(onClick = { haptics.toggle(false); onSet(0) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Turn off") }
            } else Button(onClick = { haptics.confirm(); onSet(if (endOfSong) end else minutes) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (active) "Change timer" else "Start", style = MaterialTheme.typography.titleMedium)
            }
            Text("The music fades out over the last half minute, then pauses.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
    val shown by androidx.compose.animation.core.animateFloatAsState(fraction, MaterialTheme.motionScheme.fastSpatialSpec(), label = "dial")
    Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Timer length" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun angle(o: androidx.compose.ui.geometry.Offset): Float {
                    val dx = o.x - size.width / 2f; val dy = o.y - size.height / 2f
                    val a = (Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat() + 360f) % 360f
                    return (a / 360f).coerceIn(0.001f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onDrag(angle(down.position))
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { if (it.pressed) { onDrag(angle(it.position)); it.consume() } }
                    } while (event.changes.any { it.pressed })
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
internal enum class Place(val title: String, val glyph: Glyph) { HOME("Home", Glyph.HOME), LIBRARY("Playlists", Glyph.PLAYLIST), SEARCH("Search", Glyph.SEARCH) }

/** Pages opened over Home. */
internal enum class Page(val title: String) { STATS("Your listening"), DUPLICATES("Duplicates"), SETTINGS("Settings") }

/** The navigation bar: Home, Library, Search (M3E short navigation bar). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlacesBar(current: Place?, onGo: (Place) -> Unit) {
    androidx.compose.material3.ShortNavigationBar {
        Place.entries.forEach { p ->
            val selected = current == p
            // Only a tap animates, and only the tapped icon (as Gboard does): each icon has its own small gesture.
            var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            androidx.compose.material3.ShortNavigationBarItem(
                selected = selected, onClick = { taps++; onGo(p) },
                icon = {
                    val motion = remember { androidx.compose.animation.core.Animatable(1f) }
                    LaunchedEffect(taps) { if (taps > 0) { motion.snapTo(0f); motion.animateTo(1f, androidx.compose.animation.core.spring(0.45f, 450f)) } }
                    Symbol(p.glyph, filled = selected, bold = selected, modifier = Modifier.graphicsLayer {
                        val v = motion.value
                        val k = 0.82f + 0.18f * v
                        scaleX = k; scaleY = k
                        when (p) {
                            Place.HOME -> translationY = (1f - v) * -4.dp.toPx()          // the house settles down
                            Place.LIBRARY -> translationX = (1f - v) * -4.dp.toPx()       // the list slides in
                            Place.SEARCH -> rotationZ = (1f - v) * -28f                   // the lens swings and settles
                        }
                    })
                }, label = { Text(p.title) },
            )
        }
    }
}

/** Home's title: a greeting for the time of day, as Google's apps greet. */
internal fun greeting(hour: Int = java.time.LocalTime.now().hour) = when (hour) {
    in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Late night"
}
