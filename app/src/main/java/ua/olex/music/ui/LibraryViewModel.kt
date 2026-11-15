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
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalLyricsState())

    fun reloadLyrics() = refreshLocalFiles(rescan = false)

    /** The outcome of the last tag edit, shown once. */
    var tagMessage by mutableStateOf<String?>(null); private set
    fun dismissTagMessage() { tagMessage = null }

    /** Writes the edited fields into the files (after Android's write permission), then rescans. */
    fun writeTags(tracks: List<Track>, fields: Map<String, String>) {
        val edits = tagEdits(fields)
        if (edits.isEmpty()) return
        tagMessage = "Writing tags…"
        viewModelScope.launch {
            val result = TagEditing.write(getApplication(), tracks.filter { '#' !in it.id.value }) { edits }
            tagMessage = buildString {
                append(if (result.written == 1) "Tags saved in 1 file." else "Tags saved in ${result.written} files.")
                if (result.unsupported.isNotEmpty()) append(" ${result.unsupported.size} not supported yet (only FLAC and MP3).")
                if (result.failed.isNotEmpty()) append(" ${result.failed.size} couldn't be written.")
            }
            refresh()
        }
    }

    /** Null while idle; a short status while the internet lookup runs or after it ends. */
    var onlineLyricsStatus by mutableStateOf<String?>(null); private set

    /** Looks the playing song's lyrics up on the internet (Kugou word-timed, then LRCLIB) and keeps them. */
    fun findLyricsOnline() {
        val id = playing.value.mediaId ?: return
        val track = library.value?.byId?.get(ua.olex.music.model.TrackId(id)) ?: return
        onlineLyricsStatus = "Searching online…"
        viewModelScope.launch {
            onlineLyricsStatus = try { graph.downloadedLyrics.fetch(track)?.let { "Found on $it" } ?: "No lyrics found online for this song." }
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
    val stats: StateFlow<Map<String, ua.olex.music.model.SongStats>> = graph.userLibrary.stats
        .catch { emit(emptyMap()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
        add = { id, tracks -> editUserData { addToPlaylist(id, tracks) } },
        remove = { id, song -> editUserData { removeFromPlaylist(id, song) } },
        move = { id, song, delta -> editUserData { moveInPlaylist(id, song, delta) } },
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
