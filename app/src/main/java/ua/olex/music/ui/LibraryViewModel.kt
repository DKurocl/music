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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import ua.olex.music.user.UserRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import ua.olex.music.graph
import ua.olex.music.model.Track
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
    val library: StateFlow<LibraryIndex?> = graph.library.index.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
            else graph.library.search(text).map { LibrarySearchResult(text, it) }
                .catch { emit(LibrarySearchResult(text, emptyList(), failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) { savedState["search"] = text }
    fun retrySearch() { searchRetry.update { it + 1 } }

    val personal: StateFlow<PersonalLibraryState> = combine(library, graph.userLibrary.favourites, graph.userLibrary.playlists, graph.userLibrary.history) { index, favourites, playlists, history ->
        PersonalLibraryState.resolve(index ?: LibraryIndex.EMPTY, favourites, playlists, history)
    }.flowOn(Dispatchers.Default).catch {
        userError = "Couldn't open your personal library. Your saved data has not been reset."
        emit(PersonalLibraryState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalLibraryState())

    val musicFolders = graph.musicFiles.folders
    private val lyricTrack = combine(library, playing.map { it.mediaId }.distinctUntilChanged()) { index, id ->
        id?.let { index?.byId?.get(ua.olex.music.model.TrackId(it)) }
    }.distinctUntilChanged()
    val localLyrics = combine(lyricTrack, graph.musicFiles.revision, graph.downloadedLyrics.revision) { track, revision, _ -> track to revision }
        .mapLatest { (track, _) ->
            if (track == null) return@mapLatest LocalLyricsState()
            graph.downloadedLyrics.edited(track)?.let(ua.olex.music.lyrics.Lyrics::parse)?.takeIf { it.lines.isNotEmpty() }?.let {
                return@mapLatest LocalLyricsState(it, "Your timing", mediaId = track.id.value, edited = true)
            }
            val embedded = track.metadata?.lyrics?.let(ua.olex.music.lyrics.Lyrics::parse) ?: ua.olex.music.lyrics.Lyrics.EMPTY
            val downloaded = graph.downloadedLyrics.read(track)?.let { (text, _) -> ua.olex.music.lyrics.Lyrics.parse(text) }?.takeIf { it.lines.isNotEmpty() }
            val fallback = if (downloaded != null && (embedded.lines.isEmpty() || downloaded.sync.ordinal >= embedded.sync.ordinal))
                LocalLyricsState(downloaded, "Downloaded lyrics", mediaId = track.id.value)
                else LocalLyricsState(embedded, if (embedded.lines.isEmpty()) "No local lyrics" else "Embedded lyrics", mediaId = track.id.value)
            try {
                val sidecar = graph.musicFiles.lyrics(track.path)
                if (sidecar.status == ua.olex.music.library.SidecarStatus.FOUND) {
                    val parsed = sidecar.text?.let(ua.olex.music.lyrics.Lyrics::parse) ?: ua.olex.music.lyrics.Lyrics.EMPTY
                    if (parsed.lines.isNotEmpty()) fallback.copy(lyrics = parsed, source = "Sidecar: ${sidecar.name}")
                    else fallback.copy(note = "The sidecar is empty or invalid. Showing embedded lyrics when available.")
                } else fallback.copy(note = if (track.path.isNullOrBlank()) "This song has no library file path for sidecar lookup." else when (sidecar.status) {
                    ua.olex.music.library.SidecarStatus.NO_FOLDER -> "Choose a music folder to read lyrics beside your files."
                    ua.olex.music.library.SidecarStatus.OUTSIDE_FOLDER -> "Choose a folder containing this song to read its sidecar lyrics."
                    ua.olex.music.library.SidecarStatus.AMBIGUOUS -> "Several sidecar files match. No file was chosen."
                    else -> null
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { fallback.copy(note = "Folder access was revoked. Choose the music folder again.") }
            catch (_: Exception) { fallback.copy(note = "Couldn't read the sidecar. Use UTF-8 lyrics up to 1 MB, then Reload.") }
        }.map { state ->
            val track = state.mediaId?.let { library.value?.byId?.get(ua.olex.music.model.TrackId(it)) }
            state.copy(lyrics = ua.olex.music.lyrics.LyricsCleanup.clean(state.lyrics, track?.title, track?.artist))
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalLyricsState())

    /** A moving cover for the playing song, when its folder has one. */
    val motionCover = combine(lyricTrack, graph.musicFiles.revision) { track, _ -> track }
        .mapLatest { track -> runCatching { graph.musicFiles.motionCover(track?.path) }.getOrNull() }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reloadLyrics() = refreshLocalFiles(rescan = false)

    /** Saves hand-fixed timing for the playing song (app storage, wins over other sources). */
    fun saveLyricTiming(lyrics: ua.olex.music.lyrics.Lyrics) {
        val track = lyricTrackNow() ?: return
        viewModelScope.launch(Dispatchers.IO) { graph.downloadedLyrics.saveEdited(track, ua.olex.music.lyrics.LrcWriter.write(lyrics)) }
    }
    fun resetLyricTiming() {
        val track = lyricTrackNow() ?: return
        viewModelScope.launch(Dispatchers.IO) { graph.downloadedLyrics.deleteEdited(track) }
    }
    private fun lyricTrackNow() = playing.value.mediaId?.let { library.value?.byId?.get(ua.olex.music.model.TrackId(it)) }

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
        val id = playing.value.mediaId ?: return
        val track = library.value?.byId?.get(ua.olex.music.model.TrackId(id)) ?: return
        onlineLyricsStatus = "Searching online…"
        viewModelScope.launch {
            onlineLyricsStatus = try {
                graph.downloadedLyrics.fetch(track)?.let { found ->
                    // Kept in the file too, replacing what it had, so other players and a reinstall see them.
                    if ('#' !in track.id.value) lyricsForFile = track to found.text
                    "Found on ${found.source}"
                } ?: "No lyrics found online for this song."
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Couldn't reach the lyrics services." }
        }
    }
    fun rescanManually() = refreshLocalFiles(rescan = true)
    private fun refreshLocalFiles(rescan: Boolean) { viewModelScope.launch {
        try { graph.musicFiles.refresh(); if (rescan) refresh() }
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

    val lyricOffset = combine(library, playing) { index, state ->
        state.mediaId?.let { index?.byId?.get(ua.olex.music.model.TrackId(it)) }?.let(ua.olex.music.model.SavedSong::from)
    }.distinctUntilChanged().flatMapLatest { song -> song?.let(graph.userLibrary::lyricOffset) ?: flowOf(0L) }
        .catch { emit(0L) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setLyricOffset(mediaId: String, offsetMs: Long) {
        if (playing.value.mediaId != mediaId) return
        val track = playing.value.mediaId?.let { library.value?.byId?.get(ua.olex.music.model.TrackId(it)) } ?: return
        editUserData { setLyricOffset(track, offsetMs.coerceIn(-60_000, 60_000)) }
    }

    /** Plays and ratings per song identity (SongKey value). */
    /** Ratings in the files count when the app has none of its own, so they survive a reinstall. */
    val stats: StateFlow<Map<String, ua.olex.music.model.SongStats>> = combine(graph.userLibrary.stats.catch { emit(emptyMap()) }, library) { own, index ->
        val fromFiles = index?.songs?.mapNotNull { t -> t.metadata?.rating?.let { t.key.value to it } }.orEmpty()
        if (fromFiles.isEmpty()) own else own.toMutableMap().apply {
            for ((key, stars) in fromFiles) { val s = get(key) ?: ua.olex.music.model.SongStats.NONE; if (s.rating == 0) put(key, s.copy(rating = stars)) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Whether star ratings are also written into the files (FLAC RATING, MP3 POPM). */
    var ratingsToFiles by mutableStateOf(prefs.getBoolean("ratings_to_files", false)); private set
    fun changeRatingsToFiles(on: Boolean) { ratingsToFiles = on; prefs.edit().putBoolean("ratings_to_files", on).apply() }

    /** Built-in and the owner's smart playlists, worked out off the main thread whenever their inputs change. */
    val smart: StateFlow<List<SmartPlaylistUi>> = combine(library, stats, graph.userLibrary.smartPlaylists.catch { emit(emptyList()) }, personal) { index, stats, own, personal ->
        val songs = index?.songs ?: return@combine emptyList()
        val now = System.currentTimeMillis()
        val seed = now / 86_400_000L // a shuffled list keeps its order for the day
        (ua.olex.music.model.SmartPlaylist.BUILT_IN + own).map { p ->
            SmartPlaylistUi(p, p.evaluate(songs, { stats[it.key.value] ?: ua.olex.music.model.SongStats.NONE }, { it.id.value in personal.favouriteByTrackId }, now, seed))
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
        remove = { id, song, position -> editUserData { removeFromPlaylist(id, song, position) } },
        move = { id, song, delta, position -> editUserData { moveInPlaylist(id, song, delta, position) } },
        removeFavourite = { id -> editUserData { removeFavourite(id) } },
    )

    fun toggleFavourite(track: Track) {
        val saved = personal.value.favouriteByTrackId[track.id.value]
        editUserData { toggleFavourite(track, saved?.id) }
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

    val positionMs: Long get() = player.positionMs

    /** Called when the app comes forward and after the permission dialog. */
    fun refresh() {
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
            return
        }
        rescanPending = false
        scanning = true
        scanError = null
        if (watchingRequested) startWatching()
        scanJob = viewModelScope.launch {
            try {
                graph.library.rescan()
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
                if (rescanPending && watchJob?.isActive == true) refresh()
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

    fun play(list: List<Track>, index: Int) = player.play(list, index)
    /** Plays what a spoken or typed search finds; an empty query plays the library shuffled. */
    fun playSearch(query: String) {
        viewModelScope.launch {
            val tree = (getApplication<Application>() as ua.olex.music.MusicApp).browseTree()
            val found = if (query.isBlank()) emptyList() else tree.search(query)
            if (found.isNotEmpty()) play(found, 0) else library.value?.songs?.takeIf { it.isNotEmpty() }?.let(::playShuffled)
        }
    }

    /** Plays [list] with shuffle on, from a random song; the queue keeps its order for "shuffle off". */
    fun playShuffled(list: List<Track>) {
        if (list.isEmpty()) return
        if (!playing.value.shuffle) player.toggleShuffle()
        player.play(list, list.indices.random())
    }
    fun toggle() = player.toggle()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun setSleepTimer(minutes: Int) = player.setSleepTimer(minutes)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun playQueueItem(key: String) = player.playQueueItem(key)
    fun moveQueueItem(key: String, delta: Int) = player.moveQueueItem(key, delta)
    fun removeQueueItem(key: String) = player.removeQueueItem(key)
    fun enqueue(tracks: List<Track>, next: Boolean) = player.enqueue(tracks, next)
    fun setEqualizer(routeKey: String, settings: ua.olex.music.dsp.EqualizerSettings) = player.setEqualizer(routeKey, settings)
    fun setReplayGain(settings: ua.olex.music.dsp.ReplayGainSettings) = player.setReplayGain(settings)
    fun dismissQueueError() = player.dismissQueueError()

    private fun hasAccess() =
        getApplication<Application>().checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** Results carry their query so the screen never labels old matches as a newer search. */
data class LibrarySearchResult(val query: String, val tracks: List<Track>, val failed: Boolean = false)
