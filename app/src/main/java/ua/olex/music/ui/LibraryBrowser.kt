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

private enum class LibraryTab(val title: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), GENRES("Genres"), FOLDERS("Folders") }

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
) {
    var tabName by rememberSaveable { mutableStateOf(LibraryTab.SONGS.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = LibraryTab.valueOf(tabName)
    val groups = when (tab) {
        LibraryTab.ALBUMS -> library.albums
        LibraryTab.ARTISTS -> library.artists
        LibraryTab.GENRES -> library.genres
        LibraryTab.FOLDERS -> library.folders
        LibraryTab.SONGS -> emptyList()
    }
    val selected = groups.firstOrNull { it.id == selectedId }
    val searching = query.isNotBlank()
    LaunchedEffect(selectedId, groups) { if (selectedId != null && selected == null) selectedId = null }
    BackHandler(searching || selected != null) {
        if (searching) onQueryChange("") else selectedId = null
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
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            scanError?.let { item(key = "scan-error") { ErrorNotice(it, onRescan) } }
            when {
                searching -> {
                    when {
                        search == null || search.query != query -> item { BrowserMessage("Searching…") }
                        search.failed -> item { BrowserMessage("Couldn't search your library. Try another query or rescan.") }
                        search.tracks.isEmpty() -> item { BrowserMessage("No matching songs. Try a title, artist or album.") }
                        else -> {
                            item { BrowserMessage(songCount(search.tracks.size)) }
                            itemsIndexed(search.tracks, key = { _, track -> track.id.value }) { index, track ->
                                TrackRow(track, current = track.id.value == currentId) { onPlay(search.tracks, index) }
                            }
                        }
                    }
                }
                selected != null -> {
                    item(key = "collection-header") {
                        CollectionHeader(selected, onBack = { selectedId = null }, onPlay = { onPlay(selected.tracks, 0) })
                    }
                    itemsIndexed(selected.tracks, key = { _, track -> track.id.value }) { index, track ->
                        TrackRow(track, current = track.id.value == currentId) { onPlay(selected.tracks, index) }
                    }
                }
                tab == LibraryTab.SONGS -> itemsIndexed(library.songs, key = { _, track -> track.id.value }) { index, track ->
                    TrackRow(track, current = track.id.value == currentId) { onPlay(library.songs, index) }
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
private fun CollectionHeader(collection: MusicCollection, onBack: () -> Unit, onPlay: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("Back to collections") }
        Text(collection.name, style = MaterialTheme.typography.headlineMedium)
        collection.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onPlay) { Text("Play all") }
            Text(songCount(collection.tracks.size), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BrowserMessage(text: String) {
    Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"
