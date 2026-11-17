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
    val library: StateFlow<LibraryIndex?> = graph.library.index.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** The scanned files plus durable online songs; never built in composition or from network pages. */
    val catalogue: StateFlow<LibraryIndex?> = combine(library, graph.userLibrary.catalogue) { local, saved ->
        ua.olex.music.model.SavedCatalog.merge(local ?: LibraryIndex.EMPTY, saved)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
                LibrarySearchResult(text, (local + ua.olex.music.model.SavedCatalog.searchOnline(saved ?: LibraryIndex.EMPTY, text)).distinctBy { it.id })
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
        .combine(searchRetry) { request, _ -> request }
        .mapLatest { (text, on) ->
            if (!on || text.trim().length < 2) return@mapLatest null
            try {
                val found = graph.youTube.searchAll(text.trim())
                OnlineSearch(text, found.songs, artists = found.artists, albums = found.albums)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OnlineSearch(text, emptyList(), failed = true) }
        }.combine(catalogue) { found, catalogue ->
            if (found == null) return@combine null
            val index = catalogue ?: LibraryIndex.EMPTY
            val owned = YouTubeMusic.indexOf(index)
            val mineArtists = index.artists.mapTo(HashSet()) { ua.olex.music.model.TextFold.fold(it.name) }
            val mineAlbums = index.albums.groupBy({ ua.olex.music.model.TextFold.fold(it.name) }, { ua.olex.music.model.TextFold.fold(it.subtitle.orEmpty()) })
            found.copy(
                tracks = found.tracks.filter { it.id !in index.byId && owned.copyOf(it) == null },
                artists = found.artists.filterNot { ua.olex.music.model.TextFold.fold(it.title) in mineArtists },
                albums = found.albums.filterNot { card ->
                    val by = ua.olex.music.model.TextFold.fold(card.subtitle.orEmpty())
                    mineAlbums[ua.olex.music.model.TextFold.fold(card.title)].orEmpty().any { artist -> artist.isNotBlank() && by.contains(artist.substringBefore(",").trim()) }
                },
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun retrySearch() { searchRetry.update { it + 1 } }

    val personal: StateFlow<PersonalLibraryState> = combine(library, graph.userLibrary.favourites, graph.userLibrary.playlists, graph.userLibrary.history) { index, favourites, playlists, history ->
        PersonalLibraryState.resolve(index ?: LibraryIndex.EMPTY, favourites, playlists, history)
    }.flowOn(Dispatchers.Default).catch {
        userError = "Couldn't open your personal library. Your saved data has not been reset."
        emit(PersonalLibraryState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalLibraryState())

    val musicFolders = graph.musicFiles.folders
    /** The playing song: the library's file, or, for a YouTube Music song, what the player knows of it. */
    private fun playingTrack(state: PlayerState = playing.value, index: LibraryIndex? = library.value): Track? {
        val id = state.mediaId ?: return null
        return index?.byId?.get(TrackId(id)) ?: if (id.startsWith("yt:")) Track(TrackId(id), "${ua.olex.music.playback.OnlineStreams.SCHEME}://${id.removePrefix("yt:")}",
            state.title, state.artist, album = state.album, durationMs = state.durationMs, artworkUri = state.artworkUri) else null
    }
    private val lyricTrack = combine(library, playing.distinctUntilChanged { a, b -> a.mediaId == b.mediaId && a.title == b.title }) { index, state ->
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

    /** A moving cover for the playing song, when its folder has one. */
    val motionCover = combine(lyricTrack, graph.musicFiles.revision, graph.motionCovers.revision) { track, _, _ -> track }
        .mapLatest { track ->
            // The album's own file first, then a cover.mp4 beside the songs; with neither, Apple
            // Music is asked once for the album (with the owner's key, on Wi-Fi unless allowed).
            track?.let { graph.motionCovers.file(it) }?.let { android.net.Uri.fromFile(it).toString() }
                ?: runCatching { graph.musicFiles.motionCover(track?.path) }.getOrNull()
                ?: run { if (track != null) fetchAppleMotion(track); null }
        }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun fetchAppleMotion(track: ua.olex.music.model.Track) {
        val apple = graph.appleMotion
        if (!apple.enabled) return
        val net = getApplication<android.app.Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val caps = net?.getNetworkCapabilities(net.activeNetwork) ?: return
        if (apple.wifiOnly && !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { AuxiliaryLoads.run { apple.find(track) } }.getOrNull() ?: return@launch
            graph.motionCovers.attachBytes(track, bytes)
        }
    }

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
    private fun lyricTrackNow() = playingTrack()

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
                graph.downloadedLyrics.fetch(track)?.let { found ->
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

    val lyricOffset = lyricTrack.map { it?.let(ua.olex.music.model.SavedSong::from) }
        .distinctUntilChanged().flatMapLatest { song -> song?.let(graph.userLibrary::lyricOffset) ?: flowOf(0L) }
        .catch { emit(0L) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setLyricOffset(mediaId: String, offsetMs: Long) {
        if (playing.value.mediaId != mediaId) return
        val track = playingTrack() ?: return
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
    val smart: StateFlow<List<SmartPlaylistUi>> = combine(catalogue, stats, graph.userLibrary.smartPlaylists.catch { emit(emptyList()) }, personal) { index, stats, own, personal ->
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
        // A YouTube Music song: the same heart on the owner's YouTube Music account, when signed in.
        // Kept as pending until YouTube Music confirms it, and sent again when the network returns.
        if (YouTubeMusic.isOnline(track)) {
            val id = track.id.value.removePrefix("yt:")
            pendingLikes.edit().putBoolean(id, saved == null).apply()
            viewModelScope.launch { sendLikes() }
        }
    }

    private val pendingLikes = getApplication<Application>().getSharedPreferences("pending_likes", android.content.Context.MODE_PRIVATE)
    private suspend fun sendLikes() {
        if (!graph.youTubeAccount.signedIn.value || !graph.network.value) return
        for ((id, liked) in pendingLikes.all) {
            if (liked !is Boolean) continue
            if (graph.youTube.like(id, liked)) pendingLikes.edit().remove(id).apply()
        }
    }
    init {
        viewModelScope.launch { graph.network.collect { online -> if (online) sendLikes() } }
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

    /** Plays a list; any online song the owner also has as a file plays from the file. */
    fun play(list: List<Track>, index: Int) = playFrom(list, index, null)

    /** Plays a list and remembers what it is ("Top Hits", an artist, "Search") for the player's "Playing from". */
    fun playFrom(list: List<Track>, index: Int, from: String?) {
        playingFrom = from
        // The last choice wins: a slower earlier one (an online list still being matched) is dropped,
        // so it can never replace what the owner picked after it.
        playJob?.cancel()
        // All online: swap in the owner's files off the main thread (the index may still be building
        // right after a start); a list of files plays at once.
        if (list.none(YouTubeMusic::isOnline)) { playJob = null; player.play(list, index, from); return }
        playJob = viewModelScope.launch {
            val songs = kotlinx.coroutines.withContext(Dispatchers.Default) { localFirst(list) }
            kotlinx.coroutines.yield()
            playChecked(songs, index)
        }
    }
    private var playJob: Job? = null

    private fun playChecked(songs: List<Track>, index: Int) {
        if (graph.network.value || songs.none(YouTubeMusic::isOnline)) { player.play(songs, index, playingFrom); return }
        // Keep every occurrence in place. Only the starting index changes while offline.
        val playable = songs.withIndex().filter { (_, t) -> !YouTubeMusic.isOnline(t) || ua.olex.music.playback.CacheBudget.isStreamCached(t.uri) }
        if (playable.isEmpty()) { offlineNotice++; return }
        val start = playable.firstOrNull { it.index >= index }?.index ?: playable.first().index
        player.play(songs, start, playingFrom)
        if (playable.size < songs.size) offlineNotice++
    }

    /** Bumped when the player should open (a tap on the media notification). */
    var playerRequest by mutableStateOf(0); private set
    fun requestPlayer() { playerRequest++ }

    /** Bumped when Play had to leave songs out (or play nothing) because the phone is offline. */
    var offlineNotice by mutableStateOf(0); private set

    /** What the queue was started from, when the screen that started it said; null for the owner's own album lists. */
    var playingFrom by mutableStateOf<String?>(null); private set

    private fun localFirst(list: List<Track>): List<Track> {
        if (list.none(YouTubeMusic::isOnline)) return list
        val index = localIndex() ?: return list
        return list.map { if (YouTubeMusic.isOnline(it)) index.copyOf(it) ?: it else it }
    }

    // The library indexed by title and artist (YouTubeMusic.indexOf), rebuilt only when the library itself changes.
    private fun localIndex(): YouTubeMusic.Companion.Index? = library.value?.let(YouTubeMusic::indexOf)
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
        val byId = library.value?.songs?.associateBy { it.id.value }.orEmpty()
        return stats.value.entries.filter { it.value.lastPlayedAt != null }.sortedByDescending { it.value.lastPlayedAt }.take(50)
            .mapNotNull { byId[it.key]?.let(Mix::songKey) }.toSet()
    }

    /**
     * Starts a mix from [seed]: YouTube Music's radio for it when online, else the library. A song
     * already playing keeps playing and the mix replaces what comes after it.
     */
    fun startMix(seed: Track) {
        viewModelScope.launch {
            mixLoading = true
            try {
                val recent = recentKeys()
                val yt = graph.youTube
                val page = if (!graph.youTubeOn.value) null else runCatching {
                    val id = if (YouTubeMusic.isOnline(seed)) seed.id.value.removePrefix("yt:") else yt.find(seed)?.id?.value?.removePrefix("yt:")
                    id?.let { yt.radio(it) }
                }.getOrNull()
                // Cleaning, matching against the library and the local fallback run off the main
                // thread: with a large library they are the part that made the screen stall.
                val seen = (recent + Mix.songKey(seed)).toMutableSet()
                val stats = stats.value
                val (online, upcoming) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val online = page?.tracks?.let { Mix.clean(it, seed, seen) }.orEmpty()
                    val songs = library.value?.songs.orEmpty()
                    online to localFirst(if (online.size >= 5) online else Mix.local(seed, songs, stats, recent))
                }
                if (upcoming.isEmpty()) { userError = "Couldn't make a mix from this song."; return@launch }
                seen += upcoming.map(Mix::songKey)
                val state = playing.value
                val current = state.mediaId
                val seedHere = localFirst(listOf(seed)).first()
                if (current != null && (current == seedHere.id.value || current == seed.id.value)) {
                    // Keep the song playing; replace what follows it.
                    player.clearUpcoming()
                    player.enqueue(upcoming, false)
                } else player.play(listOf(seedHere) + upcoming, 0)
                mixRun = if (online.size >= 5) PlaybackMix.Run(seed, page?.continuation, seen, (upcoming.map { it.id.value } + seedHere.id.value + seed.id.value).toMutableSet()) else null
                mixLabel = "Mix from ${seed.title}"
            } finally { mixLoading = false }
        }
    }

    /** Plays a YouTube Music mix ("My Mix 1", a supermix) by its playlist id, cleaned and endless like any mix. */
    fun startPlaylistRadio(playlistId: String, label: String) {
        viewModelScope.launch {
            mixLoading = true
            try {
                val page = runCatching { graph.youTube.playlistRadio(playlistId) }.getOrNull()
                val tracks = page?.tracks.orEmpty()
                if (tracks.isEmpty()) { userError = "YouTube Music didn't answer. Check the connection."; return@launch }
                val first = tracks.first()
                val seen = (recentKeys() + Mix.songKey(first)).toMutableSet()
                val queue = localFirst(listOf(first) + Mix.clean(tracks.drop(1), first, seen))
                seen += queue.map(Mix::songKey)
                player.play(queue, 0)
                mixRun = PlaybackMix.Run(first, page?.continuation, seen, queue.map { it.id.value }.toMutableSet())
                mixLabel = label
            } finally { mixLoading = false }
        }
    }

    // The title/artist index of each new library is built here, off the main thread, so online pages
    // and Search only look songs up in it (YouTubeMusic.indexOf) and never build it while drawing.
    init { viewModelScope.launch(Dispatchers.Default) { library.collect { it?.let(YouTubeMusic::indexOf) } } }

    // A song downloaded while it plays (or waits in the queue) switches to the file by itself, at the
    // same second: the library sees the new file, and every online song it now has is swapped.
    init {
        viewModelScope.launch {
            library.collect { lib ->
                if (lib == null) return@collect
                val state = playing.value
                val online = state.queue.drop(state.queueIndex.coerceAtLeast(0)).filter { it.mediaId.startsWith("yt:") }
                if (online.isEmpty()) return@collect
                val index = kotlinx.coroutines.withContext(Dispatchers.Default) { localIndex() } ?: return@collect
                // All at once (one queue change, not one per song), each keeping its place; the song
                // playing now keeps playing from the stream (PlayerConnection.replaceQueueItems).
                val swaps = online.mapNotNull { item ->
                    val duration = if (item.mediaId == state.mediaId) state.durationMs else 0L
                    index.copyOf(Track(TrackId(item.mediaId), "", item.title, item.artist, durationMs = duration))?.let { item.key to it }
                }.toMap()
                player.replaceQueueItems(swaps)
            }
        }
    }

    /**
     * Plays a mood: its first YouTube Music playlist, cleaned like a mix, flowing into the radio of
     * its first song when it ends; without one, a mix grown from a library song of that mood.
     */
    internal fun startMood(mood: YouTubeMusic.Mood, page: YouTubeMusic.MoodPage?) {
        viewModelScope.launch {
            mixLoading = true
            try {
                val yt = graph.youTube
                val listed = page?.playlists?.firstOrNull()?.let { runCatching { yt.album(it.id)?.second }.getOrNull() }.orEmpty().ifEmpty { page?.songs.orEmpty() }
                if (listed.size >= 5) {
                    val first = listed.first()
                    val seen = (recentKeys() + Mix.songKey(first)).toMutableSet()
                    val queue = localFirst(listOf(first) + Mix.clean(listed.drop(1), first, seen))
                    seen += queue.map(Mix::songKey)
                    player.play(queue, 0)
                    mixRun = PlaybackMix.Run(first, null, seen, queue.map { it.id.value }.toMutableSet(), startFrom = first.id.value.removePrefix("yt:"))
                    mixLabel = mood.label
                    return@launch
                }
                val songs = library.value?.songs.orEmpty()
                val matches = Mix.moodSongs(mood.label, songs)
                if (matches.isEmpty()) { userError = "Nothing for \"${mood.label}\" yet: YouTube Music isn't reachable and no song in the library has a genre that fits."; return@launch }
                startMix(matches.random())
                mixLabel = mood.label
            } finally { mixLoading = false }
        }
    }

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
    fun enqueue(tracks: List<Track>, next: Boolean) {
        if (tracks.none(YouTubeMusic::isOnline)) player.enqueue(tracks, next)
        else viewModelScope.launch { player.enqueue(kotlinx.coroutines.withContext(Dispatchers.Default) { localFirst(tracks) }, next) }
    }
    fun setEqualizer(routeKey: String, settings: ua.olex.music.dsp.EqualizerSettings) = player.setEqualizer(routeKey, settings)
    fun setReplayGain(settings: ua.olex.music.dsp.ReplayGainSettings) = player.setReplayGain(settings)
    fun dismissQueueError() = player.dismissQueueError()

    private fun hasAccess() =
        getApplication<Application>().checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** Results carry their query so the screen never labels old matches as a newer search. */
data class LibrarySearchResult(val query: String, val tracks: List<Track>, val failed: Boolean = false)

/** YouTube Music's answer to a search: songs not in the library. */
internal data class OnlineSearch(val query: String, val tracks: List<Track>, val failed: Boolean = false,
    val artists: List<YouTubeMusic.Card> = emptyList(), val albums: List<YouTubeMusic.Card> = emptyList())
