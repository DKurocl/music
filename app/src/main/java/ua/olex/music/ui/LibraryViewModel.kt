package ua.olex.music.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import ua.olex.music.user.UserRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import ua.olex.music.graph
import ua.olex.music.model.Track
import ua.olex.music.model.TrackId
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.TrackSortScope
import ua.olex.music.model.TrackSortMode
import ua.olex.music.model.TrackSortOrder
import ua.olex.music.playback.PlayerState

/** The library and the player, as the first screens need them. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val graph = app.graph
    private val player get() = graph.player

    /** Null until the first read from the database: the list shows nothing rather than "empty". */
    // The app's shared index (AppGraph.index): already loading or loaded when the screen opens.
    val library: StateFlow<LibraryIndex?> = graph.index
    /** The scanned files plus durable online songs; never built in composition or from network pages. */
    // Shared and kept by the app (AppGraph.catalogueState): reopening the app doesn't merge it again.
    val catalogue: StateFlow<LibraryIndex?> = graph.catalogueState
    private val sortPreferences = app.getSharedPreferences("library-sort-v1", android.content.Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences("library-options", android.content.Context.MODE_PRIVATE)
    private val _sortOrders = MutableStateFlow(TrackSortScope.entries.associateWith { scope ->
        val mode = runCatching { TrackSortMode.valueOf(sortPreferences.getString(scope.name, null).orEmpty()) }.getOrDefault(TrackSortMode.DEFAULT)
        TrackSortOrder(mode, runCatching { sortPreferences.getBoolean("${scope.name}.descending", false) }.getOrDefault(false))
    })
    val sortOrders: StateFlow<Map<TrackSortScope, TrackSortOrder>> = _sortOrders
    fun setSortOrder(scope: TrackSortScope, order: TrackSortOrder) {
        _sortOrders.value = _sortOrders.value + (scope to order)
        sortPreferences.edit().putString(scope.name, order.mode.name).putBoolean("${scope.name}.descending", order.descending).apply()
    }

    val playing: StateFlow<PlayerState> get() = player.state
    val query: StateFlow<String> = savedState.getStateFlow("search", "")
    private val searchRetry = MutableStateFlow(0)
    val search: StateFlow<LibrarySearchResult?> = query
        .debounce { if (it.isBlank()) 0 else 180 }
        .combine(searchRetry) { text, _ -> text }
        .flatMapLatest { text ->
            if (text.isBlank()) flowOf(LibrarySearchResult(text, emptyList()))
            else combine(graph.library.search(text), catalogue) { local, saved ->
                val index = saved ?: LibraryIndex.EMPTY
                val bound = local.map { track -> index.byId[track.id] ?: index.localCopiesById[track.id] ?: track }
                val online = ua.olex.music.model.SavedCatalog.searchOnline(index, text)
                LibrarySearchResult(text, (bound + online).distinctBy { it.recordingId ?: it.id.value })
            }.flowOn(Dispatchers.Default)
                .catch { emit(LibrarySearchResult(text, emptyList(), failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) { savedState["search"] = text }

    /**
     * YouTube Music's songs for the same query, a moment after typing stops. Songs the owner already
     * has saved are left out here; the library section shows their file or durable online reference.
     */
    internal val online: StateFlow<OnlineSearch?> = query
        .debounce { if (it.isBlank()) 0 else 450 }
        .combine(graph.youTubeOn) { text, on -> text to on }
        .combine(graph.network) { (text, on), connected -> text to (on && connected) }
        .combine(searchRetry) { request, _ -> request }
        .mapLatest { (text, on) ->
            if (!on || text.trim().length < 2) return@mapLatest null
            try {
                val found = graph.youTube.searchAll(text.trim())
                OnlineSearch(text, found.songs, artists = found.artists, albums = found.albums)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OnlineSearch(text, emptyList(), failed = true) }
        }.combine(search) { found, local -> found to local }.combine(catalogue) { (found, local), catalogue ->
            if (found == null) return@combine null
            val visible = local?.takeIf { it.query == found.query }?.tracks.orEmpty()
            val visibleIds = visible.mapTo(HashSet()) { it.id }
            val linkedVideos = visible.flatMap { it.sources }.mapNotNullTo(HashSet()) { it.uri }
            val index = catalogue ?: LibraryIndex.EMPTY
            val owned = YouTubeMusic.indexOf(index)
            val mineArtists = index.artists.mapTo(HashSet()) { ua.olex.music.model.TextFold.fold(it.name) }
            val mineAlbums = index.albums.groupBy({ ua.olex.music.model.TextFold.fold(it.name) }, { ua.olex.music.model.TextFold.fold(it.subtitle.orEmpty()) })
            found.copy(
                tracks = found.tracks.filter { track ->
                    val file = owned.copyOf(track)?.takeIf { it.recordingId == null }
                    track.id !in visibleIds && track.uri !in linkedVideos && (file == null || file.id !in visibleIds)
                },
                artists = found.artists.filterNot { ua.olex.music.model.TextFold.fold(it.title) in mineArtists },
                albums = found.albums.filterNot { card ->
                    val by = ua.olex.music.model.TextFold.fold(card.subtitle.orEmpty())
                    mineAlbums[ua.olex.music.model.TextFold.fold(card.title)].orEmpty().any { artist -> artist.isNotBlank() && by.contains(artist.substringBefore(",").trim()) }
                },
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun retrySearch() { searchRetry.update { it + 1 } }

    private val collections = combine(catalogue, graph.userLibrary.favourites.distinctUntilChanged(),
        graph.userLibrary.playlists.distinctUntilChanged(), graph.userLibrary.recordingIds) { index, favourites, playlists, aliases ->
        val library = index ?: LibraryIndex.EMPTY
        val base = PersonalLibraryState.resolve(library, favourites, playlists, emptyList(), aliases)
        base to HistoryRows(library)
    }
    val personal: StateFlow<PersonalLibraryState> = combine(collections, graph.userLibrary.history) { (base, rows), events ->
        base.copy(history = rows.resolve(events))
    }.flowOn(Dispatchers.Default).catch {
        userError = "Couldn't open your personal library. Your saved data has not been reset."
        emit(PersonalLibraryState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalLibraryState())

    val musicFolders = graph.musicFiles.folders
    /** The playing song: the library's file, or, for a YouTube Music song, what the player knows of it. */
    private fun playingTrack(state: PlayerState = playing.value, index: LibraryIndex? = catalogue.value): Track? {
        val id = state.mediaId ?: return null
        val file = index?.byId?.get(TrackId(id))
        val queued = state.track
        if (file != null && (queued == null || file.uri == queued.uri)) return file.copy(
            recordingId = file.recordingId ?: queued?.recordingId,
            sources = (file.sources + queued?.sources.orEmpty()).distinctBy { it.key })
        return queued ?: if (id.startsWith("yt:")) Track(TrackId(id), "${ua.olex.music.playback.OnlineStreams.SCHEME}://${id.removePrefix("yt:")}",
            state.title, state.artist, album = state.album, durationMs = state.durationMs, artworkUri = state.artworkUri) else null
    }
    private val lyricTrack = combine(catalogue, playing.distinctUntilChanged { a, b -> a.mediaId == b.mediaId && a.title == b.title && a.track == b.track }) { index, state ->
        playingTrack(state, index)
    }.distinctUntilChanged()
    val localLyrics = combine(lyricTrack, graph.musicFiles.revision, graph.downloadedLyrics.revision) { track, revision, _ -> track to revision }
        .mapLatest { (track, _) ->
            if (track == null) return@mapLatest LocalLyricsState()
            graph.resolveLyrics(track)
        }.map { state ->
            val track = playingTrack()?.takeIf { it.id.value == state.mediaId }
            state.copy(lyrics = ua.olex.music.lyrics.LyricsCleanup.clean(state.lyrics, track?.title, track?.artist))
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalLyricsState())

    internal data class PlaybackMotionCover(val owner: MotionCoverOwner, val video: MotionArtwork)

    /** Keep a same-album video alive across tracks. Resolve new folders and albums off main. */
    internal val motionCover = combine(
        lyricTrack.distinctUntilChanged { a, b -> a?.let(::motionCoverOwner) == b?.let(::motionCoverOwner) },
        graph.musicFiles.revision,
        graph.motionCovers.revision,
    ) { track, files, covers -> Triple(track, files, covers) }
        .mapLatest { (track, files, covers) ->
            if (track == null) return@mapLatest null
            val saved = graph.motionCovers.file(track)
            val sidecar = if (saved == null) graph.musicFiles.motionCover(track.path) else null
            val video = when {
                saved != null -> MotionArtwork(android.net.Uri.fromFile(saved).toString(), covers.toLong())
                sidecar != null -> MotionArtwork(sidecar, files)
                else -> {
                    kotlinx.coroutines.delay(250)
                    fetchAppleMotion(track)?.let { MotionArtwork(android.net.Uri.fromFile(it).toString(), graph.motionCovers.revision.value.toLong()) }
                }
            }
            video?.let { PlaybackMotionCover(motionCoverOwner(track), it) }
        }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    private suspend fun fetchAppleMotion(track: ua.olex.music.model.Track): java.io.File? {
        val apple = graph.appleMotion
        if (!apple.enabled) return null
        val net = getApplication<android.app.Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val caps = net?.getNetworkCapabilities(net.activeNetwork) ?: return null
        if (apple.wifiOnly && !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return null
        return try {
            val bytes = AuxiliaryLoads.run { apple.find(track) } ?: return null
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            graph.motionCovers.attachBytes(track, bytes)
            graph.motionCovers.file(track)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun reloadLyrics() = refreshLocalFiles(rescan = false)

    /** Saves hand-fixed timing for the playing song (app storage, wins over other sources). */
    fun saveLyricTiming(lyrics: ua.olex.music.lyrics.Lyrics) {
        val track = lyricTrackNow() ?: return
        viewModelScope.launch(Dispatchers.IO) { graph.downloadedLyrics.saveEdited(graph.userLibrary.keepReference(track).bind(track), ua.olex.music.lyrics.LrcWriter.write(lyrics)) }
    }
    fun resetLyricTiming() {
        val track = lyricTrackNow() ?: return
        viewModelScope.launch(Dispatchers.IO) { graph.downloadedLyrics.deleteEdited(graph.userLibrary.keepReference(track).bind(track)) }
    }
    private fun lyricTrackNow() = playingTrack()
    /** Current source tags plus the queue's recording identity and reserves. */
    fun currentTrack(state: PlayerState = playing.value) = playingTrack(state)

    /** Songs kept in more than one file, best copy first in each group (worked out off the main thread). */
    val duplicates: StateFlow<List<List<Track>>> = library.map { index -> index?.let { ua.olex.music.model.Duplicates.find(it.songs) }.orEmpty() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The last searches that led somewhere, newest first (at most 10). */
    var recentSearches by mutableStateOf(prefs.getString("recent_searches", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()); private set
    fun addRecentSearch(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, true) }).take(10)
        prefs.edit().putString("recent_searches", recentSearches.joinToString("\n")).apply()
    }
    fun clearRecentSearches() { recentSearches = emptyList(); prefs.edit().remove("recent_searches").apply() }

    /** The outcome of the last tag edit, shown once. */
    var tagMessage by mutableStateOf<String?>(null); private set
    fun dismissTagMessage() { tagMessage = null }

    /** Writes the edited fields into the files (after Android's write permission), then rescans. */
    internal fun writeTags(tracks: List<Track>, request: TagEditRequest) {
        val edits = tagEdits(request.fields)
        if (edits.isEmpty() && request.cover == null && request.nameTemplate == null && request.folderTemplate == null) return
        if (!request.quiet) tagMessage = "Writing tags…"
        viewModelScope.launch {
            val result = TagEditing.write(getApplication(), tracks.filter { '#' !in it.id.value }, request.cover, request.nameTemplate, request.folderTemplate) { edits }
            if (!request.quiet || result.failed.isNotEmpty()) tagMessage = buildString {
                append(if (result.written == 1) "Tags saved in 1 file." else "Tags saved in ${result.written} files.")
                if (result.renamed > 0) append(" ${result.renamed} renamed or moved.")
                if (result.unsupported.isNotEmpty()) append(" ${result.unsupported.size} not supported (FLAC, MP3, M4A, Ogg and Opus only).")
                if (result.failed.isNotEmpty()) append(" ${result.failed.size} couldn't be written.")
            }
            refresh()
        }
    }

    /** Null while idle; a short status while the internet lookup runs or after it ends. */
    var onlineLyricsStatus by mutableStateOf<String?>(null); private set

    /** Lyrics just found online, waiting for Android's permission to be written into the song's file. */
    var lyricsForFile by mutableStateOf<Pair<ua.olex.music.model.Track, String>?>(null); private set
    fun lyricsForFileHandled() { lyricsForFile = null }

    /** Looks the playing song's lyrics up on the internet (Kugou word-timed, then LRCLIB) and keeps them. */
    fun findLyricsOnline() {
        val track = playingTrack() ?: return
        onlineLyricsStatus = "Searching online…"
        viewModelScope.launch {
            onlineLyricsStatus = try {
                graph.downloadedLyrics.fetch(graph.userLibrary.keepReference(track).bind(track))?.let { found ->
                    // Kept in the file too, replacing what it had, so other players and a reinstall see them.
                    if ('#' !in track.id.value && !YouTubeMusic.isOnline(track)) lyricsForFile = track to found.text
                    "Found on ${found.source}"
                } ?: "No lyrics found online for this song."
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Couldn't reach the lyrics services." }
        }
    }
    fun rescanManually() = refreshLocalFiles(rescan = true)
    private fun refreshLocalFiles(rescan: Boolean) { viewModelScope.launch {
        try { graph.musicFiles.refresh(); if (rescan) refresh(force = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { userError = "Couldn't refresh local files. Try again." }
    } }
    fun updateCurrentArtwork(mediaId: String, uri: String) = player.updateCurrentArtwork(mediaId, uri)
    fun addMusicFolder(uri: android.net.Uri) {
        viewModelScope.launch {
            try { graph.musicFiles.addFolder(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { userError = "Couldn't keep folder access. Choose a music folder on this phone's internal storage." }
        }
    }
    fun clearMusicFolders() { viewModelScope.launch {
        try { graph.musicFiles.clearFolders() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { userError = "Couldn't clear folder access. Try again." }
    } }

    val lyricOffset = lyricTrack.map { it?.let(ua.olex.music.model.SavedSong::from) }
        .distinctUntilChanged().flatMapLatest { song -> song?.let(graph.userLibrary::lyricOffset) ?: flowOf(0L) }
        .catch { emit(0L) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setLyricOffset(mediaId: String, offsetMs: Long) {
        if (playing.value.mediaId != mediaId) return
        val track = playingTrack() ?: return
        editUserData { setLyricOffset(track, offsetMs.coerceIn(-60_000, 60_000)) }
    }

    /** Plays and ratings per recording, with unique legacy keys for unsaved files. */
    /** Ratings in the files count when the app has none of its own, so they survive a reinstall. */
    private val fileRatings = catalogue.map { index ->
        index?.songs?.mapNotNull { t -> t.metadata?.rating?.let { t.statsKey to it } }.orEmpty()
    }.distinctUntilChanged()
    val stats: StateFlow<Map<String, ua.olex.music.model.SongStats>> = combine(graph.userLibrary.stats.catch { emit(emptyMap()) }, fileRatings) { own, fromFiles ->
        if (fromFiles.isEmpty()) own else own.toMutableMap().apply {
            for ((key, stars) in fromFiles) { val s = get(key) ?: ua.olex.music.model.SongStats.NONE; if (!s.ratingSet) put(key, s.copy(rating = stars, ratingSet = true)) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Whether star ratings are also written into the files (FLAC RATING, MP3 POPM). */
    var ratingsToFiles by mutableStateOf(prefs.getBoolean("ratings_to_files", false)); private set
    fun changeRatingsToFiles(on: Boolean) { ratingsToFiles = on; prefs.edit().putBoolean("ratings_to_files", on).apply() }

    /** Built-in and the owner's smart playlists, worked out off the main thread whenever their inputs change. */
    val smart: StateFlow<List<SmartPlaylistUi>> = combine(catalogue, stats, graph.userLibrary.smartPlaylists.catch { emit(emptyList()) }, personal) { index, stats, own, personal ->
        val songs = index?.songs ?: return@combine emptyList()
        val now = System.currentTimeMillis()
        val seed = now / 86_400_000L // a shuffled list keeps its order for the day
        (ua.olex.music.model.SmartPlaylist.BUILT_IN + own).map { p ->
            SmartPlaylistUi(p, p.evaluate(songs, { stats[it.statsKey] ?: ua.olex.music.model.SongStats.NONE }, { (it.recordingId ?: it.id.value) in personal.favouriteByTrackId }, now, seed))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveSmartPlaylist(p: ua.olex.music.model.SmartPlaylist) = editUserData { saveSmartPlaylist(p) }
    fun deleteSmartPlaylist(id: Long) = editUserData { deleteSmartPlaylist(id) }
    fun setRating(track: Track, stars: Int) = editUserData { setRating(track, stars) }

    var userError by mutableStateOf<String?>(null); private set
    val personalActions = PersonalLibraryActions(
        create = { name, tracks -> editUserData { createPlaylist(name, tracks) } },
        importPlaylist = { name, songs -> editUserData { importPlaylist(name, songs) } },
        rename = { id, name -> editUserData { renamePlaylist(id, name) } },
        delete = { id -> editUserData { deletePlaylist(id) } },
        add = { id, tracks, duplicates -> editUserData { addToPlaylist(id, tracks, duplicates) } },
        removeWithUndo = { id, song, position ->
            val repository = graph.userLibrary
            val removed = repository.removeFromPlaylist(id, song, position)
            if (removed == null) null else suspend { repository.restorePlaylistOccurrence(removed) }
        },
        remove = { id, song, position -> editUserData { removeFromPlaylist(id, song, position) } },
        move = { id, song, delta, position -> editUserData { moveInPlaylist(id, song, delta, position) } },
        removeFavourite = { id -> editUserData {
            graph.youtubeFavourites.remove(id)
            graph.youtubeFavourites.refresh()
        } },
    )

    fun toggleFavourite(track: Track) {
        val saved = personal.value.favouriteByTrackId[track.recordingId ?: track.id.value]
        editUserData {
            graph.youtubeFavourites.toggle(track, saved?.id)
            graph.youtubeFavourites.refresh()
        }
    }

    fun refreshYouTubeLikes() {
        viewModelScope.launch { graph.youtubeFavourites.refresh() }
    }

    init {
        viewModelScope.launch {
            combine(graph.network, graph.youTubeAccount.cacheScope, graph.youTubeOn) { _, _, _ -> Unit }
                .collectLatest { graph.youtubeFavourites.refresh() }
        }
    }
    fun dismissUserError() { userError = null }
    private fun editUserData(action: suspend UserRepository.() -> Unit) {
        viewModelScope.launch {
            try { graph.userLibrary.action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { userError = "Couldn't save your changes. Please try again." }
        }
    }

    var allowed by mutableStateOf(hasAccess()); private set
    var scanning by mutableStateOf(false); private set
    var scanError by mutableStateOf<String?>(null); private set
    private var scanJob: Job? = null
    private var watchJob: Job? = null
    private var watchingRequested = false
    private var rescanPending = false
    private var pendingForcedScan = false

    val positionMs: Long get() = player.positionMs

    /** Called when the app comes forward and after the permission dialog. */
    fun refresh(force: Boolean = false) {
        allowed = hasAccess()
        if (!allowed) {
            scanJob?.cancel()
            watchJob?.cancel()
            watchJob = null
            scanError = null
            return
        }
        if (scanning) {
            rescanPending = true
            pendingForcedScan = pendingForcedScan || force
            return
        }
        rescanPending = false
        val forced = force || pendingForcedScan
        pendingForcedScan = false
        scanning = true
        scanError = null
        if (watchingRequested) startWatching()
        scanJob = viewModelScope.launch {
            try {
                if (forced) graph.library.rescan() else graph.library.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                allowed = hasAccess()
                scanError = if (allowed) "Music access changed. Try scanning again." else null
            } catch (_: Exception) {
                scanError = "Couldn't scan your music. Your saved library is unchanged."
            } finally {
                scanning = false
                // File changes arriving during a scan need one more pass after its snapshot.
                if (rescanPending && watchJob?.isActive == true) refresh(force = pendingForcedScan)
            }
        }
    }

    /** Observe only while the activity is visible, and combine a file-copy burst into one scan. */
    fun startWatching() {
        watchingRequested = true
        if (watchJob?.isActive == true || !hasAccess()) return
        watchJob = viewModelScope.launch {
            graph.library.changes.debounce(500)
                .catch { scanError = "Automatic library updates are unavailable. Use Rescan to refresh." }
                .collect { refresh() }
        }
    }

    fun stopWatching() { watchingRequested = false; watchJob?.cancel(); watchJob = null }

    /** Connect only while a screen is visible; the service owns ongoing playback independently. */
    fun connectPlayer() = player.connect()
    fun disconnectPlayer() = player.disconnect()

    /** Plays a list; any online song the owner also has as a file plays from the file. */
    fun play(list: List<Track>, index: Int) = playFrom(list, index, null)

    /** Plays a list and remembers what it is ("Top Hits", an artist, "Search") for the player's "Playing from". */
    fun playFrom(list: List<Track>, index: Int, from: String?) {
        if (index !in list.indices) return
        graph.mix.stop()
        playingFrom = from
        cancelPendingPlay()
        playChecked(list, index)
    }

    private var playJob: Job? = null

    private fun cancelPendingPlay() {
        playJob?.cancel()
        playJob = null
        mixLoading = false
        dismissMixFailure()
    }

    private fun prepareMix(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        cancelPendingPlay()
        mixLoading = true
        playJob = viewModelScope.launch {
            try { block() }
            finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) mixLoading = false }
        }
    }

    private fun playChecked(songs: List<Track>, index: Int) {
        // Source selection, offline staging and latest-request arbitration belong to the service.
        player.play(songs, index, playingFrom)
    }

    /** Bumped when the player should open (a tap on the media notification). */
    var systemPlayerRequested by mutableStateOf(savedState.get<Boolean>("system_player_requested") == true)
        private set
    internal suspend fun lastPlayedPreview() = withContext(Dispatchers.IO) { player.lastPlayedPreview() }

    fun openPlayerFromSystem() {
        systemPlayerRequested = true
        savedState["system_player_requested"] = true
    }
    fun dismissSystemPlayerRequest() {
        systemPlayerRequested = false
        savedState["system_player_requested"] = false
    }

    var playerRequest by mutableStateOf(0); private set
    fun requestPlayer() { playerRequest++ }
    fun consumePlayerRequest() { playerRequest = 0 }

    var downloadRequest by mutableStateOf<Int?>(null); private set
    fun requestDownloads(id: Int) { downloadRequest = id }
    fun dismissDownloads() { downloadRequest = null }

    /** Bumped when Play had to leave songs out (or play nothing) because the phone is offline. */
    var offlineNotice by mutableStateOf(0); private set

    /** What the queue was started from, when the screen that started it said; null for the owner's own album lists. */
    var playingFrom by mutableStateOf<String?>(null); private set

    // "Start mix": YouTube Music's radio for a song, cleaned (see Mix), endless while it plays.
    private var mixRun: PlaybackMix.Run?
        get() = graph.mix.run
        set(value) { graph.mix.run = value }
    /** The same mix context is visible after the screen reconnects to ongoing playback. */
    var mixLabel: String?
        get() = graph.mix.label
        private set(value) { graph.mix.label = value }
    /** True while the first page of a mix is being found. */
    var mixLoading by mutableStateOf(false); private set
    internal data class MixFailure(val message: String, val retry: () -> Unit)
    internal var mixFailure by mutableStateOf<MixFailure?>(null); private set
    internal fun dismissMixFailure() { mixFailure = null }

    // "Don't recommend": YouTube Music songs the owner never wants offered again, by their video
    // id and by the song (so another upload of it does not slip through). Kept on the phone.
    private val notForMe = getApplication<Application>().getSharedPreferences("not_recommended", android.content.Context.MODE_PRIVATE)
    var excluded by mutableStateOf(notForMe.getStringSet("keys", emptySet()).orEmpty()); private set

    /** Never offers [track] again; out of the queue too, skipping it if it plays. */
    fun dontRecommend(track: Track) {
        excluded = excluded + track.id.value + Mix.songKey(track)
        notForMe.edit().putStringSet("keys", excluded).apply()
        val state = playing.value
        state.queue.drop(state.queueIndex + 1).filter { it.mediaId == track.id.value }.forEach { player.removeQueueItem(it.key) }
        if (state.mediaId == track.id.value) player.next()
        mixRun?.seen?.addAll(listOf(track.id.value, Mix.songKey(track)))
    }

    /** Songs played lately, as mix keys, so a mix does not bring them straight back. */
    private fun recentKeys(): Set<String> = recentPlayed() + excluded

    private fun recentPlayed(): Set<String> {
        val byRecording = catalogue.value?.songs?.associateBy { it.statsKey }.orEmpty()
        return stats.value.entries.filter { it.value.lastPlayedAt != null }.sortedByDescending { it.value.lastPlayedAt }.take(50)
            .mapNotNull { byRecording[it.key]?.let(Mix::songKey) }.toSet()
    }

    /**
     * Starts a mix from [seed]: YouTube Music's radio for it when online, else the library. A song
     * already playing keeps playing and the mix replaces what comes after it.
     */
    fun startMix(seed: Track) = prepareMix { playMix(seed, "Mix from ${seed.title}") }

    private suspend fun playMix(seed: Track, label: String) {
        val atRequest = playing.value
        val seedOccurrence = atRequest.queue.getOrNull(atRequest.queueIndex)?.takeIf {
            it.mediaId == seed.id.value || seed.matchesPlayback(atRequest.track)
        }?.key
        val recent = withContext(Dispatchers.Default) { recentPlayed() }
        val excludedSongs = excluded
        val yt = graph.youTube
        val indexed = (catalogue.value ?: library.value)
        val linked = indexed?.let { it.byId[seed.id] ?: it.localCopiesById[seed.id] }
        var video = Mix.videoId(seed) ?: linked?.let(Mix::videoId)
        var page: YouTubeMusic.RadioPage? = null
        if (graph.youTubeOn.value && graph.network.value) {
            kotlinx.coroutines.withTimeoutOrNull(6_000) {
                try {
                    val id = video ?: yt.find(seed)?.let(Mix::videoId)
                    video = id
                    if (id != null) {
                        page = yt.radio(id)
                        val first = page
                        if (first?.continuation != null && first.tracks.none { it.id.value != "yt:$id" }) {
                            yt.radioMore(first.continuation)?.let { page = it }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The available local library is the fallback. */ }
            }
        }

        val seen = (excludedSongs + recent + listOfNotNull(Mix.songKey(seed), video?.let { "yt:$it" })).toMutableSet()
        val result = page
        val weights = stats.value
        val upcoming = withContext(Dispatchers.Default) {
            val radio = result?.tracks?.let { Mix.clean(it, seed, seen) }.orEmpty()
            if (radio.size >= 8) radio else {
                val files = indexed?.songs.orEmpty().filterNot(YouTubeMusic::isOnline)
                val used = radio.mapTo(HashSet(), Mix::songKey)
                radio + Mix.local(seed, files, weights, recent, size = if (radio.isEmpty()) 50 else 8 - radio.size,
                    excluded = excludedSongs + used)
            }
        }
        if (upcoming.isEmpty()) {
            mixFailure = MixFailure("No other playable songs were found for ${seed.title}.") { startMix(seed) }
            return
        }
        seen += upcoming.map(Mix::songKey)
        val state = playing.value
        val current = state.queue.getOrNull(state.queueIndex)
        if (seedOccurrence != null && seedOccurrence != current?.key) {
            mixFailure = MixFailure("The song changed while the mix was getting ready.") { startMix(seed) }
            return
        }
        val sameSeed = current != null && (state.mediaId == seed.id.value || seed.matchesPlayback(state.track))
        val run = video?.let { id ->
            PlaybackMix.Run(seed, result?.continuation, seen,
                (upcoming.map { it.id.value } + listOfNotNull(seed.id.value, state.mediaId.takeIf { sameSeed })).toMutableSet(), startFrom = id)
        }
        val origin = graph.mix.origin(run, label)
        if (current != null && sameSeed) {
            if (!player.replaceUpcomingForRadio(current.key, upcoming, origin)) {
                mixFailure = MixFailure("The queue changed or the suggested songs couldn't be opened.") { startMix(seed) }
                return
            }
            graph.mix.begin(run, label)
        } else {
            graph.mix.begin(run, label)
            player.play(listOf(seed) + upcoming, 0, origin, shuffle = false)
        }
    }

    /** Plays a YouTube Music mix ("My Mix 1", a supermix) by its playlist id, cleaned and endless like any mix. */
    fun startPlaylistRadio(playlistId: String, label: String): Unit = prepareMix {
        val page = kotlinx.coroutines.withTimeoutOrNull(8_000) {
            try { graph.youTube.playlistRadio(playlistId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        val tracks = page?.tracks.orEmpty()
        if (tracks.isEmpty()) {
            mixFailure = MixFailure("YouTube Music didn't return songs for $label.") { startPlaylistRadio(playlistId, label) }
            return@prepareMix
        }
        val first = tracks.first()
        val (queue, seen) = withContext(Dispatchers.Default) {
            val seen = (recentKeys() + Mix.songKey(first)).toMutableSet()
            (listOf(first) + Mix.clean(tracks.drop(1), first, seen)) to seen
        }
        seen += queue.map(Mix::songKey)
        val run = PlaybackMix.Run(first, page?.continuation, seen, queue.map { it.id.value }.toMutableSet())
        player.play(queue, 0, graph.mix.begin(run, label), shuffle = false)
    }


    // Deferred source changes are observed by PlaybackService even with no UI bound.

    /**
     * Plays a mood: its first YouTube Music playlist, cleaned like a mix, flowing into the radio of
     * its first song when it ends; without one, a mix grown from a library song of that mood.
     */
    internal fun startMood(mood: YouTubeMusic.Mood, page: YouTubeMusic.MoodPage?) = prepareMix {
        val yt = graph.youTube
        val listed = page?.playlists?.firstOrNull()?.let { runCatching { yt.album(it.id)?.second }.getOrNull() }.orEmpty().ifEmpty { page?.songs.orEmpty() }
        coroutineContext.ensureActive()
        if (listed.size >= 5) {
            val first = listed.first()
            val (queue, seen) = withContext(Dispatchers.Default) {
                val seen = (recentKeys() + Mix.songKey(first)).toMutableSet()
                (listOf(first) + Mix.clean(listed.drop(1), first, seen)) to seen
            }
            seen += queue.map(Mix::songKey)
            val run = PlaybackMix.Run(first, null, seen, queue.map { it.id.value }.toMutableSet(), startFrom = first.id.value.removePrefix("yt:"))
            player.play(queue, 0, graph.mix.begin(run, mood.label))
            return@prepareMix
        }
        val songs = library.value?.songs.orEmpty()
        val matches = withContext(Dispatchers.Default) { Mix.moodSongs(mood.label, songs) }
        if (matches.isEmpty()) { userError = "Nothing for \"${mood.label}\" yet: YouTube Music isn't reachable and no song in the library has a genre that fits."; return@prepareMix }
        playMix(matches.random(), mood.label)
    }

    /** Plays what a spoken or typed search finds; an empty query plays the library shuffled. */
    fun playSearch(query: String) {
        cancelPendingPlay()
        playJob = viewModelScope.launch {
            val tree = (getApplication<Application>() as ua.olex.music.MusicApp).browseTree()
            val found = withContext(Dispatchers.Default) { if (query.isBlank()) emptyList() else tree.search(query) }
            if (found.isNotEmpty()) play(found, 0) else library.value?.songs?.takeIf { it.isNotEmpty() }?.let(::playShuffled)
        }
    }

    /** Plays [list] with shuffle on, from a random song; the queue keeps its order for "shuffle off". */
    fun playShuffled(list: List<Track>) {
        if (list.isEmpty()) return
        cancelPendingPlay()
        graph.mix.stop()
        playingFrom = null
        val pick = list.indices.random()
        val now = playing.value
        val sameSong = list[pick].id.value == now.mediaId || list[pick].matchesPlayback(now.track)
        // Keep a second shuffle from starting the current recording again on a deduplicated queue.
        val start = if (sameSong && list.size > 1) {
            val other = (0 until list.lastIndex).random()
            if (other < pick) other else other + 1
        } else pick
        player.play(list, start, shuffle = true)
    }
    fun setPlaybackParameters(speed: Float, pitch: Float) = player.setPlaybackParameters(speed, pitch)
    fun toggle() = player.toggle()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun setSleepTimer(minutes: Int) = player.setSleepTimer(minutes)
    fun extendSleepTimer(ms: Long) = player.extendSleepTimer(ms)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun playQueueItem(key: String) {
        cancelPendingPlay()
        player.playQueueItem(key)
    }
    fun moveQueueItem(key: String, delta: Int) = player.moveQueueItem(key, delta)
    fun removeQueueItem(key: String) = player.removeQueueItem(key)

    // Launcher shortcuts: each waits for what it needs (the player, the library), then acts once.
    /** Bumped when Search should open with the keyboard (the Search shortcut). */
    var searchRequest by mutableStateOf(0); private set
    fun requestSearch() { searchRequest++ }
    fun consumeSearchRequest() { searchRequest = 0 }
    fun resumeFromShortcut() = viewModelScope.launch {
        val state = kotlinx.coroutines.withTimeoutOrNull(5_000) { player.state.first { it.connected } } ?: return@launch
        if (!state.playWhenReady && state.hasTrack) player.toggle()
        requestPlayer()
    }
    fun shuffleAllFromShortcut() = viewModelScope.launch {
        val songs = kotlinx.coroutines.withTimeoutOrNull(10_000) { library.first { it != null } }?.songs.orEmpty()
        kotlinx.coroutines.withTimeoutOrNull(5_000) { player.state.first { it.connected } } ?: return@launch
        if (songs.isNotEmpty()) { playShuffled(songs); requestPlayer() }
    }
    fun likedFromShortcut() = viewModelScope.launch {
        val liked = kotlinx.coroutines.withTimeoutOrNull(10_000) { personal.first { it.favourites.isNotEmpty() } }?.favourites?.mapNotNull { it.track }.orEmpty()
        kotlinx.coroutines.withTimeoutOrNull(5_000) { player.state.first { it.connected } } ?: return@launch
        if (liked.isNotEmpty()) { playFrom(liked, 0, null); requestPlayer() }
    }

    /** Clears every song after the one playing, last first so the keys stay valid. */
    fun clearUpNext() {
        val state = playing.value
        state.queue.drop(state.queueIndex + 1).asReversed().forEach { player.removeQueueItem(it.key) }
    }
    fun enqueue(tracks: List<Track>, next: Boolean) {
        player.enqueue(tracks, next)
    }
    fun setEqualizer(routeKey: String, settings: ua.olex.music.dsp.EqualizerSettings) = player.setEqualizer(routeKey, settings)
    fun setReplayGain(settings: ua.olex.music.dsp.ReplayGainSettings) = player.setReplayGain(settings)
    fun dismissQueueError() = player.dismissQueueError()

    private fun hasAccess(): Boolean {
        val permitted = getApplication<Application>().checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        graph.audioAccess.value = permitted
        return permitted
    }
}

/** Results carry their query so the screen never labels old matches as a newer search. */
data class LibrarySearchResult(val query: String, val tracks: List<Track>, val failed: Boolean = false)

/** YouTube Music's answer to a search: songs not in the library. */
internal data class OnlineSearch(val query: String, val tracks: List<Track>, val failed: Boolean = false,
    val artists: List<YouTubeMusic.Card> = emptyList(), val albums: List<YouTubeMusic.Card> = emptyList())
