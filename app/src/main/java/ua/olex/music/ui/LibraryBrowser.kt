@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

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
import androidx.compose.foundation.background
import androidx.compose.material3.toShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.MusicCollection
import ua.olex.music.model.Track

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
    onCollection: (String?) -> Unit = {}, closeCollection: Int = 0,
    /** Set when something here was opened from another place: Back from it goes there. */
    onLeave: (() -> Unit)? = null,
    /** YouTube Music's songs for the query, shown under the library's (null while it looks). */
    online: OnlineSearch? = null, onlineOn: Boolean = false,
    /** Bumped when the Search tab is tapped again: back to the tiles and the field focused (as Google Play does). */
    focusSearch: Int = 0,
    /** Open a Search tile from elsewhere (Home's "see all" for History and Smart playlists). */
    openTile: String? = null,
) {
    val nav = LocalOnlineNav.current
    val startTab = LocalStartTab.current
    var tabName by rememberSaveable { mutableStateOf(if (mode == BrowserMode.LIBRARY) LibraryTab.PLAYLISTS.name else startTab) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var missingFavourite by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = LibraryTab.valueOf(tabName)
    val groups = when (tab) {
        LibraryTab.ALBUMS -> library.albums
        LibraryTab.ARTISTS -> library.artists
        LibraryTab.GENRES -> library.genres
        LibraryTab.FOLDERS -> library.folders
        LibraryTab.YEARS -> library.years
        LibraryTab.COMPOSERS -> library.composers
        else -> emptyList()
    }
    val selected = groups.firstOrNull { it.id == selectedId }
    var collectionSort by rememberSaveable(tabName) { mutableStateOf(CollectionSort.NAME) }
    val shownGroups = remember(groups, collectionSort, stats) {
        when (collectionSort) {
            CollectionSort.NAME -> groups
            CollectionSort.NEWEST -> groups.sortedByDescending { g -> g.tracks.maxOf { it.addedAt ?: 0 } }
            CollectionSort.YEAR -> groups.sortedByDescending { g -> g.tracks.mapNotNull { it.year }.maxOrNull() ?: 0 }
            CollectionSort.PLAYED -> groups.sortedByDescending { g -> g.tracks.sumOf { stats[it.key.value]?.plays ?: 0 } }
        }
    }
    val searching = query.isNotBlank()
    // Albums and artists that match every word of the search, shown above the songs.
    val searchGroups = remember(library, query) {
        val words = ua.olex.music.model.TextFold.fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) emptyList() else {
            fun hit(c: MusicCollection, extra: String = "") = ua.olex.music.model.TextFold.fold(c.name + " " + extra).let { t -> words.all { t.contains(it) } }
            library.artists.filter { hit(it) }.take(5).map { LibraryTab.ARTISTS to it } +
                library.albums.filter { hit(it, it.subtitle.orEmpty()) }.take(8).map { LibraryTab.ALBUMS to it }
        }
    }
    val sortScope = when {
        searching -> TrackSortScope.SEARCH
        selected == null -> TrackSortScope.SONGS
        tab == LibraryTab.ALBUMS -> TrackSortScope.ALBUM_TRACKS
        tab == LibraryTab.ARTISTS -> TrackSortScope.ARTIST_TRACKS
        tab == LibraryTab.GENRES -> TrackSortScope.GENRE_TRACKS
        else -> TrackSortScope.FOLDER_TRACKS
    }
    val raw = when {
        searching -> search?.takeIf { it.query == query && !it.failed }?.tracks?.filter { it.id in library.byId }
        selected != null -> selected.tracks
        tab == LibraryTab.SONGS -> library.songs
        else -> null
    }
    var filter by remember { mutableStateOf(ua.olex.music.model.TrackFilter()) }
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val filterStats = if (filter.unplayed || filter.minRating > 0) stats else null
    val unsorted = remember(raw, filter, filterStats) { raw?.let { list -> filter.apply(list) { stats[it.key.value] } } }
    val order = sortOrders[sortScope] ?: TrackSortOrder()
    val source = unsorted?.let(::SortSource)
    val statsKey = if (order.mode == TrackSortMode.PLAYS || order.mode == TrackSortMode.RATING) stats else null
    val sorted by produceState<SortedTracks?>(null, source, order, statsKey) {
        value = if (source == null || order == TrackSortOrder()) null else withContext(Dispatchers.Default) {
            SortedTracks(source, order, order.sort(source.tracks) { stats[it.key.value] })
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
    fun closeSelected() {
        val artist = backToArtist
        backToArtist = null
        if (artist != null && tab == LibraryTab.ALBUMS) { tabName = LibraryTab.ARTISTS.name; selectedId = artist; return }
        if (openedFromOutside == "collection" && onLeave != null) { openedFromOutside = null; selectedId = null; browsing = null; onLeave(); return }
        selectedId = null
    }
    LaunchedEffect(closeCollection) { if (closeCollection > 0) closeSelected() }
    BackHandler(searching || selected != null) {
        if (searching) onQueryChange("") else closeSelected()
    }
    BackHandler(picking) { picking = false; picked = TrackSelection() }
    LaunchedEffect(openTile) { openTile?.let { t -> runCatching { BrowseTile.valueOf(t) }.getOrNull()?.let { tile -> browsing = tile.name; tabName = tile.tab.name; filter = tile.filter; openedFromOutside = "tile"; onOpened() } } }
    BackHandler(mode == BrowserMode.LIBRARY && tab != LibraryTab.PLAYLISTS && selected == null) { tabName = LibraryTab.PLAYLISTS.name }
    fun closeTile() {
        browsing = null; filter = ua.olex.music.model.TrackFilter()
        if (openedFromOutside == "tile" && onLeave != null) { openedFromOutside = null; onLeave() }
    }
    BackHandler(mode == BrowserMode.SEARCH && browsing != null && selected == null && !searching) { closeTile() }
    missingFavourite?.let { id ->
        AppDialog(onDismissRequest = { missingFavourite = null }, title = { Text("Remove unavailable favourite?") },
            text = { Text("The saved entry will be removed. Your music files are not changed.") },
            confirmButton = { TextButton(onClick = { personalActions?.removeFavourite?.invoke(id); missingFavourite = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { missingFavourite = null }) { Text("Cancel") } })
    }

    var fieldFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(insets)) {
        // A search pill, as in Google's apps, and the library's sections as chips below it.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(focusSearch) { if (focusSearch > 0) { browsing = null; selectedId = null; filter = ua.olex.music.model.TrackFilter(); runCatching { focus.requestFocus() } } }
        val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onQueryChange)
        }
        // Inside a tile or an album the page has its own header (as Google Play's category pages do);
        // the field comes back with Back or a second tap on Search.
        val fieldShown = mode == BrowserMode.ALL || (mode == BrowserMode.SEARCH && (searching || (browsing == null && selected == null)))
        if (fieldShown) androidx.compose.material3.TextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            placeholder = { Text("Search your music") },
            leadingIcon = { Symbol(Glyph.SEARCH, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = "Clear" }) { Symbol(Glyph.CLOSE) }
                else if (mode == BrowserMode.SEARCH) androidx.compose.material3.IconButton(onClick = {
                    runCatching { voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a song, artist or album")) }
                }, modifier = Modifier.semantics { contentDescription = "Search by voice" }) { Symbol(Glyph.MIC) }
            },
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus)
                .onFocusChanged { fieldFocused = it.isFocused },
        )
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
                androidx.compose.material3.IconButton(onClick = { closeTile() },
                    modifier = Modifier.semantics { contentDescription = "Back to browse" }) { Symbol(Glyph.BACK) }
                Text(BrowseTile.valueOf(name).title, style = MaterialTheme.typography.titleLarge)
            }
        }
        // Library: the four main kinds as a connected button group (M3E); the rest live in Search's tiles.
        // The Playlists place: Favourites and Smart playlists as two cards above your own playlists;
        // each opens in place with a way back.
        if (!searching && mode == BrowserMode.LIBRARY && selected == null) {
            if (tab == LibraryTab.PLAYLISTS) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubCard("Favourites", "${personal.favourites.size} songs", Glyph.FAVOURITE, androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Modifier.weight(1f)) { tabName = LibraryTab.FAVOURITES.name }
                HubCard("Smart", "${smart.count { it.tracks.isNotEmpty() }} lists", Glyph.SMART, androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
                    MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f)) { tabName = LibraryTab.SMART.name }
            } else Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = { tabName = LibraryTab.PLAYLISTS.name },
                    modifier = Modifier.semantics { contentDescription = "Back to playlists" }) { Symbol(Glyph.BACK) }
                Text(tab.title, style = MaterialTheme.typography.titleLarge)
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
        val tools = unsorted != null && !(selected != null && tab == LibraryTab.ALBUMS) && !picking
        if (tools && (mode != BrowserMode.SEARCH || (browsing != null && !searching))) {
            // One row: sort, then filters (song lists only), then select at the end.
            val sortChip: @Composable () -> Unit = { LibrarySortControl(sortScope, order) { onSortOrder(sortScope, it) } }
            val selectButton: @Composable () -> Unit = {
                androidx.compose.material3.IconButton(onClick = { picking = true }, modifier = Modifier.semantics { contentDescription = "Select" }) { Symbol(Glyph.CHECK) }
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
            SmartPlaylistBrowser(smart, currentId, Modifier.weight(1f), onPlay = onPlay, onShuffle = onShuffle, onTrackActions = onTrackActions,
                onSave = onSaveSmart, onDelete = onDeleteSmart)
        } else if (!searching && tab == LibraryTab.PLAYLISTS && personalActions != null) {
            PlaylistBrowser(personal.playlists, personalActions, modifier = Modifier.weight(1f), currentId = currentId, onPlay = onPlay, onTrackActions = onTrackActions, library = library)
        } else Box(Modifier.weight(1f)) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val headerGone by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        LaunchedEffect(selected?.id, headerGone) { onCollection(selected?.let { if (headerGone) it.name else "" }) }
        LaunchedEffect(selected?.id) { if (selected != null) listState.scrollToItem(0) }
        val railTracks = if (!searching && selected == null && tab == LibraryTab.SONGS && (order.mode == TrackSortMode.DEFAULT || order.mode == TrackSortMode.TITLE)) visible else null
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp + LocalPlayerInset.current + (if (picking) 80.dp else 0.dp))) {
            scanError?.let { item(key = "scan-error") { ErrorNotice(it, onRescan) } }
            when {
                searching -> {
                    when {
                        search == null || search.query != query -> item { BrowserMessage("Searching…") }
                        search.failed -> item { ErrorNotice("Couldn't search your library.", onRetrySearch) }
                        search.tracks.isEmpty() && searchGroups.isEmpty() && online?.tracks.isNullOrEmpty() -> item {
                            if (online == null && onlineOn) BrowserMessage("Searching YouTube Music…")
                            else EmptyState(Glyph.SEARCH, "Nothing found", "Try fewer words, or part of a title, artist or album.",
                                shape = androidx.compose.material3.MaterialShapes.Clover4Leaf.toShape())
                        }
                        else -> {
                            searchGroups.groupBy { it.first }.forEach { (kind, hits) ->
                                item(key = "group-title-$kind") { SearchHeading(if (kind == LibraryTab.ARTISTS) "Artists" else "Albums") }
                            hits.forEach { (_, collection) ->
                                item(key = "group-$kind-${collection.id}") {
                                    androidx.compose.material3.ListItem(
                                        headlineContent = { Text(collection.name, maxLines = 1) },
                                        supportingContent = { Text(listOfNotNull(collection.subtitle, songCount(collection.tracks.size)).joinToString(" · "), maxLines = 1) },
                                        leadingContent = { if (kind == LibraryTab.ARTISTS) ArtistImage(collection.name, Modifier.size(56.dp), 56.dp) else Artwork(collection.artworkUri, Modifier.size(56.dp), corner = 12.dp) },
                                        // Same inset and rounded press as the song rows below, so all results share one left edge.
                                        modifier = Modifier.padding(horizontal = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).clickable { onSearchUsed(query); tabName = kind.name; selectedId = collection.id; onQueryChange("") },
                                    )
                                }
                            }
                            }
                            if (search.tracks.isNotEmpty()) item { SearchHeading("Songs · ${search.tracks.size}") }
                            if (visible == null) item { BrowserMessage("Sorting...") }
                            else itemsIndexed(visible, key = { _, track -> track.id.value }) { index, track ->
                                TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                            checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }, onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) }) { onSearchUsed(query); onPlay(visible, index) }
                            }
                            // YouTube Music under the owner's own results: songs not in the library, played the same way.
                            if (onlineOn) {
                                item(key = "online-title") { SearchHeading("YouTube Music") }
                                val found = online?.takeIf { it.query == query }
                                if (found != null && found.artists.isNotEmpty()) {
                                    item(key = "online-artists-h") { SearchSubheading("Artists") }
                                    item(key = "online-artists") { YouTubeCards(found.artists, size = 104.dp) }
                                }
                                if (found != null && found.albums.isNotEmpty()) {
                                    item(key = "online-albums-h") { SearchSubheading("Albums") }
                                    item(key = "online-albums") { YouTubeCards(found.albums, size = 120.dp) }
                                }
                                if (found != null && (found.artists.isNotEmpty() || found.albums.isNotEmpty()) && found.tracks.isNotEmpty())
                                    item(key = "online-songs-h") { SearchSubheading("Songs") }
                                when {
                                    found == null -> items(3, key = { "online-wait-$it" }) { PlaceholderRow() }
                                    found.failed -> item(key = "online-failed") { BrowserMessage("YouTube Music didn't answer. Check the connection.") }
                                    found.tracks.isEmpty() && found.artists.isEmpty() && found.albums.isEmpty() -> item(key = "online-none") { BrowserMessage("Nothing more on YouTube Music.") }
                                    found.tracks.isEmpty() -> {}
                                    else -> itemsIndexed(found.tracks, key = { _, t -> "online-" + t.id.value }) { i, t ->
                                        TrackRow(t, current = t.id.value == currentId, onMore = { onTrackActions(t) },
                                            onPlayNext = { onEnqueue(listOf(t), true) }, onQueue = { onEnqueue(listOf(t), false) }) { onSearchUsed(query); nav.play(found.tracks, i, "Search") }
                                    }
                                }
                            }
                        }
                    }
                }
                selected != null && tab == LibraryTab.ARTISTS -> {
                    // The artist's page: who they are, their albums, then their most played songs.
                    val ids = selected.tracks.mapTo(HashSet()) { it.id }
                    val albums = library.albums.filter { a -> a.tracks.any { it.id in ids } }
                    item(key = "artist-header") {
                        ArtistHeader(selected, albums.size, showBack = mode != BrowserMode.LIBRARY, onBack = ::closeSelected, onPlay = { visible?.let { onPlay(it, 0) } },
                            onShuffle = { visible?.let(onShuffle) }, onAdd = { visible?.let(onAddToPlaylist) }, enabled = !visible.isNullOrEmpty())
                    }
                    item(key = "artist-bio") { ArtistBio(selected.name) }
                    if (albums.isNotEmpty()) {
                        item(key = "artist-albums-title") { PageHeading(if (albums.size == 1) "Album" else "Albums") }
                        item(key = "artist-albums") {
                            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                items(albums, key = { it.id }) { album ->
                                    AlbumCard(album, Modifier.width(156.dp)) { backToArtist = selected.id; tabName = LibraryTab.ALBUMS.name; selectedId = album.id }
                                }
                            }
                        }
                    }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else {
                        val shown = if (allArtistSongs || visible.size <= 5) visible else visible.sortedByDescending { stats[it.key.value]?.plays ?: 0 }.take(5)
                        item(key = "artist-songs-title") {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (allArtistSongs || visible.size <= 5) "Songs" else "Most played", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
                                if (visible.size > 5) TextButton(onClick = { allArtistSongs = !allArtistSongs }) { Text(if (allArtistSongs) "Show less" else "All ${visible.size}") }
                            }
                        }
                        shown.forEachIndexed { index, track ->
                            item(key = "artist-" + track.id.value) {
                                TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                                    checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }, onPlayNext = { onEnqueue(listOf(track), true) }, onQueue = { onEnqueue(listOf(track), false) },
                                    albumArtist = selected.name) { onPlay(shown, index) }
                            }
                        }
                        // What else the artist has on YouTube Music, beyond the owner's files.
                        item(key = "artist-online") { MoreOnYouTubeMusic(selected.name, library, currentId) }
                    }
                }
                selected != null -> {
                    item(key = "collection-header") {
                        CollectionHeader(selected, artist = tab == LibraryTab.ARTISTS, showBack = mode != BrowserMode.LIBRARY, onBack = ::closeSelected, onPlay = { visible?.let { onPlay(it, 0) } }, onShuffle = { visible?.let(onShuffle) }, onAdd = { visible?.let(onAddToPlaylist) }, enabled = !visible.isNullOrEmpty())
                    }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else {
                        // Disc headers in an album shown in its own order.
                        val discs = if (tab == LibraryTab.ALBUMS && order == TrackSortOrder()) ua.olex.music.model.discStarts(visible) else emptyMap()
                        // One quality for the whole album is said once, in the header.
                        val uniformQuality = visible.mapNotNull { it.format?.label }.distinct().size <= 1
                        visible.forEachIndexed { index, track ->
                            discs[index]?.let { disc -> item(key = "disc-$disc") {
                                Text("Disc $disc", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                            } }
                            item(key = track.id.value) {
                                val inAlbum = tab == LibraryTab.ALBUMS && order == TrackSortOrder()
                                TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
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
                    else itemsIndexed(visible, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
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
                                .clickable(onClickLabel = "Open ${artist.name}") { selectedId = artist.id }.padding(vertical = 8.dp),
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
private fun PageHeading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))

/** The artist's photo beside the name and counts, smaller than an album cover: the page is about the music below. */
@Composable
private fun ArtistHeader(artist: MusicCollection, albums: Int, showBack: Boolean, onBack: () -> Unit, onPlay: () -> Unit, onShuffle: () -> Unit, onAdd: () -> Unit, enabled: Boolean) {
    val cs = MaterialTheme.colorScheme
    val minutes = artist.tracks.sumOf { it.durationMs } / 60_000
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (showBack) androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { Symbol(Glyph.BACK) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtistImage(artist.name, Modifier.size(120.dp), 120.dp)
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                Text(artist.name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(songCount(artist.tracks.size), if (albums > 0) (if (albums == 1) "1 album" else "$albums albums") else null, if (minutes > 0) "$minutes min" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = enabled, onClick = onPlay, modifier = Modifier.height(52.dp)) {
                Symbol(Glyph.PLAY, size = 20.dp, filled = true); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp)); Text("Play all")
            }
            androidx.compose.material3.FilledTonalIconButton(enabled = enabled, onClick = onShuffle, modifier = Modifier.size(52.dp).semantics { contentDescription = "Shuffle" }) { Symbol(Glyph.SHUFFLE) }
            androidx.compose.material3.FilledTonalIconButton(enabled = enabled, onClick = onAdd, modifier = Modifier.size(52.dp).semantics { contentDescription = "Add all to playlist" }) { Symbol(Glyph.PLAYLIST_ADD) }
        }
    }
}

/** A few lines about the artist from Wikipedia, when it has them; a tap shows the rest. Nothing while there is none. */
@Composable
private fun ArtistBio(name: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val images = (context.applicationContext as? ua.olex.music.MusicApp)?.graph?.artistImages
    val bio by androidx.compose.runtime.produceState(images?.cachedBio(name), name) { if (value == null && images != null) value = images.bio(name) }
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
            androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { Symbol(Glyph.BACK) }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = enabled, onClick = onPlay, modifier = Modifier.height(52.dp)) {
                Symbol(Glyph.PLAY, size = 20.dp, filled = true); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp)); Text("Play all")
            }
            androidx.compose.material3.FilledTonalIconButton(enabled = enabled, onClick = onShuffle,
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Shuffle" }) { Symbol(Glyph.SHUFFLE) }
            androidx.compose.material3.FilledTonalIconButton(enabled = enabled, onClick = onAdd,
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Add all to playlist" }) { Symbol(Glyph.PLAYLIST_ADD) }
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
    val revision by covers.revision.collectAsState()
    val has = remember(track.id, revision) { covers.file(track) != null }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var asking by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = covers.attach(track, uri)
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
private fun AlbumCard(album: MusicCollection, modifier: Modifier, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.squish(0.03f).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .clickable(onClickLabel = "Open ${album.name}", onClick = onOpen).padding(4.dp).padding(bottom = 12.dp),
    ) {
        Artwork(album.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f), 16.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(album.subtitle ?: songCount(album.tracks.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

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
    androidx.compose.foundation.layout.Box {
        // A chip like the filters beside it, so sort and filter read as one row of list options.
        androidx.compose.material3.FilterChip(selected = order != TrackSortOrder(), onClick = { expanded = true },
            leadingIcon = { Symbol(Glyph.SORT, size = 18.dp) },
            label = { Text("Sort: ${label(order.mode)}${if (order.descending) " descending" else ""}") })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSortMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(label(mode)) }, onClick = { onChange(order.copy(mode = mode)); expanded = false })
            }
            DropdownMenuItem(text = { Text("Descending") }, trailingIcon = { Checkbox(checked = order.descending, onCheckedChange = null) },
                onClick = { onChange(order.copy(descending = !order.descending)); expanded = false })
        }
    }
}

@Composable
private fun TrackSelectionToolbar(count: Int, total: Int, ready: Boolean, onAll: () -> Unit, onClear: () -> Unit,
    onClose: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onPlaylist: () -> Unit, onTags: () -> Unit = {}) {
    // Compact: close, the count, select all. The actions are in the floating toolbar below.
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close selection" }) { Symbol(Glyph.CLOSE) }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (count > 0) TextButton(onClick = onClear) { Text("Clear selection") }
            TextButton(enabled = ready && total <= TrackSelection.MAX_ITEMS, onClick = onAll) { Text("Select all") }
        }
        if (total > TrackSelection.MAX_ITEMS || count >= TrackSelection.MAX_ITEMS)
            Text("Select up to ${TrackSelection.MAX_ITEMS} songs per batch.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** Filter chips for any song list: quality, play state, rating, decade, genre. */
@Composable
private fun TrackFilterRow(filter: ua.olex.music.model.TrackFilter, tracks: List<Track>,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onChange: (ua.olex.music.model.TrackFilter) -> Unit) {
    val decades = remember(tracks) { ua.olex.music.model.TrackFilter.decades(tracks) }
    val genres = remember(tracks) { tracks.flatMap { it.genres }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(30) }
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
    val starts = remember(tracks) {
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
        listOf(Triple(Glyph.PLAY, "Play selected", onPlay), Triple(Glyph.NEXT, "Play next", onNext), Triple(Glyph.QUEUE, "Add to queue", onQueue),
            Triple(Glyph.PLAYLIST_ADD, "Add to playlist", onPlaylist), Triple(Glyph.EDIT, "Edit tags", onTags),
            Triple(Glyph.DELETE, "Move to trash", onTrash)).forEachIndexed { i, (glyph, label, action) ->
            androidx.compose.material3.IconButton(enabled = enabled, onClick = { haptics.confirm(); action() },
                modifier = Modifier.semantics { contentDescription = label }) { Symbol(glyph, filled = glyph == Glyph.PLAY) }
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
private fun SearchHeading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))

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
    val tiles = BrowseTile.entries.filter { t ->
        when (t) { BrowseTile.COMPOSERS -> library.composers.isNotEmpty(); BrowseTile.YEARS -> library.years.size > 1; BrowseTile.GENRES -> library.genres.size > 1
            BrowseTile.HI_RES -> library.songs.any { it.format?.hiRes == true }; BrowseTile.LOSSLESS -> library.songs.any { it.format?.lossless == true }; else -> true }
    }
    val accents = listOf(cs.primaryContainer to cs.onPrimaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer, cs.secondaryContainer to cs.onSecondaryContainer)
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalPlayerInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Browse", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)) }
        items(tiles.chunked(2).size) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2)[r].forEachIndexed { c, tile ->
                    val (bg, fg) = accents[(r * 2 + c) % accents.size]
                    androidx.compose.material3.Surface(onClick = { haptics.tick(); onTile(tile) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = cs.surfaceContainer, modifier = Modifier.weight(1f).height(72.dp).squish(0.03f)) {
                        Row(Modifier.padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.weight(1f))
                            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                                Symbol(tile.glyph, color = fg, filled = true)
                            }
                        }
                    }
                }
                if (tiles.chunked(2)[r].size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // Your genres as big cover cards, as Google Play and Spotify fill their browse pages.
        val genres = library.genres.sortedByDescending { it.tracks.size }.take(6)
        if (genres.size > 1) {
            item { Text("Your genres", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)) }
            items(genres.chunked(2).size) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.chunked(2)[r].forEach { genre -> GenreCard(genre, Modifier.weight(1f)) { haptics.tick(); onGenre(genre) } }
                    if (genres.chunked(2)[r].size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A genre as a card: up to three covers fanned on the right, the name and size on the left. */
@Composable
private fun GenreCard(genre: MusicCollection, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val arts = genre.tracks.mapNotNull { it.artworkUri }.distinct().take(3)
    androidx.compose.material3.Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = cs.secondaryContainer,
        contentColor = cs.onSecondaryContainer, modifier = modifier.height(112.dp).squish(0.03f)) {
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
private fun HubCard(title: String, subtitle: String, glyph: Glyph, shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    androidx.compose.material3.Surface(onClick = { haptics.tick(); onClick() }, shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = container, contentColor = content, modifier = modifier.height(120.dp).squish()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(44.dp).clip(shape).background(content.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Symbol(glyph, filled = true) }
            Column {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A small heading inside a search section ("Artists", "Albums", "Songs"). */
@Composable
private fun SearchSubheading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
