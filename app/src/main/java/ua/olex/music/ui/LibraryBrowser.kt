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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
    LaunchedEffect(selectedId, groups) { if (selectedId != null && selected == null) selectedId = null }
    BackHandler(searching || selected != null) {
        if (searching) onQueryChange("") else selectedId = null
    }
    missingFavourite?.let { id ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { missingFavourite = null }, title = { Text("Remove unavailable favourite?") },
            text = { Text("The saved entry will be removed. Your music files are not changed.") },
            confirmButton = { TextButton(onClick = { personalActions?.removeFavourite?.invoke(id); missingFavourite = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { missingFavourite = null }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().padding(insets)) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            label = { Text("Search your music") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (!searching) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 16.dp) {
                LibraryTab.entries.forEach { item ->
                    Tab(selected = item == tab, onClick = { tabName = item.name; selectedId = null }, text = { Text(item.title) })
                }
            }
        }
        if (!searching && tab == LibraryTab.PLAYLISTS && personalActions != null) {
            PlaylistBrowser(personal.playlists, personalActions, modifier = Modifier.weight(1f), currentId = currentId, onPlay = onPlay, onTrackActions = onTrackActions)
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
                            itemsIndexed(search.tracks, key = { _, track -> track.id.value }) { index, track ->
                                TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }) { onPlay(search.tracks, index) }
                            }
                        }
                    }
                }
                selected != null -> {
                    item(key = "collection-header") {
                        CollectionHeader(selected, onBack = { selectedId = null }, onPlay = { onPlay(selected.tracks, 0) }, onAdd = { onAddToPlaylist(selected.tracks) })
                    }
                    itemsIndexed(selected.tracks, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }) { onPlay(selected.tracks, index) }
                    }
                }
                tab == LibraryTab.SONGS -> {
                    if (library.songs.isEmpty()) item { BrowserMessage("No music yet. Put music in the Music folder, then rescan.") }
                    itemsIndexed(library.songs, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId, onMore = { onTrackActions(track) }) { onPlay(library.songs, index) }
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
private fun CollectionHeader(collection: MusicCollection, onBack: () -> Unit, onPlay: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("Back to collections") }
        Text(collection.name, style = MaterialTheme.typography.headlineMedium)
        collection.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onPlay) { Text("Play all") }
            Text(songCount(collection.tracks.size), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onAdd) { Text("Add all to playlist") }
    }
}

@Composable
private fun BrowserMessage(text: String) {
    Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"
