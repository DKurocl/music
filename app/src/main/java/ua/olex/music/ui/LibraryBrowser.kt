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
import androidx.compose.ui.unit.dp
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.MusicCollection
import ua.olex.music.model.Track

private enum class LibraryTab(val title: String) {
    SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists"), FAVOURITES("Favourites"), HISTORY("History"), FOLDERS("Folders"), GENRES("Genres")
}

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
    personal: PersonalLibraryState = PersonalLibraryState(),
    personalActions: PersonalLibraryActions? = null,
    onTrackActions: (Track) -> Unit = {},
    onAddToPlaylist: (List<Track>) -> Unit = {},
    sortOrders: Map<TrackSortScope, TrackSortOrder> = emptyMap(),
    onSortOrder: (TrackSortScope, TrackSortOrder) -> Unit = { _, _ -> },
    onEnqueue: (List<Track>, Boolean) -> Unit = { _, _ -> },
) {
    var tabName by rememberSaveable { mutableStateOf(LibraryTab.SONGS.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var missingFavourite by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = LibraryTab.valueOf(tabName)
    val groups = when (tab) {
        LibraryTab.ALBUMS -> library.albums
        LibraryTab.ARTISTS -> library.artists
        LibraryTab.GENRES -> library.genres
        LibraryTab.FOLDERS -> library.folders
        else -> emptyList()
    }
    val selected = groups.firstOrNull { it.id == selectedId }
    val searching = query.isNotBlank()
    val sortScope = when {
        searching -> TrackSortScope.SEARCH
        selected == null -> TrackSortScope.SONGS
        tab == LibraryTab.ALBUMS -> TrackSortScope.ALBUM_TRACKS
        tab == LibraryTab.ARTISTS -> TrackSortScope.ARTIST_TRACKS
        tab == LibraryTab.GENRES -> TrackSortScope.GENRE_TRACKS
        else -> TrackSortScope.FOLDER_TRACKS
    }
    val unsorted = when {
        searching -> search?.takeIf { it.query == query && !it.failed }?.tracks
        selected != null -> selected.tracks
        tab == LibraryTab.SONGS -> library.songs
        else -> null
    }
    val order = sortOrders[sortScope] ?: TrackSortOrder()
    val source = unsorted?.let(::SortSource)
    val sorted by produceState<SortedTracks?>(null, source, order) {
        value = if (source == null || order == TrackSortOrder()) null else withContext(Dispatchers.Default) {
            SortedTracks(source, order, order.sort(source.tracks))
        }
    }
    val visible = if (order == TrackSortOrder()) unsorted else sorted?.takeIf { it.source == source && it.order == order }?.tracks
    val selectionContext = "${tab.name}:${selectedId.orEmpty().length}:${selectedId.orEmpty()}:${query.length}:$query"
    var picking by remember(selectionContext) { mutableStateOf(false) }
    var picked by remember(selectionContext) { mutableStateOf(TrackSelection()) }
    LaunchedEffect(source) { if (unsorted != null && picked.ids.isNotEmpty()) picked = picked.retain(unsorted) }
    fun batch(action: (List<Track>) -> Unit) { visible?.let(picked::ordered)?.takeIf { it.isNotEmpty() }?.let(action) }
    LaunchedEffect(selectedId, groups) { if (selectedId != null && selected == null) selectedId = null }
    BackHandler(searching || selected != null) {
        if (searching) onQueryChange("") else selectedId = null
    }
    BackHandler(picking) { picking = false; picked = TrackSelection() }
    missingFavourite?.let { id ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { missingFavourite = null }, title = { Text("Remove unavailable favourite?") },
            text = { Text("The saved entry will be removed. Your music files are not changed.") },
            confirmButton = { TextButton(onClick = { personalActions?.removeFavourite?.invoke(id); missingFavourite = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { missingFavourite = null }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().padding(insets)) {
        // A search pill, as in Google's apps, and the library's sections as chips below it.
        androidx.compose.material3.TextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            placeholder = { Text("Search your music") },
            leadingIcon = { Symbol(Glyph.SEARCH, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = "Clear" }) { Symbol(Glyph.CLOSE) }
            },
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (!searching) {
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
        if (unsorted != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                LibrarySortControl(sortScope, order) { onSortOrder(sortScope, it) }
                if (!picking) TextButton(onClick = { picking = true }) { Text("Select") }
            }
        }
        if (picking) TrackSelectionToolbar(picked.ids.size, visible?.size ?: 0, visible != null,
            onAll = { visible?.let { picked = picked.all(it) } }, onClear = { picked = TrackSelection() },
            onClose = { picking = false; picked = TrackSelection() }, onPlay = { batch { onPlay(it, 0) } },
            onNext = { batch { onEnqueue(it, true) } }, onQueue = { batch { onEnqueue(it, false) } },
            onPlaylist = { batch(onAddToPlaylist) })
        if (!searching && tab == LibraryTab.PLAYLISTS && personalActions != null) {
            PlaylistBrowser(personal.playlists, personalActions, modifier = Modifier.weight(1f), currentId = currentId, onPlay = onPlay, onTrackActions = onTrackActions, library = library)
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            scanError?.let { item(key = "scan-error") { ErrorNotice(it, onRescan) } }
            when {
                searching -> {
                    when {
                        search == null || search.query != query -> item { BrowserMessage("Searching…") }
                        search.failed -> item { ErrorNotice("Couldn't search your library.", onRetrySearch) }
                        search.tracks.isEmpty() -> item { BrowserMessage("No matching songs. Try a title, artist or album.") }
                        else -> {
                            item { BrowserMessage(songCount(search.tracks.size)) }
                            if (visible == null) item { BrowserMessage("Sorting...") }
                            else itemsIndexed(visible, key = { _, track -> track.id.value }) { index, track ->
                                TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                            checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }) { onPlay(visible, index) }
                            }
                        }
                    }
                }
                selected != null -> {
                    item(key = "collection-header") {
                        CollectionHeader(selected, onBack = { selectedId = null }, onPlay = { visible?.let { onPlay(it, 0) } }, onAdd = { visible?.let(onAddToPlaylist) }, enabled = !visible.isNullOrEmpty())
                    }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else itemsIndexed(visible, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                            checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }) { onPlay(visible, index) }
                    }
                }
                tab == LibraryTab.SONGS -> {
                    if (library.songs.isEmpty()) item { BrowserMessage("No music yet. Put music in the Music folder, then rescan.") }
                    if (visible == null) item { BrowserMessage("Sorting...") }
                    else itemsIndexed(visible, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }, selecting = picking,
                            checked = track.id in picked.ids, onSelect = { picking = true; picked = picked.toggle(track.id) }) { onPlay(visible, index) }
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
                // Albums as a grid of covers, two to a row (four on a wide screen), as music apps show them.
                tab == LibraryTab.ALBUMS -> items(groups.chunked(2), key = { row -> row.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { album -> AlbumCard(album, Modifier.weight(1f)) { selectedId = album.id } }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
                else -> items(groups, key = { it.id }) { collection ->
                    ListItem(
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
}

@Composable
private fun CollectionHeader(collection: MusicCollection, onBack: () -> Unit, onPlay: () -> Unit, onAdd: () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val minutes = collection.tracks.sumOf { it.durationMs } / 60_000
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) {
            androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to collections" }) { Symbol(Glyph.BACK) }
        }
        Artwork(collection.artworkUri, Modifier.size(220.dp), 28.dp, pixels = ua.olex.music.model.ArtworkPlan.LARGE)
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Text(collection.name, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        collection.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(
            listOfNotNull(songCount(collection.tracks.size), if (minutes > 0) "$minutes min" else null,
                collection.tracks.mapNotNull { it.format?.label }.distinct().singleOrNull()).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = enabled, onClick = onPlay, modifier = Modifier.height(52.dp)) {
                Symbol(Glyph.PLAY, size = 20.dp, filled = true); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp)); Text("Play all")
            }
            androidx.compose.material3.FilledTonalIconButton(enabled = enabled, onClick = onAdd,
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Add all to playlist" }) { Symbol(Glyph.PLAYLIST_ADD) }
        }
    }
}

@Composable
private fun AlbumCard(album: MusicCollection, modifier: Modifier, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
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
    }
    androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = true }) { Text("Sort: ${label(order.mode)}${if (order.descending) " descending" else ""}") }
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
    onClose: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, onPlaylist: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close selection") }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = ready && total <= TrackSelection.MAX_ITEMS, onClick = onAll) { Text("Select all") }
            TextButton(enabled = count > 0, onClick = onClear) { Text("Clear selection") }
            TextButton(enabled = ready && count > 0, onClick = onPlay) { Text("Play selected") }
            TextButton(enabled = ready && count > 0, onClick = onNext) { Text("Play next") }
            TextButton(enabled = ready && count > 0, onClick = onQueue) { Text("Add to queue") }
            TextButton(enabled = ready && count > 0, onClick = onPlaylist) { Text("Add to playlist") }
        }
        if (total > TrackSelection.MAX_ITEMS || count >= TrackSelection.MAX_ITEMS)
            Text("Select up to ${TrackSelection.MAX_ITEMS} songs per batch.", style = MaterialTheme.typography.bodySmall)
    }
}
