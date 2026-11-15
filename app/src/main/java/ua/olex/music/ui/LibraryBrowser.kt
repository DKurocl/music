@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

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
    /** Bumped when the Search tab is tapped again: back to the tiles and the field focused (as Google Play does). */
    focusSearch: Int = 0,
    /** Open a Search tile from elsewhere (Home's "see all" for History and Smart playlists). */
    openTile: String? = null,
) {
    val startTab = LocalStartTab.current
    var tabName by rememberSaveable { mutableStateOf(startTab) }
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
    LaunchedEffect(open) { open?.let { (t, id) -> tabName = t; selectedId = id; onOpened() } }
    LaunchedEffect(closeCollection) { if (closeCollection > 0) selectedId = null }
    BackHandler(searching || selected != null) {
        if (searching) onQueryChange("") else selectedId = null
    }
    BackHandler(picking) { picking = false; picked = TrackSelection() }
    LaunchedEffect(openTile) { openTile?.let { t -> runCatching { BrowseTile.valueOf(t) }.getOrNull()?.let { tile -> browsing = tile.name; tabName = tile.tab.name; filter = tile.filter; onOpened() } } }
    BackHandler(mode == BrowserMode.SEARCH && browsing != null && selected == null && !searching) { browsing = null; filter = ua.olex.music.model.TrackFilter() }
    missingFavourite?.let { id ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { missingFavourite = null }, title = { Text("Remove unavailable favourite?") },
            text = { Text("The saved entry will be removed. Your music files are not changed.") },
            confirmButton = { TextButton(onClick = { personalActions?.removeFavourite?.invoke(id); missingFavourite = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { missingFavourite = null }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().padding(insets)) {
        // A search pill, as in Google's apps, and the library's sections as chips below it.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(focusSearch) { if (focusSearch > 0) { browsing = null; selectedId = null; filter = ua.olex.music.model.TrackFilter(); runCatching { focus.requestFocus() } } }
        val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onQueryChange)
        }
        if (mode != BrowserMode.LIBRARY) androidx.compose.material3.TextField(
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        if (mode == BrowserMode.SEARCH && !searching && selected == null && browsing == null) {
            BrowseTiles(library, recentSearches, onPickRecent = onQueryChange, onClearRecent = onClearRecent) { tile ->
                browsing = tile.name; tabName = tile.tab.name; filter = tile.filter
            }
            return@Column
        }
        // Inside a tile: its name with a way back to the tiles.
        if (mode == BrowserMode.SEARCH && !searching && selected == null) browsing?.let { name ->
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = { browsing = null; filter = ua.olex.music.model.TrackFilter() },
                    modifier = Modifier.semantics { contentDescription = "Back to browse" }) { Symbol(Glyph.BACK) }
                Text(BrowseTile.valueOf(name).title, style = MaterialTheme.typography.titleLarge)
            }
        }
        // Library: the four main kinds as a connected button group (M3E); the rest live in Search's tiles.
        if (!searching && mode == BrowserMode.LIBRARY && selected == null) KindSelector(tab) { tabName = it.name; selectedId = null }
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
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp + LocalPlayerInset.current)) {
            scanError?.let { item(key = "scan-error") { ErrorNotice(it, onRescan) } }
            when {
                searching -> {
                    when {
                        search == null || search.query != query -> item { BrowserMessage("Searching…") }
                        search.failed -> item { ErrorNotice("Couldn't search your library.", onRetrySearch) }
                        search.tracks.isEmpty() && searchGroups.isEmpty() -> item { BrowserMessage("No matches. Try a title, artist or album.") }
                        else -> {
                            searchGroups.groupBy { it.first }.forEach { (kind, hits) ->
                                item(key = "group-title-$kind") { SearchHeading(if (kind == LibraryTab.ARTISTS) "Artists" else "Albums") }
                            hits.forEach { (_, collection) ->
                                item(key = "group-$kind-${collection.id}") {
                                    androidx.compose.material3.ListItem(
                                        headlineContent = { Text(collection.name, maxLines = 1) },
                                        supportingContent = { Text(listOfNotNull(collection.subtitle, songCount(collection.tracks.size)).joinToString(" · "), maxLines = 1) },
                                        leadingContent = { if (kind == LibraryTab.ARTISTS) ArtistImage(collection.name, Modifier.size(48.dp), 48.dp) else Artwork(collection.artworkUri, Modifier.size(48.dp), corner = 8.dp) },
                                        modifier = Modifier.clickable { onSearchUsed(query); tabName = kind.name; selectedId = collection.id; onQueryChange("") },
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
                        }
                    }
                }
                selected != null -> {
                    item(key = "collection-header") {
                        CollectionHeader(selected, artist = tab == LibraryTab.ARTISTS, showBack = mode == BrowserMode.ALL, onBack = { selectedId = null }, onPlay = { visible?.let { onPlay(it, 0) } }, onShuffle = { visible?.let(onShuffle) }, onAdd = { visible?.let(onAddToPlaylist) }, enabled = !visible.isNullOrEmpty())
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
                    if (rows.isEmpty()) item { BrowserMessage(if (tab == LibraryTab.FAVOURITES) "Save songs from their More menu to find them here." else "Songs you play will appear here, most recent first.") }
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
                        modifier = Modifier.clickable(onClickLabel = "Open ${collection.name}") { selectedId = collection.id },
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
            onPlaylist = { batch(onAddToPlaylist) }, onTags = { batch(onEditTags) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp + LocalPlayerInset.current))
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
                collection.tracks.mapNotNull { it.year?.takeIf { y -> y > 0 } }.distinct().sorted().let { ys -> when { ys.isEmpty() -> null; ys.size == 1 -> ys[0].toString(); else -> "${ys.first()}–${ys.last()}" } }
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
        }
    }
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

private fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"

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
private fun SelectionActions(enabled: Boolean, onPlay: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onPlaylist: () -> Unit, onTags: () -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    androidx.compose.material3.HorizontalFloatingToolbar(expanded = true, modifier = modifier,
        colors = androidx.compose.material3.FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
        listOf(Triple(Glyph.PLAY, "Play selected", onPlay), Triple(Glyph.NEXT, "Play next", onNext), Triple(Glyph.QUEUE, "Add to queue", onQueue),
            Triple(Glyph.PLAYLIST_ADD, "Add to playlist", onPlaylist), Triple(Glyph.EDIT, "Edit tags", onTags)).forEachIndexed { i, (glyph, label, action) ->
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
private enum class CollectionSort(val title: String) { NAME("A–Z"), NEWEST("Newest"), YEAR("Year"), PLAYED("Most played") }

@Composable
private fun SearchHeading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))

/** Search's browse tiles: kinds of the library that are not in Library's four, and quick filters. */
private enum class BrowseTile(val title: String, val glyph: Glyph, val tab: LibraryTab, val filter: ua.olex.music.model.TrackFilter = ua.olex.music.model.TrackFilter()) {
    GENRES("Genres", Glyph.GENRE, LibraryTab.GENRES), YEARS("Years", Glyph.CALENDAR, LibraryTab.YEARS),
    FAVOURITES("Favourites", Glyph.FAVOURITE, LibraryTab.FAVOURITES), SMART("Smart playlists", Glyph.SMART, LibraryTab.SMART),
    HISTORY("History", Glyph.HISTORY, LibraryTab.HISTORY), FOLDERS("Folders", Glyph.FOLDER, LibraryTab.FOLDERS),
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
private fun BrowseTiles(library: LibraryIndex, recent: List<String>, onPickRecent: (String) -> Unit, onClearRecent: () -> Unit, onTile: (BrowseTile) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val tiles = BrowseTile.entries.filter { t ->
        when (t) { BrowseTile.COMPOSERS -> library.composers.isNotEmpty(); BrowseTile.YEARS -> library.years.size > 1; BrowseTile.GENRES -> library.genres.size > 1
            BrowseTile.HI_RES -> library.songs.any { it.format?.hiRes == true }; BrowseTile.LOSSLESS -> library.songs.any { it.format?.lossless == true }; else -> true }
    }
    val accents = listOf(cs.primaryContainer to cs.onPrimaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer, cs.secondaryContainer to cs.onSecondaryContainer)
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalPlayerInset.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recent.isNotEmpty()) item { RecentSearches(recent.take(4), onPick = onPickRecent, onClear = onClearRecent) }
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
    }
}

/** Library's kinds as a connected M3E button group: four equal targets, the selected one filled. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KindSelector(tab: LibraryTab, onSelect: (LibraryTab) -> Unit) {
    val haptics = rememberHaptics()
    val kinds = listOf(LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.SONGS, LibraryTab.PLAYLISTS)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
        kinds.forEachIndexed { i, kind ->
            androidx.compose.material3.ToggleButton(
                checked = tab == kind, onCheckedChange = { if (tab != kind) { haptics.tick(); onSelect(kind) } },
                shapes = when (i) {
                    0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                    kinds.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.weight(1f).semantics { contentDescription = kind.title },
            ) { Text(kind.title, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
        }
    }
}
