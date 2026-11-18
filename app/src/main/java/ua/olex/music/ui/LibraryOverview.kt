package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.TextFold
import ua.olex.music.model.Track

/** A file-only view with recoverable deletion through the system's media trash. */
@Composable
internal fun LibraryOverview(
    tracks: List<Track>, modifier: Modifier, onDuplicates: () -> Unit, onStorage: () -> Unit,
    onTrash: (List<Track>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val files = rememberOffMain(tracks, initial = emptyList()) {
        tracks.filter { it.uri.startsWith("content://") && '#' !in it.id.value }.distinctBy { it.uri }
    }
    val visible = rememberOffMain(files, query, initial = emptyList()) {
        val terms = TextFold.fold(query).split(' ').filter { it.isNotBlank() }
        files.filter { track ->
            val text = TextFold.fold(listOfNotNull(track.title, track.artist, track.album).joinToString(" "))
            terms.all { it in text }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalPlayerInset.current)) {
        item(key = "overview-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${files.size} local files", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDuplicates) { Text("Duplicates") }
                    FilledTonalButton(onClick = onStorage) { Text("Storage") }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Find local files") }, leadingIcon = { Symbol(Glyph.SEARCH) })
                if (selected.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val chosen = files.filter { it.uri in selected }
                        onTrash(chosen)
                        selected = emptySet()
                    }) { Text("Move ${selected.size} to trash") }
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                }
            }
        }
        if (visible.isEmpty()) item(key = "overview-empty") {
            Text(if (query.isBlank()) "No local files" else "No matching files", Modifier.padding(vertical = 16.dp))
        }
        items(visible, key = { it.uri }) { track ->
            val checked = track.uri in selected
            ListItem(headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Artwork(track.artworkUri, Modifier.size(40.dp), 10.dp) },
                trailingContent = { Checkbox(checked, { selected = if (it) selected + track.uri else selected - track.uri }) })
        }
    }
}
