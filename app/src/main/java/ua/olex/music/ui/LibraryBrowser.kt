@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.material3.SegmentedButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.olex.music.model.TrackSortMode
import ua.olex.music.model.TrackSortScope
import ua.olex.music.model.TrackSortOrder
import ua.olex.music.model.TrackSelection
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.toShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.MusicCollection
import ua.olex.music.model.Track

private val trackFilterSaver = androidx.compose.runtime.saveable.listSaver<ua.olex.music.model.TrackFilter, Any>(
    save = { listOf(it.lossless, it.hiRes, it.unplayed, it.minRating, it.decade ?: -1, it.genre.orEmpty()) },
    restore = { ua.olex.music.model.TrackFilter(it[0] as Boolean, it[1] as Boolean, it[2] as Boolean,
        it[3] as Int, (it[4] as Int).takeIf { year -> year >= 0 }, (it[5] as String).ifEmpty { null }) },
)

private enum class LibraryTab(val title: String) {
    ALBUMS("Albums"), ARTISTS("Artists"), SONGS("Songs"), PLAYLISTS("Playlists"), SMART("Smart"), FAVOURITES("Favourites"), GENRES("Genres"), FOLDERS("Folders"), YEARS("Years"), COMPOSERS("Composers"), HISTORY("History")
}

/** Where the browser is shown: the Library place (no search field), the Search place (field, no chips), or both (tests). */
internal enum class BrowserMode { ALL, LIBRARY, SEARCH }

/** The section the library opens on: Home normally; tests start on Songs. */
internal val LocalStartTab = androidx.compose.runtime.staticCompositionLocalOf { "ALBUMS" }

/** Browse one shared library snapshot or cancellable FTS results; taps play exactly the visible queue. */
@Composable
internal fun LibraryBrowser(
    library: LibraryIndex,
    insets: PaddingValues,
    query: String,
    search: LibrarySearchResult?,
    onQueryChange: (String) -> Unit,
    currentId: String?,
    onPlay: (List<Track>, Int) -> Unit,
    scanError: String?,
    onRescan: () -> Unit,
    onRetrySearch: () -> Unit = {},
    onShuffle: (List<Track>) -> Unit = {},
    smart: List<SmartPlaylistUi> = emptyList(),
    onSaveSmart: (ua.olex.music.model.SmartPlaylist) -> Unit = {},
    onDeleteSmart: (Long) -> Unit = {},
    personal: PersonalLibraryState = PersonalLibraryState(),
    personalActions: PersonalLibraryActions? = null,
    onTrackActions: (Track) -> Unit = {},
    onAddToPlaylist: (List<Track>) -> Unit = {},
    onEditTags: (List<Track>) -> Unit = {},
    onTrash: (List<Track>) -> Unit = {},
    sortOrders: Map<TrackSortScope, TrackSortOrder> = emptyMap(),
    onSortOrder: (TrackSortScope, TrackSortOrder) -> Unit = { _, _ -> },
    onEnqueue: (List<Track>, Boolean) -> Unit = { _, _ -> },
    stats: Map<String, ua.olex.music.model.SongStats> = emptyMap(),
    mode: BrowserMode = BrowserMode.ALL,
    /** Open a collection from elsewhere (Home): section name and collection id; [onOpened] clears it. */
    open: Pair<String, String>? = null, onOpened: () -> Unit = {},
    recentSearches: List<String> = emptyList(), onSearchUsed: (String) -> Unit = {}, onClearRecent: () -> Unit = {},
    /** The open collection's name once its header has scrolled away (null when none is open), for the top bar. */
    onCollection: (String?) -> Unit = {}, closeCollection: Int = 0, onCollectionClosed: () -> Unit = {},
    /** Set when something here was opened from another place: Back from it goes there. */
    onLeave: (() -> Unit)? = null,
    /** YouTube Music results for the query (null while loading). */
    online: OnlineSearch? = null, onlineOn: Boolean = false, suggestions: List<String> = emptyList(),
    /** Bumped when the Search tab is tapped again: back to the tiles and the field focused (as Google Play does). */
    focusSearch: Int = 0, onSearchFocused: () -> Unit = {},
    /** Open a Search tile from elsewhere (Home's "see all" for History and Smart playlists). */
    openTile: String? = null,
    openPlaylist: Long? = null, onOpenedPlaylist: () -> Unit = {},
    onPlayWithOrigin: ((List<Track>, Int, String) -> Unit)? = null,
) {
    val nav = LocalOnlineNav.current
    val network = LocalNetwork.current
    val startTab = LocalStartTab.current
    var tabName by rememberSaveable { mutableStateOf(if (mode == BrowserMode.LIBRARY) LibraryTab.PLAYLISTS.name else startTab) }
    LaunchedEffect(openPlaylist) { if (openPlaylist != null) tabName = LibraryTab.PLAYLISTS.name }
    var returnQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var missingFavourite by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = LibraryTab.valueOf(tabName)
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val showCollection = mode != BrowserMode.SEARCH || browsing != null || selectedId != null
    val groups = when (tab.takeIf { showCollection }) {
        LibraryTab.ALBUMS -> library.albums
        LibraryTab.ARTISTS -> library.artists
        LibraryTab.GENRES -> library.genres
        LibraryTab.FOLDERS -> library.folders
        LibraryTab.YEARS -> library.years
        LibraryTab.COMPOSERS -> library.composers
        else -> emptyList()
    }
    val selected = selectedId?.let { id -> groups.firstOrNull { it.id == id } }
    var collectionSort by rememberSaveable(tabName) { mutableStateOf(CollectionSort.NAME) }
    val shownGroups = rememberOffMain(groups, collectionSort, stats, initial = groups) {
        when (collectionSort) {
            CollectionSort.NAME -> groups
            CollectionSort.NEWEST -> groups.sortedByDescending { g -> g.tracks.maxOf { it.addedAt ?: 0 } }
            CollectionSort.YEAR -> groups.sortedByDescending { g -> g.tracks.mapNotNull { it.year }.maxOrNull() ?: 0 }
            CollectionSort.PLAYED -> groups.sortedByDescending { g -> g.tracks.sumOf { stats[it.statsKey]?.plays ?: 0 } }
        }
    }
    val searching = query.isNotBlank()
    // Albums and artists that match every word of the search, shown above the songs.
    val searchGroups = androidx.compose.runtime.key(query) { rememberOffMain(library, query, initial = emptyList<Pair<LibraryTab, MusicCollection>>()) {
        val words = ua.olex.music.model.TextFold.fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) emptyList() else {
            fun hit(c: MusicCollection, extra: String = "") = ua.olex.music.model.TextFold.fold(c.name + " " + extra).let { t -> words.all { t.contains(it) } }
            library.artists.asSequence().filter { hit(it) }.take(5).map { LibraryTab.ARTISTS to it }.toList() +
                library.albums.asSequence().filter { hit(it, it.subtitle.orEmpty()) }.take(8).map { LibraryTab.ALBUMS to it }.toList()
        }
    } }
    val sortScope = when {
        searching -> TrackSortScope.SEARCH
        selected == null -> TrackSortScope.SONGS
        tab == LibraryTab.ALBUMS -> TrackSortScope.ALBUM_TRACKS
        tab == LibraryTab.ARTISTS -> TrackSortScope.ARTIST_TRACKS
        tab == LibraryTab.GENRES -> TrackSortScope.GENRE_TRACKS
        else -> TrackSortScope.FOLDER_TRACKS
    }
    val raw = when {
        searching -> search?.takeIf { it.query == query && !it.failed }?.tracks
        selected != null -> selected.tracks
        showCollection && tab == LibraryTab.SONGS -> library.songs
        else -> null
    }
    var filter by rememberSaveable(stateSaver = trackFilterSaver) { mutableStateOf(ua.olex.music.model.TrackFilter()) }
    val filterStats = if (filter.unplayed || filter.minRating > 0) stats else null
    val visibleIds = library.byId.takeIf { searching }
    val filterRequest = raw?.let(::SortSource)?.takeIf { searching || !filter.empty }?.let {
        FilterRequest(it, filter, filterStats, visibleIds?.let(::LibraryMembership))
    }
    val filtered by produceState<FilteredTracks?>(null, filterRequest) {
        value = filterRequest?.let { request -> withContext(Dispatchers.Default) {
            val allowed = request.membership?.let { members -> request.source.tracks.filter { it.id in members.byId } }
                ?: request.source.tracks
            FilteredTracks(request, request.filter.apply(allowed) { request.stats?.get(it.statsKey) })
        } }
    }
    val unsorted = if (filterRequest == null) raw else filtered?.takeIf { it.request == filterRequest }?.tracks
    val order = sortOrders[sortScope] ?: TrackSortOrder()
    val source = unsorted?.let(::SortSource)
    val statsKey = if (order.mode == TrackSortMode.PLAYS || order.mode == TrackSortMode.RATING) stats else null
    val sorted by produceState<SortedTracks?>(null, source, order, statsKey) {
        value = if (source == null || order == TrackSortOrder()) null else withContext(Dispatchers.Default) {
            SortedTracks(source, order, order.sort(source.tracks) { stats[it.statsKey] })
        }
    }
    val visible = if (order == TrackSortOrder()) unsorted else sorted?.takeIf { it.source == source && it.order == order }?.tracks
    val selectionContext = "${tab.name}:${selectedId.orEmpty().length}:${selectedId.orEmpty()}:${query.length}:$query"
    var picking by remember(selectionContext) { mutableStateOf(false) }
    var picked by remember(selectionContext) { mutableStateOf(TrackSelection()) }
    LaunchedEffect(source) { if (unsorted != null && picked.ids.isNotEmpty()) picked = picked.retain(unsorted) }
    fun batch(action: (List<Track>) -> Unit) { visible?.let(picked::ordered)?.takeIf { it.isNotEmpty() }?.let(action) }
    LaunchedEffect(selectedId, groups) { if (selectedId != null && selected == null) selectedId = null }
    // What was opened from outside (Home, the player): Back from it leaves to where the owner came from.
    var openedFromOutside by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { open?.let { (t, id) -> tabName = t; selectedId = id; openedFromOutside = "collection"; onOpened() } }
    // An album opened from an artist's page goes back to that artist, not to the album grid.
    var backToArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var allArtistSongs by rememberSaveable(selectedId) { mutableStateOf(false) }
    val artist = selected.takeIf { tab == LibraryTab.ARTISTS }
    val artistAlbums = if (artist == null) emptyList() else androidx.compose.runtime.key(LibraryMembership(library.byId), artist.id) {
        rememberOffMain(library, artist, initial = emptyList()) {
            val ids = artist.tracks.mapTo(HashSet()) { it.id }
            library.albums.filter { album -> album.tracks.any { it.id in ids } }
        }
    }
    val topArtistSongs = if (artist == null || allArtistSongs || visible == null || visible.size <= 5) visible.orEmpty()
        else androidx.compose.runtime.key(SortSource(visible)) {
            rememberOffMain(SortSource(visible), stats, initial = emptyList()) {
                visible.sortedByDescending { stats[it.statsKey]?.plays ?: 0 }.take(5)
            }
        }
    fun closeSelected() {
        val artist = backToArtist
        backToArtist = null
        if (artist != null && tab == LibraryTab.ALBUMS) { tabName = LibraryTab.ARTISTS.name; selectedId = artist; return }
        if (openedFromOutside == "collection" && onLeave != null) { openedFromOutside = null; selectedId = null; browsing = null; onLeave(); return }
        if (selectedId == null && mode == BrowserMode.LIBRARY && tab != LibraryTab.PLAYLISTS) { tabName = LibraryTab.PLAYLISTS.name; return }
        selectedId = null
        returnQuery?.let { returnQuery = null; onQueryChange(it) }
    }
    LaunchedEffect(closeCollection) { if (closeCollection > 0) { closeSelected(); onCollectionClosed() } }
    LaunchedEffect(openTile) { openTile?.let { t -> runCatching { BrowseTile.valueOf(t) }.getOrNull()?.let { tile -> browsing = tile.name; tabName = tile.tab.name; filter = tile.filter; openedFromOutside = "tile"; onOpened() } } }
    fun closeTile() {
        browsing = null
        filter = ua.olex.music.model.TrackFilter()
        if (openedFromOutside == "tile" && onLeave != null) { openedFromOutside = null; onLeave() }
    }
    val back = rememberLibraryBack(
        picking || searching || selected != null ||
            (mode == BrowserMode.LIBRARY && tab != LibraryTab.PLAYLISTS) ||
            (mode == BrowserMode.SEARCH && browsing != null),
    ) {
        when {
            picking -> { picking = false; picked = TrackSelection() }
            searching -> onQueryChange("")
            selected != null -> closeSelected()
            mode == BrowserMode.LIBRARY -> tabName = LibraryTab.PLAYLISTS.name
            browsing != null -> closeTile()
        }
    }
    missingFavourite?.let { id ->
        AppDialog(onDismissRequest = { missingFavourite = null }, title = { Text("Remove unavailable favourite?") },
            text = { Text("The saved entry will be removed. Your music files are not changed.") },
            confirmButton = { TextButton(onClick = { personalActions?.removeFavourite?.invoke(id); missingFavourite = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { missingFavourite = null }) { Text("Cancel") } })
    }

    val showSources = mode == BrowserMode.SEARCH && onlineOn
    var searchLibrary by rememberSaveable { mutableStateOf(false) }
    var searchKind by rememberSaveable { mutableStateOf(SearchKind.ALL.name) }
    LaunchedEffect(query.isBlank()) { if (query.isBlank()) searchKind = SearchKind.ALL.name }
    val showLibraryResults = !showSources || searchLibrary
    val resultList = androidx.compose.runtime.key(query, showLibraryResults) { androidx.compose.foundation.lazy.rememberLazyListState() }
    var fieldFocused by remember { mutableStateOf(false) }
    val browserRoute = if (searching) "results" else selected?.id ?: browsing ?: tabName
    val browserDepth = (if (browsing != null || mode == BrowserMode.LIBRARY && tab != LibraryTab.PLAYLISTS) 1 else 0) +
        if (selected != null) 1 else 0
    Column(Modifier.fillMaxSize().padding(insets).libraryBackMotion(back).arrivalMotion(browserRoute, mode, browserDepth)) {
        // A search pill, as in Google's apps, and the library's sections as chips below it.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        // The lens swings each time the field is entered, as in Google's own search fields.
        var lens by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        LaunchedEffect(focusSearch) {
            if (focusSearch > 0) { returnQuery = null; browsing = null; selectedId = null; filter = ua.olex.music.model.TrackFilter() }
        }
        val snackbar = LocalSnackbar.current
        val voiceScope = androidx.compose.runtime.rememberCoroutineScope()
        val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onQueryChange)
        }
        // Inside a tile or an album the page has its own header (as Google Play's category pages do);
        // the field comes back with Back or a second tap on Search.
        val fieldShown = mode == BrowserMode.ALL || (mode == BrowserMode.SEARCH && (searching || (browsing == null && selected == null)))
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(focusSearch, fieldShown) {
            if (focusSearch > 0 && fieldShown) {
                focus.requestFocus()
                keyboard?.show()
                onSearchFocused()
            }
        }
        if (fieldShown) androidx.compose.material3.TextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            placeholder = { Text("Search your music") },
            leadingIcon = { Symbol(Glyph.SEARCH, color = MaterialTheme.colorScheme.onSurfaceVariant, kick = lens) },
            trailingIcon = {
                if (query.isNotEmpty()) KickIconButton(onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = "Clear" }) { taps -> Symbol(Glyph.CLOSE, kick = taps) }
                else if (mode == BrowserMode.SEARCH) androidx.compose.material3.IconButton(onClick = {
                    runCatching { voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a song, artist or album")) }
                        .onFailure { voiceScope.launch { snackbar?.showSnackbar("Voice search is unavailable. Type your search instead.") } }
                }, modifier = Modifier.semantics { contentDescription = "Search by voice" }) { Symbol(Glyph.MIC, size = 22.dp) }
            },
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus)
                .onFocusChanged { if (it.isFocused) lens++ }
                .onFocusChanged { fieldFocused = it.isFocused },
        )
        if (searching && showSources) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = !searchLibrary, onClick = { searchLibrary = false },
                    label = { Text("YouTube Music") })
                androidx.compose.material3.FilterChip(selected = searchLibrary, onClick = { searchLibrary = true },
                    label = { Text("Your library") })
            }
            // As YouTube Music completes a query while it is typed: one tap fills it in.
            androidx.compose.animation.AnimatedVisibility(!searchLibrary && suggestions.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)) {
                    items(suggestions, key = { it }) { suggestion ->
                        androidx.compose.material3.SuggestionChip(onClick = { onQueryChange(suggestion) }, label = { Text(suggestion, maxLines = 1) },
                            icon = { Symbol(Glyph.SEARCH, size = 18.dp) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
        // Typing mode (a tap in the field, or a second tap on Search): only recent searches, nothing else.
        if (mode == BrowserMode.SEARCH && !searching && selected == null && browsing == null && fieldFocused) {
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            BackHandler { focusManager.clearFocus() }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                item { RecentSearches(recentSearches.take(10), onPick = onQueryChange, onClear = onClearRecent) }
            }
            return@Column
        }
        if (mode == BrowserMode.SEARCH && !searching && selected == null && browsing == null) {
            BrowseTiles(library, onGenre = { genre -> browsing = BrowseTile.GENRES.name; tabName = BrowseTile.GENRES.tab.name; filter = BrowseTile.GENRES.filter; selectedId = genre.id }) { tile ->
                browsing = tile.name; tabName = tile.tab.name; filter = tile.filter
            }
            return@Column
        }
        // Inside a tile: its name with a way back to the tiles.
        if (mode == BrowserMode.SEARCH && !searching && selected == null) browsing?.let { name ->
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                KickIconButton(onClick = { closeTile() },
                    modifier = Modifier.semantics { contentDescription = "Back to browse" }) { taps -> Symbol(Glyph.BACK, kick = taps) }
                Text(BrowseTile.valueOf(name).title, style = MaterialTheme.typography.titleLarge)
            }
        }
        if (!searching && mode == BrowserMode.ALL && selected == null) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                items(LibraryTab.entries.size) { i ->
                    val item = LibraryTab.entries[i]
                    androidx.compose.material3.FilterChip(
                        selected = item == tab, onClick = { tabName = item.name; selectedId = null },
                        label = { Text(item.title) },
                        leadingIcon = if (item == tab) ({ Symbol(Glyph.CHECK, size = 18.dp) }) else null,
                    )
                }
            }
        }
        // An album keeps its own order and a clean page; long press still selects.
        val tools = raw != null && !(selected != null && tab == LibraryTab.ALBUMS) && !picking
        if (tools && (mode != BrowserMode.SEARCH || (browsing != null && !searching))) {
            // One row: sort, then filters (song lists only), then select at the end.
            val sortChip: @Composable () -> Unit = { LibrarySortControl(sortScope, order) { onSortOrder(sortScope, it) } }
            val selectButton: @Composable () -> Unit = {
                KickIconButton(onClick = { picking = true }, modifier = Modifier.semantics { contentDescription = "Select" }) { k -> Symbol(Glyph.CHECK, kick = k) }
            }
            if (selected == null) TrackFilterRow(filter, raw.orEmpty(), leading = sortChip, trailing = selectButton) { filter = it }
            else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                sortChip(); androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)); selectButton()
            }
        }
        // Albums and artists have their own order: A to Z, newest, year, most played.
        if (!searching && selected == null && (tab == LibraryTab.ALBUMS || tab == LibraryTab.ARTISTS)) {
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CollectionSort.entries.filter { it != CollectionSort.YEAR || tab == LibraryTab.ALBUMS }.size) { i ->
                    val option = CollectionSort.entries.filter { it != CollectionSort.YEAR || tab == LibraryTab.ALBUMS }[i]
                    androidx.compose.material3.FilterChip(selected = collectionSort == option, onClick = { collectionSort = option },
                        label = { Text(option.title) }, leadingIcon = if (collectionSort == option) ({ Symbol(Glyph.CHECK, size = 18.dp) }) else null)
                }
            }
        }
        if (picking) TrackSelectionToolbar(picked.ids.size, visible?.size ?: 0, visible != null,
            onAll = { visible?.let { picked = picked.all(it) } }, onClear = { picked = TrackSelection() },
            onClose = { picking = false; picked = TrackSelection() }, onPlay = { batch { onPlay(it, 0) } },
            onNext = { batch { onEnqueue(it, true) } }, onQueue = { batch { onEnqueue(it, false) } },
            onPlaylist = { batch(onAddToPlaylist) }, onTags = { batch(onEditTags) })
        if (!searching && tab == LibraryTab.SMART) {
            if (mode == BrowserMode.LIBRARY) LaunchedEffect(Unit) { onCollection(tab.title) }
            SmartPlaylistBrowser(smart, currentId, Modifier.weight(1f), onPlay = onPlay, onShuffle = onShuffle, onTrackActions = onTrackActions,
                onSave = onSaveSmart, onDelete = onDeleteSmart)
        } else if (!searching && tab == LibraryTab.PLAYLISTS && personalActions != null) {
            PlaylistBrowser(personal.playlists, personalActions, modifier = Modifier.weight(1f), currentId = currentId, onPlay = onPlay, onTrackActions = onTrackActions, library = library,
                openPlaylist = openPlaylist, onOpenedPlaylist = onOpenedPlaylist, onPlayFrom = onPlayWithOrigin,
                shortcuts = {
                    val cs = MaterialTheme.colorScheme
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubCard("Favourites", songCount(personal.favourites.size), Glyph.FAVOURITE, androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
                            cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.weight(1f)) { tabName = LibraryTab.FAVOURITES.name }
                        val count = smart.count { it.tracks.isNotEmpty() }
                        HubCard("Smart playlists", if (count == 1) "1 list" else "$count lists", Glyph.SMART, androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
                            cs.secondaryContainer, cs.onSecondaryContainer, Modifier.weight(1f)) { tabName = LibraryTab.SMART.name }
                    }
                })
        } else Box(Modifier.weight(1f)) {
        val listState = if (searching) resultList else androidx.compose.runtime.key(tabName, selected?.id) { androidx.compose.foundation.lazy.rememberLazyListState() }
        val headerGone by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        // A list opened from Playlists (Favourites, Smart, History) names itself in the top bar with
        // Back, as Google's apps do, instead of a second title row under the place's title.
        val subList = !searching && mode == BrowserMode.LIBRARY && tab != LibraryTab.PLAYLISTS
        LaunchedEffect(selected?.id, headerGone, subList, tab) {
            onCollection(selected?.let { if (headerGone) it.name else "" } ?: if (subList) tab.title else null)
        }
        val railTracks = if (!searching && selected == null && tab == LibraryTab.SONGS && (order.mode == TrackSortMode.DEFAULT || order.mode == TrackSortMode.TITLE)) visible else null
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp + LocalPlayerInset.current + (if (picking) 80.dp else 0.dp))) {
            if (!searching || showLibraryResults) scanError?.let { item(key = "scan-error") { ErrorNotice(it, onRescan) } }
            when {
                searching && !showLibraryResults -> {
                    val found = online?.takeIf { it.query == query }
                    val webSongs = found?.tracks.orEmpty()
                    val folded = ua.olex.music.model.TextFold.fold(query)
                    // YouTube Music's own top result; else an artist named exactly as typed.
                    val top = found?.top ?: found?.artists?.firstOrNull { ua.olex.music.model.TextFold.fold(it.title) == folded }
                    val artists = found?.artists.orEmpty().filter { it.id != top?.id }
                    val albums = found?.albums.orEmpty().filter { it.id != top?.id }
                    val playlists = found?.playlists.orEmpty().filter { it.id != top?.id }
                    // Chips as YouTube Music's search has them; only the kinds that have results.
                    val kinds = buildList {
                        if (webSongs.isNotEmpty()) add(SearchKind.SONGS)
                        if (artists.isNotEmpty() || top?.kind == YouTubeMusic.Card.Kind.ARTIST) add(SearchKind.ARTISTS)
                        if (albums.isNotEmpty() || top?.kind == YouTubeMusic.Card.Kind.ALBUM) add(SearchKind.ALBUMS)
                        if (playlists.isNotEmpty() || top?.kind == YouTubeMusic.Card.Kind.PLAYLIST) add(SearchKind.PLAYLISTS)
                    }
                    val kind = SearchKind.entries.firstOrNull { it.name == searchKind && (it == SearchKind.ALL || it in kinds) } ?: SearchKind.ALL
                    when {
                        !network -> item(key = "online-offline") { BrowserMessage("You’re offline. Search your library instead.") }
                        query.trim().length < 2 -> item(key = "online-short") { BrowserMessage("Enter at least two characters.") }
                        found == null -> items(3, key = { "online-wait-$it" }) { PlaceholderRow() }
                        found.failed -> item(key = "online-failed") { ErrorNotice("Couldn't search YouTube Music.", onRetrySearch) }
                        webSongs.isEmpty() && top == null && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() ->
                            item(key = "online-empty") { BrowserMessage("No results on YouTube Music.") }
                    }
                    if (kinds.size > 1) item(key = "search-kinds", contentType = "chips") {
                        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)) {
                            items(listOf(SearchKind.ALL) + kinds, key = { it.name }) { k ->
                                androidx.compose.material3.FilterChip(selected = k == kind, onClick = { searchKind = k.name },
                                    label = { Text(k.title, maxLines = 1) }, leadingIcon = if (k == kind) ({ Symbol(Glyph.CHECK, size = 18.dp) }) else null)
                            }
                        }
                    }
                    val all = kind == SearchKind.ALL
                    if (all && top != null) item(key = "top-result", contentType = "top") {
                        SearchHeading("Top result")
                        TopResultCard(top) { onSearchUsed(query) }
                    }
                    if (all || kind == SearchKind.SONGS) {
                        // "All" shows the first songs, as YouTube Music does; the Songs chip shows every one.
                        val shown = if (all) webSongs.take(4) else webSongs
                        if (shown.isNotEmpty()) item(key = "web-heading", contentType = "title") { SearchHeading("Songs") }
                        itemsIndexed(shown, key = { _, t -> "online-" + t.id.value }, contentType = { _, _ -> "song" }) { i, track ->
                            TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) },
                                onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) }) {
                                onSearchUsed(query)
                                nav.playTrackMix?.invoke(track) ?: nav.play(webSongs, i, "Search")
                            }
                        }
                        if (all && webSongs.size > shown.size) item(key = "songs-more", contentType = "more") {
                            TextButton(onClick = { searchKind = SearchKind.SONGS.name }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Show all songs", maxLines = 1) }
                        }
                    }
                    for ((k, label, cards) in listOf(Triple(SearchKind.ARTISTS, "Artists", artists), Triple(SearchKind.ALBUMS, "Albums", albums), Triple(SearchKind.PLAYLISTS, "Playlists", playlists))) {
                        if (cards.isEmpty() || !(all || kind == k)) continue
                        item(key = "web-title-$label", contentType = "title") { SearchHeading(label) }
                        // "All" shows each kind as a row of cards; its own chip lists them all.
                        if (all) item(key = "web-rail-$label", contentType = "yt-cards") { YouTubeCards(cards, size = if (k == SearchKind.ARTISTS) 112.dp else 132.dp) }
                        else items(cards, key = { "web-${it.kind}-${it.id}" }, contentType = { "web-row" }) { card -> OnlineResultRow(card) { onSearchUsed(query) } }
                    }
                }
                searching -> {
                    val local = search?.takeIf { it.query == query }
                    val localSongs = visible.orEmpty()
                    val groups = searchGroups.groupBy { it.first }
                    if (local == null) item(key = "local-search") { BrowserMessage("Searching…") }
                    if (local?.failed == true) item(key = "local-failed") { ErrorNotice("Couldn't search your library.", onRetrySearch) }
                    for ((kind, hits) in groups) {
                        item(key = "group-title-$kind") { SearchHeading(if (kind == LibraryTab.ARTISTS) "Artists in your library" else "Albums in your library") }
                        hits.forEach { (_, collection) ->
                            item(key = "group-$kind-${collection.id}") {
                                androidx.compose.material3.ListItem(
                                    headlineContent = { Text(collection.name, maxLines = 1) },
                                    supportingContent = { Text(listOfNotNull(collection.subtitle, songCount(collection.tracks.size)).joinToString(" · "), maxLines = 1) },
                                    leadingContent = { if (kind == LibraryTab.ARTISTS) ArtistImage(collection.name, Modifier.size(56.dp), 56.dp) else Artwork(collection.artworkUri, Modifier.size(56.dp), corner = 12.dp) },
                                    // Same inset and rounded press as the song rows below, so all results share one left edge.
                                    modifier = Modifier.padding(horizontal = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).clickable { onSearchUsed(query)
                                        openedFromOutside = null; backToArtist = null; returnQuery = query
                                        tabName = kind.name; selectedId = collection.id; onQueryChange("")
                                    },
                                )
                            }
                        }
                    }
                    if (localSongs.isNotEmpty()) item(key = "songs-title") { SearchHeading("Songs in your library") }
                    itemsIndexed(localSongs, key = { _, track -> track.id.value }, contentType = { _, _ -> "song" }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                            checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }, onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) }) {
                            onSearchUsed(query)
                            nav.playTrackMix?.invoke(track) ?: onPlay(localSongs, index)
                        }
                    }
                    if (local != null && !local.failed && local.tracks.isEmpty() && groups.isEmpty())
                        item(key = "nothing") { EmptyState(Glyph.SEARCH, "Nothing found in your library",
                            "Try fewer words, or part of a title, artist or album.", shape = androidx.compose.material3.MaterialShapes.Clover4Leaf.toShape()) }
                }
                selected != null && tab == LibraryTab.ARTISTS -> {
                    // The artist's page: who they are, their albums, then their most played songs.
                    val albums = artistAlbums
                    item(key = "artist-header") {
                        ArtistHeader(selected, albums.size, showBack = mode != BrowserMode.LIBRARY, onBack = ::closeSelected, onPlay = { visible?.let { onPlay(it, 0) } },
                            onShuffle = { visible?.let(onShuffle) }, onAdd = { visible?.let(onAddToPlaylist) }, enabled = !visible.isNullOrEmpty())
                    }
                    item(key = "artist-bio") { ArtistBio(selected.name) }
                    if (albums.isNotEmpty()) {
                        item(key = "artist-albums-title") { PageHeading(if (albums.size == 1) "Album" else "Albums") }
                        item(key = "artist-albums") {
                            CardRail(albums, key = { it.id }) { album ->
                                AlbumCard(album, Modifier) { backToArtist = selected.id; tabName = LibraryTab.ALBUMS.name; selectedId = album.id }
                            }
                        }
                    }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else {
                        val shown = topArtistSongs
                        item(key = "artist-songs-title") {
                            SectionHeader(if (allArtistSongs || visible.size <= 5) "Songs" else "Most played", trailing = if (visible.size > 5) ({
                                TextButton(onClick = { allArtistSongs = !allArtistSongs }) { Text(if (allArtistSongs) "Show less" else "All ${visible.size}", maxLines = 1) }
                            }) else null)
                        }
                        shown.forEachIndexed { index, track ->
                            item(key = "artist-" + track.id.value) {
                                TrackRow(track, modifier = Modifier.animateItem(), current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                                    checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }, onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) },
                                    albumArtist = selected.name) { onPlay(shown, index) }
                            }
                        }
                    }
                    item(key = "artist-online") { MoreOnYouTubeMusic(selected.name, library, currentId) }
                }
                selected != null -> {
                    item(key = "collection-header") {
                        CollectionHeader(selected, artist = tab == LibraryTab.ARTISTS, showBack = mode != BrowserMode.LIBRARY, onBack = ::closeSelected, onPlay = { visible?.let { onPlay(it, 0) } }, onShuffle = { visible?.let(onShuffle) }, onAdd = { visible?.let(onAddToPlaylist) }, enabled = !visible.isNullOrEmpty())
                    }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else {
                        // Disc headers in an album shown in its own order.
                        val discs = (if (tab == LibraryTab.ALBUMS && order == TrackSortOrder()) ua.olex.music.model.discStarts(visible) else emptyMap()).takeIf { it.size > 1 }.orEmpty()
                        // One quality for the whole album is said once, in the header.
                        val uniformQuality = visible.mapNotNull { it.format?.label }.distinct().size <= 1
                        visible.forEachIndexed { index, track ->
                            discs[index]?.let { disc -> item(key = "disc-$disc") {
                                Text("Disc $disc", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                            } }
                            item(key = track.id.value) {
                                val inAlbum = tab == LibraryTab.ALBUMS && order == TrackSortOrder()
                                TrackRow(track, modifier = Modifier.animateItem(), current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                                    checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }, onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) },
                                    number = if (inAlbum) track.trackNumber ?: (index + 1) else null, albumArtist = selected.subtitle,
                                    showQuality = !(inAlbum && uniformQuality)) { onPlay(visible, index) }
                            }
                        }
                    }
                }
                tab == LibraryTab.SONGS -> {
                    if (library.songs.isEmpty()) item { BrowserMessage("No music yet. Put music in the Music folder, then rescan.") }
                    else if (!picking) item(key = "shuffle-all") {
                        androidx.compose.material3.FilledTonalButton(onClick = { visible?.let(onShuffle) }, enabled = visible != null,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).height(48.dp)) {
                            Symbol(Glyph.SHUFFLE, size = 20.dp); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                            Text("Shuffle all ${songCount(library.songs.size)}")
                        }
                    }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else itemsIndexed(visible, key = { _, track -> track.id.value }, contentType = { _, _ -> "song" }) { index, track ->
                        TrackRow(track, modifier = Modifier.animateItem(), current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                            checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }, onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) }) { onPlay(visible, index) }
                    }
                }
                tab == LibraryTab.FAVOURITES || tab == LibraryTab.HISTORY -> {
                    val rows = if (tab == LibraryTab.FAVOURITES) personal.favourites else personal.history
                    val queue = rows.mapNotNull { it.track }.distinctBy { it.id }
                    if (rows.isEmpty()) item {
                        if (tab == LibraryTab.FAVOURITES) EmptyState(Glyph.FAVOURITE, "No favourites yet", "Tap the heart in the player, or double tap a cover.",
                            shape = androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape())
                        else EmptyState(Glyph.HISTORY, "Nothing played yet", "Songs you play appear here, newest first.")
                    }
                    items(rows, key = { it.song.id }) { row ->
                        SavedSongItem(row, currentId,
                            onPlay = { val index = queue.indexOfFirst { it.id == row.track?.id }; if (index >= 0) onPlay(queue, index) },
                            onMore = if (row.track != null) ({ onTrackActions(row.track) }) else if (tab == LibraryTab.FAVOURITES) ({ missingFavourite = row.song.id }) else null,
                        )
                    }
                }
                // Artists as round photos, three to a row.
                tab == LibraryTab.ARTISTS -> items(shownGroups.chunked(3), key = { row -> row.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { artist ->
                            Column(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                .clickable(onClickLabel = "Open ${artist.name}") {
                                    if (onlineOn) nav.open(OnlineTarget.Artist(artist.name)) else selectedId = artist.id
                                }.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                ArtistImage(artist.name, Modifier.fillMaxWidth(0.86f).aspectRatio(1f), 104.dp)
                                Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
                                Text(songCount(artist.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        repeat(3 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                    }
                }
                // Albums as a grid of covers, two to a row (four on a wide screen), as music apps show them.
                tab == LibraryTab.ALBUMS -> items(shownGroups.chunked(2), key = { row -> row.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { album -> AlbumCard(album, Modifier.weight(1f)) { selectedId = album.id } }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
                else -> {
                if (tab == LibraryTab.FOLDERS) item(key = "hidden-folders") { HiddenFolders() }
                items(groups, key = { it.id }) { collection ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    ListItem(
                        trailingContent = if (tab == LibraryTab.FOLDERS && collection.subtitle != null) ({
                            TextButton(onClick = { ua.olex.music.library.LibraryFolders.setExcluded(context, collection.subtitle!!, true) }) { Text("Hide") }
                        }) else null,
                        headlineContent = { Text(collection.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(listOfNotNull(collection.subtitle, songCount(collection.tracks.size)).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Artwork(collection.artworkUri, Modifier.size(56.dp), 12.dp) },
                        modifier = Modifier.padding(horizontal = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open ${collection.name}") { selectedId = collection.id },
                    )
                }
                }
            }
        }
        // Items above the first song: the scan error notice and the shuffle button.
        // The letter rail appears while the list moves (and while it is held), so it never covers the rows' buttons.
        var railHeld by remember { mutableStateOf(false) }
        var railShown by remember { mutableStateOf(false) }
        LaunchedEffect(listState.isScrollInProgress, railHeld) {
            if (listState.isScrollInProgress || railHeld) railShown = true else { kotlinx.coroutines.delay(1_500); railShown = false }
        }
        railTracks?.let {
            androidx.compose.animation.AnimatedVisibility(railShown, modifier = Modifier.align(Alignment.CenterEnd),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally { w -> w },
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutHorizontally { w -> w }) {
                LetterRail(it, firstItem = (if (scanError != null) 1 else 0) + 1, listState, descending = order.descending, Modifier, onHeld = { h -> railHeld = h })
            }
        }
        if (picking) SelectionActions(visible != null && picked.ids.isNotEmpty(),
            onPlay = { batch { onPlay(it, 0) } }, onNext = { batch { onEnqueue(it, true) } }, onQueue = { batch { onEnqueue(it, false) } },
            onPlaylist = { batch(onAddToPlaylist) }, onTags = { batch(onEditTags) }, onTrash = { batch(onTrash) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp + LocalPlayerInset.current))
        }
    }
}

@Composable
private fun PageHeading(text: String) = SectionHeader(text)

/** The artist's photo beside the name and counts, smaller than an album cover: the page is about the music below. */
@Composable
private fun ArtistHeader(artist: MusicCollection, albums: Int, showBack: Boolean, onBack: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit, onAdd: () -> Unit, enabled: Boolean) {
    val cs = MaterialTheme.colorScheme
    val minutes = artist.tracks.sumOf { it.durationMs } / 60_000
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (showBack) KickIconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { k -> Symbol(Glyph.BACK, kick = k) }
        // As YouTube Music opens an artist: the photo across the page with the name on it, then Shuffle and Play.
        ArtistHero(artist.name, listOfNotNull(songCount(artist.tracks.size), if (albums > 0) (if (albums == 1) "1 album" else "$albums albums") else null, if (minutes > 0) "$minutes min" else null).joinToString(" · ")) {
            ArtistImage(artist.name, Modifier.fillMaxSize(), 160.dp, androidx.compose.ui.graphics.RectangleShape)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        PageActions(shuffleFirst = true, enabled = enabled, onPlay = onPlay, onShuffle = onShuffle) {
            KickFilledTonalIconButton(enabled = enabled, onClick = onAdd, modifier = Modifier.size(52.dp).semantics { contentDescription = "Add all to playlist" }) { k -> Symbol(Glyph.PLAYLIST_ADD, kick = k) }
        }
    }
}

/** A few lines about the artist from Wikipedia, when it has them; a tap shows the rest. Nothing while there is none. */
@Composable
private fun ArtistBio(name: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val images = (context.applicationContext as? ua.olex.music.MusicApp)?.graph?.artistImages
    val bio by androidx.compose.runtime.produceState(images?.knownBio(name), name) {
        if (value == null && images != null) value = withContext(Dispatchers.IO) { images.cachedBio(name) } ?: images.bio(name)
    }
    val text = bio ?: return
    var open by remember(name) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(onClick = { open = !open }, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            Text("From Wikipedia", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun CollectionHeader(collection: MusicCollection, artist: Boolean = false, showBack: Boolean = true, onBack: () -> Unit, onPlay: () -> Unit, onAdd: () -> Unit, enabled: Boolean = true, onShuffle: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val minutes = collection.tracks.sumOf { it.durationMs } / 60_000
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // In the app the top bar holds the back arrow; this one is for the standalone browser.
        if (showBack) Row(Modifier.fillMaxWidth()) {
            KickIconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { k -> Symbol(Glyph.BACK, kick = k) }
        }
        if (artist) ArtistImage(collection.name, Modifier.size(200.dp), 200.dp)
        else Artwork(collection.artworkUri, Modifier.size(220.dp), 28.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE)
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Text(collection.name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        collection.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(
            listOfNotNull(
                collection.tracks.mapNotNull { it.year?.takeIf { y -> y > 0 } }.distinct().sorted().let { ys -> when { ys.isEmpty() -> null; ys.size == 1 -> ys[0].toString(); else -> "${ys.first()}\u2013${ys.last()}" } }
                    .takeIf { !artist },
                songCount(collection.tracks.size), if (minutes > 0) "$minutes min" else null,
                collection.tracks.mapNotNull { it.format?.label }.distinct().singleOrNull()).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        PageActions(shuffleFirst = artist, enabled = enabled, onPlay = onPlay, onShuffle = onShuffle) {
            KickFilledTonalIconButton(enabled = enabled, onClick = onAdd,
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Add all to playlist" }) { taps -> Symbol(Glyph.PLAYLIST_ADD, kick = taps) }
            if (!artist) collection.tracks.firstOrNull()?.let { MotionCoverButton(it) }
        }
    }
}

/**
 * An album's moving cover: one short video for the whole album, attached here once and played in
 * the player for every song of it. Lit when the album has one; then a tap offers to replace or remove it.
 */
@Composable
private fun MotionCoverButton(track: Track) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val covers = (context.applicationContext as? ua.olex.music.MusicApp)?.graph?.motionCovers ?: return
    val revision by covers.revision.collectAsStateWithLifecycle()
    val has = remember(track.id, revision) { covers.file(track) != null }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var asking by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            // A file that can't be read (moved, too large, not a video) is a message, never a crash.
            val ok = try { covers.attach(track, uri) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { false }
            snackbar?.showSnackbar(if (ok) "Moving cover set for this album" else "Choose a video up to 40 MB")
        }
    }
    fun choose() = pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly))
    androidx.compose.material3.FilledTonalIconToggleButton(checked = has, onCheckedChange = { if (has) asking = true else choose() },
        modifier = Modifier.size(52.dp).semantics { contentDescription = if (has) "Moving cover: change or remove" else "Add a moving cover" }) {
        Symbol(Glyph.MOTION, filled = has)
    }
    if (asking) AppDialog(onDismissRequest = { asking = false }, title = { Text("Moving cover") },
        text = { Text("This album plays its own short video in place of the cover.") },
        confirmButton = { TextButton(onClick = { asking = false; choose() }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { asking = false; covers.remove(track) }) { Text("Remove") } })
}

@Composable
private fun AlbumCard(album: MusicCollection, modifier: Modifier, onOpen: () -> Unit) =
    MediaCard(album.artworkUri, album.name, album.subtitle ?: songCount(album.tracks.size), size = if (modifier == Modifier) 148.dp else null, modifier = modifier, onClick = onOpen)

@Composable
private fun BrowserMessage(text: String) {
    Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"

/** Reference identity prevents stale work when two collections contain structurally equal lists. */
private class SortSource(val tracks: List<Track>) {
    override fun equals(other: Any?) = other is SortSource && tracks === other.tracks
    override fun hashCode() = System.identityHashCode(tracks)
}
private data class SortedTracks(val source: SortSource, val order: TrackSortOrder, val tracks: List<Track>)
private class LibraryMembership(val byId: Map<ua.olex.music.model.TrackId, Track>) {
    override fun equals(other: Any?) = other is LibraryMembership && byId === other.byId
    override fun hashCode() = System.identityHashCode(byId)
}

private data class FilterRequest(
    val source: SortSource,
    val filter: ua.olex.music.model.TrackFilter,
    val stats: Map<String, ua.olex.music.model.SongStats>?,
    val membership: LibraryMembership?,
)

private data class FilteredTracks(val request: FilterRequest, val tracks: List<Track>)


@Composable
private fun LibrarySortControl(scope: TrackSortScope, order: TrackSortOrder, onChange: (TrackSortOrder) -> Unit) {
    var expanded by remember(scope) { mutableStateOf(false) }
    fun label(mode: TrackSortMode) = when (mode) {
        TrackSortMode.DEFAULT -> "Default"
        TrackSortMode.TITLE -> "Title"
        TrackSortMode.ARTIST -> "Artist"
        TrackSortMode.ALBUM -> "Album"
        TrackSortMode.YEAR -> "Year"
        TrackSortMode.DURATION -> "Duration"
        TrackSortMode.FILE_NAME -> "File name"
        TrackSortMode.ADDED -> "Date added"
        TrackSortMode.PLAYS -> "Most played"
        TrackSortMode.RATING -> "Rating"
    }
    // A chip like the filters beside it, so sort and filter read as one row of list options.
    androidx.compose.material3.FilterChip(selected = order != TrackSortOrder(), onClick = { expanded = true },
        leadingIcon = { Symbol(Glyph.SORT, size = 18.dp) },
        trailingIcon = { Symbol(Glyph.DOWN, size = 18.dp) },
        label = { Text("Sort: ${label(order.mode)}" + if (order.descending) " ↓" else "") })
    // A sheet, as Google's Files and Photos sort: the direction as a segmented choice on top, then
    // one rounded group of orders with a tick on the chosen one. Picking an order closes it.
    if (expanded) AppSheet(onDismiss = { expanded = false }) {
        val cs = MaterialTheme.colorScheme
        Text("Sort by", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(false to "Ascending", true to "Descending").forEachIndexed { i, (down, text) ->
                SegmentedButton(selected = order.descending == down, onClick = { onChange(order.copy(descending = down)) },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 2)) { Text(text) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(cs.surfaceContainer)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            TrackSortMode.entries.forEach { mode ->
                val chosen = mode == order.mode
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable { onChange(order.copy(mode = mode)); expanded = false }
                    .background(if (chosen) cs.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label(mode), style = MaterialTheme.typography.bodyLarge, color = if (chosen) cs.onSecondaryContainer else cs.onSurface, modifier = Modifier.weight(1f))
                    if (chosen) Symbol(Glyph.CHECK, color = cs.onSecondaryContainer, size = 20.dp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TrackSelectionToolbar(count: Int, total: Int, ready: Boolean, onAll: () -> Unit, onClear: () -> Unit,
    onClose: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onPlaylist: () -> Unit, onTags: () -> Unit = {}) {
    // Compact: close, the count, select all. The actions are in the floating toolbar below.
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KickIconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close selection" }) { k -> Symbol(Glyph.CLOSE, kick = k) }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // One toggle, as Google Files has it: Select all, or Deselect all once everything is chosen.
            val all = total > 0 && count >= total
            TextButton(enabled = all || ready && total <= TrackSelection.MAX_ITEMS, onClick = if (all) onClear else onAll) { Text(if (all) "Deselect all" else "Select all", maxLines = 1) }
        }
        if (total > TrackSelection.MAX_ITEMS || count >= TrackSelection.MAX_ITEMS)
            Text("Select up to ${TrackSelection.MAX_ITEMS} songs per batch.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** Filter chips for any song list: quality, play state, rating, decade, genre. */
@Composable
private fun TrackFilterRow(filter: ua.olex.music.model.TrackFilter, tracks: List<Track>,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onChange: (ua.olex.music.model.TrackFilter) -> Unit) {
    val decades = rememberOffMain(tracks, initial = emptyList()) { ua.olex.music.model.TrackFilter.decades(tracks) }
    val genres = rememberOffMain(tracks, initial = emptyList()) { tracks.flatMap { it.genres }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(30) }
    var decadeMenu by remember { mutableStateOf(false) }
    var genreMenu by remember { mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading?.let { item { it() } }
        item { androidx.compose.material3.FilterChip(filter.lossless, { onChange(filter.copy(lossless = !filter.lossless)) }, { Text("Lossless") }) }
        item { androidx.compose.material3.FilterChip(filter.hiRes, { onChange(filter.copy(hiRes = !filter.hiRes)) }, { Text("Hi-res") }) }
        item { androidx.compose.material3.FilterChip(filter.unplayed, { onChange(filter.copy(unplayed = !filter.unplayed)) }, { Text("Unplayed") }) }
        item { androidx.compose.material3.FilterChip(filter.minRating > 0, { onChange(filter.copy(minRating = if (filter.minRating > 0) 0 else 4)) }, { Text("4+ stars") }) }
        if (decades.size > 1 || filter.decade != null) item {
            Box {
                androidx.compose.material3.FilterChip(filter.decade != null, { decadeMenu = true }, { Text(filter.decade?.let { "${it}s" } ?: "Decade") })
                DropdownMenu(decadeMenu, { decadeMenu = false }) {
                    DropdownMenuItem({ Text("Any decade") }, { onChange(filter.copy(decade = null)); decadeMenu = false })
                    decades.forEach { d -> DropdownMenuItem({ Text("${d}s") }, { onChange(filter.copy(decade = d)); decadeMenu = false }) }
                }
            }
        }
        if (genres.size > 1 || filter.genre != null) item {
            Box {
                androidx.compose.material3.FilterChip(filter.genre != null, { genreMenu = true }, { Text(filter.genre ?: "Genre") })
                DropdownMenu(genreMenu, { genreMenu = false }) {
                    DropdownMenuItem({ Text("Any genre") }, { onChange(filter.copy(genre = null)); genreMenu = false })
                    genres.forEach { g -> DropdownMenuItem({ Text(g) }, { onChange(filter.copy(genre = g)); genreMenu = false }) }
                }
            }
        }
        trailing?.let { item { it() } }
    }
}

/** A letter rail beside a long song list sorted by title: drag or tap a letter to jump there. */
@Composable
private fun LetterRail(tracks: List<Track>, firstItem: Int, state: androidx.compose.foundation.lazy.LazyListState, descending: Boolean, modifier: Modifier, onHeld: (Boolean) -> Unit = {}) {
    val haptics = rememberHaptics()
    if (tracks.size < 50) return
    val starts = rememberOffMain(tracks, initial = emptyList()) {
        val map = LinkedHashMap<Char, Int>()
        tracks.forEachIndexed { i, t ->
            val c = ua.olex.music.model.LibraryOrder.name(t.title).firstOrNull()?.uppercaseChar()?.let { if (it.isLetter()) it else '#' } ?: '#'
            map.putIfAbsent(c, i)
        }
        map.entries.toList()
    }
    if (starts.size < 3) return
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<Char?>(null) }
    var height by remember { mutableStateOf(1) }
    fun jump(y: Float) {
        val i = (y / height * starts.size).toInt().coerceIn(0, starts.lastIndex)
        val (letter, index) = starts[i]
        if (active != letter) { active = letter; haptics.tick(); scope.launch { state.scrollToItem(firstItem + index) } }
    }
    Box(modifier.fillMaxHeight().padding(vertical = 24.dp)) {
        Column(
            Modifier.fillMaxHeight().width(28.dp)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(starts) {
                    detectVerticalDragGestures(onDragStart = { onHeld(true); jump(it.y) }, onDragEnd = { active = null; onHeld(false) }, onDragCancel = { active = null; onHeld(false) }) { change, _ -> jump(change.position.y) }
                }
                .pointerInput(starts) { detectTapGestures { jump(it.y); active = null } },
            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            starts.forEach { (letter, _) ->
                Text(letter.toString(), style = MaterialTheme.typography.labelSmall,
                    color = if (letter == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        active?.let { letter ->
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.Center).offset(x = (-56).dp).size(64.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(letter.toString(), style = MaterialTheme.typography.headlineMedium) }
            }
        }
    }
}

/** Folders hidden from the library, each with a way back. */
@Composable
private fun HiddenFolders() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hidden by remember { ua.olex.music.library.LibraryFolders.excluded(context) }.collectAsState(emptySet())
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("Every folder on the phone is scanned. Hide the ones that are not music (recordings, ringtones).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        hidden.sorted().forEach { folder ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hidden: ${folder.trimEnd('/')}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { ua.olex.music.library.LibraryFolders.setExcluded(context, folder, false) }) { Text("Show") }
            }
        }
    }
}

/** The actions for selected songs, as a floating toolbar over the list (M3E). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionActions(enabled: Boolean, onPlay: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onPlaylist: () -> Unit, onTags: () -> Unit, modifier: Modifier,
    onTrash: () -> Unit = {}) {
    val haptics = rememberHaptics()
    androidx.compose.material3.HorizontalFloatingToolbar(expanded = true, modifier = modifier,
        colors = androidx.compose.material3.FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
        listOf(Triple(Glyph.PLAY, "Play selected", onPlay), Triple(Glyph.PLAY_NEXT, "Play next", onNext), Triple(Glyph.ADD_TO_QUEUE, "Add to queue", onQueue),
            Triple(Glyph.PLAYLIST_ADD, "Add to playlist", onPlaylist), Triple(Glyph.EDIT, "Edit tags", onTags),
            Triple(Glyph.DELETE, "Move to trash", onTrash)).forEachIndexed { i, (glyph, label, action) ->
            KickIconButton(enabled = enabled, onClick = { haptics.confirm(); action() },
                modifier = Modifier.semantics { contentDescription = label }) { taps -> Symbol(glyph, filled = glyph == Glyph.PLAY, kick = taps) }
        }
    }
}

/** The Search place before typing: what was searched lately, one tap to search it again. */
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (recent.isEmpty()) {
            Text("Search songs, artists and albums. Accents and case don't matter.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            return
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear") }
        }
        recent.forEach { q ->
            ListItem(headlineContent = { Text(q) }, leadingContent = { Symbol(Glyph.HISTORY, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable { onPick(q) })
        }
    }
}

/** How the album and artist grids are ordered. */
private enum class CollectionSort(val title: String) { NAME("A\u2013Z"), NEWEST("Newest"), YEAR("Year"), PLAYED("Most played") }

@Composable
private fun SearchHeading(text: String) = SectionHeader(text)

/** Search's browse tiles: kinds of the library that are not in Library's four, and quick filters. */
private enum class BrowseTile(val title: String, val glyph: Glyph, val tab: LibraryTab, val filter: ua.olex.music.model.TrackFilter = ua.olex.music.model.TrackFilter()) {
    ALBUMS("Albums", Glyph.ALBUM, LibraryTab.ALBUMS), ARTISTS("Artists", Glyph.PERSON, LibraryTab.ARTISTS),
    SONGS("Songs", Glyph.NOTE, LibraryTab.SONGS), GENRES("Genres", Glyph.GENRE, LibraryTab.GENRES),
    YEARS("Years", Glyph.CALENDAR, LibraryTab.YEARS), FOLDERS("Folders", Glyph.FOLDER, LibraryTab.FOLDERS),
    HISTORY("History", Glyph.HISTORY, LibraryTab.HISTORY),
    COMPOSERS("Composers", Glyph.PIANO, LibraryTab.COMPOSERS),
    LOSSLESS("Lossless", Glyph.WAVE, LibraryTab.SONGS, ua.olex.music.model.TrackFilter(lossless = true)),
    HI_RES("Hi-res", Glyph.HI_RES, LibraryTab.SONGS, ua.olex.music.model.TrackFilter(hiRes = true)),
    UNPLAYED("Never played", Glyph.UNPLAYED, LibraryTab.SONGS, ua.olex.music.model.TrackFilter(unplayed = true)),
}

/**
 * The Search place before typing, as Google Play's: tiles in two columns (name left, a tonal icon
 * right), recent searches above when there are any. Icon colours rotate through the wallpaper's
 * three accent families, so the grid is lively without inventing colours.
 */
@Composable
private fun BrowseTiles(library: LibraryIndex, onGenre: (MusicCollection) -> Unit, onTile: (BrowseTile) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    // Worked out in the same frame (the index already holds genres, composers and years; the
    // format scan stops at the first match), so the page opens whole instead of growing tiles
    // and pushing the grid down a moment later.
    val (available, genres) = remember(library) {
        val tiles = BrowseTile.entries.filterTo(HashSet()) { tile ->
            when (tile) {
                BrowseTile.COMPOSERS -> library.composers.isNotEmpty()
                BrowseTile.YEARS -> library.years.size > 1
                BrowseTile.GENRES -> library.genres.size > 1
                BrowseTile.HI_RES -> library.songs.any { it.format?.hiRes == true }
                BrowseTile.LOSSLESS -> library.songs.any { it.format?.lossless == true }
                else -> true
            }
        }
        tiles.toSet() to library.genres.sortedByDescending { it.tracks.size }.take(6)
    }
    val accents = listOf(cs.primaryContainer to cs.onPrimaryContainer, cs.secondaryContainer to cs.onSecondaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer)
    val columns = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 1 else 2
    val groups = listOf("Browse" to listOf(BrowseTile.SONGS, BrowseTile.ALBUMS, BrowseTile.ARTISTS, BrowseTile.GENRES, BrowseTile.COMPOSERS, BrowseTile.YEARS, BrowseTile.FOLDERS),
        "Listening" to listOf(BrowseTile.HISTORY, BrowseTile.UNPLAYED),
        "Audio quality" to listOf(BrowseTile.LOSSLESS, BrowseTile.HI_RES))
    val genreRows = genres.chunked(columns)
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalPlayerInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEachIndexed { group, (heading, tiles) ->
            val rows = tiles.filter { it in available }.chunked(columns)
            if (rows.isNotEmpty()) item(key = heading) {
                Text(heading, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
            }
            items(rows.size, key = { "$heading-$it" }) { index ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows[index].forEach { tile ->
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val down by interaction.collectIsPressedAsState()
                        val still = LocalPowerSave.current
                        val amount by androidx.compose.animation.core.animateFloatAsState(if (down && !still) 1f else 0f,
                            MaterialTheme.motionScheme.fastSpatialSpec(), label = "Browse icon press")
                        val (bg, fg) = accents[group]
                        // Each tile wears one of M3 Expressive's shapes behind its icon; a press
                        // turns the shape and squeezes the tile, as Pixel's launcher folders do.
                        val shape = browseShapes[tile.ordinal % browseShapes.size].toShape()
                        androidx.compose.material3.Surface(onClick = { haptics.tick(); onTile(tile) }, interactionSource = interaction,
                            shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.weight(1f).heightIn(min = 76.dp).squish(0.04f)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(46.dp).graphicsLayer {
                                    rotationZ = amount * if (group == 1) -30f else 30f
                                }.clip(shape).background(bg), contentAlignment = Alignment.Center) {
                                    Symbol(tile.glyph, color = fg, filled = true, modifier = Modifier.graphicsLayer { rotationZ = -amount * if (group == 1) -30f else 30f })
                                }
                                Text(tile.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (rows[index].size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
        // Your genres as big cover cards, as Google Play and Spotify fill their browse pages.
        if (genres.size > 1) {
            item { Text("Your genres", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() }) }
            items(genreRows.size) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    genreRows[r].forEach { genre -> GenreCard(genre, Modifier.weight(1f)) { haptics.tick(); onGenre(genre) } }
                    if (genreRows[r].size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private val browseShapes = listOf(
    androidx.compose.material3.MaterialShapes.Cookie6Sided, androidx.compose.material3.MaterialShapes.Clover4Leaf,
    androidx.compose.material3.MaterialShapes.Sunny, androidx.compose.material3.MaterialShapes.Cookie4Sided,
    androidx.compose.material3.MaterialShapes.Pentagon, androidx.compose.material3.MaterialShapes.Cookie9Sided,
    androidx.compose.material3.MaterialShapes.Flower, androidx.compose.material3.MaterialShapes.Gem,
)

/** A genre as a card: up to three covers fanned on the right, the name and size on the left. */
@Composable
private fun GenreCard(genre: MusicCollection, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val arts = genre.tracks.asSequence().mapNotNull { it.artworkUri }.distinct().take(3).toList()
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    androidx.compose.material3.Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = cs.secondaryContainer,
        contentColor = cs.onSecondaryContainer, modifier = modifier.heightIn(min = 112.dp + 36.dp * (scale - 1f)).squish(0.03f)) {
        Box {
            arts.forEachIndexed { i, art ->
                Artwork(art, Modifier.align(Alignment.BottomEnd).offset(x = (12 - i * 22).dp, y = (14 - i * 4).dp)
                    .graphicsLayer { rotationZ = 14f - i * 9f }.size(72.dp), 14.dp)
            }
            Column(Modifier.align(Alignment.TopStart).padding(16.dp).fillMaxWidth(0.62f)) {
                Text(genre.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(songCount(genre.tracks.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/** A bright card for Favourites or Smart playlists at the top of the Playlists place. */
@Composable
internal fun HubCard(title: String, subtitle: String, glyph: Glyph, shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    androidx.compose.material3.Surface(onClick = { haptics.tick(); onClick() }, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = container, contentColor = content, modifier = modifier.heightIn(min = 108.dp).squish()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(38.dp).clip(shape).background(content.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Symbol(glyph, filled = true) }
            Column {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The kinds a YouTube Music search can be narrowed to, as its chips. */
internal enum class SearchKind(val title: String) { ALL("All"), SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists") }

/**
 * YouTube Music's "Top result": the artist, album or playlist the query most likely means, large,
 * with its page a tap away and Play on the right.
 */
@Composable
private fun TopResultCard(card: YouTubeMusic.Card, onOpened: () -> Unit) {
    val nav = LocalOnlineNav.current
    val cs = MaterialTheme.colorScheme
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val artist = card.kind == YouTubeMusic.Card.Kind.ARTIST
    val playlist = card.kind == YouTubeMusic.Card.Kind.PLAYLIST
    fun open() {
        onOpened()
        if (artist) nav.open(OnlineTarget.Artist(card.title, card.id))
        else nav.open(OnlineTarget.Album(card.id, card.title, playlist = playlist, cover = card.cover, subtitle = card.subtitle))
    }
    androidx.compose.material3.Surface(onClick = ::open, color = cs.surfaceContainerHigh, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(112.dp).squish(0.02f)) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (artist) Artwork(card.cover, Modifier.size(88.dp).clip(androidx.compose.foundation.shape.CircleShape), 44.dp)
            else Artwork(card.cover, Modifier.size(88.dp), 18.dp)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (artist) "Artist" else if (playlist) "Playlist" else "Album", card.subtitle).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Play straight from the card: the artist's top songs, or the album or playlist.
            androidx.compose.material3.FilledIconButton(onClick = {
                haptics.confirm(); onOpened()
                scope.launch {
                    val songs = runCatching { if (artist) graph.youTube.artist(card.id)?.songs else graph.youTube.album(card.id)?.second }.getOrNull().orEmpty()
                    if (songs.isNotEmpty()) nav.play(songs, 0, card.title) else open()
                }
            }, modifier = Modifier.size(52.dp).semantics { contentDescription = "Play ${card.title}" }) {
                Symbol(Glyph.PLAY, size = 26.dp, filled = true)
            }
        }
    }
}
