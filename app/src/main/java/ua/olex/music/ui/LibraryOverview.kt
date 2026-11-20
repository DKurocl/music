package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
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
    val cs = MaterialTheme.colorScheme
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + LocalPlayerInset.current),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "overview-controls") {
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${files.size} local files", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDuplicates, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Symbol(Glyph.DUPLICATES, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("Duplicates") }
                    FilledTonalButton(onClick = onStorage, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Symbol(Glyph.FOLDER, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("Storage") }
                }
                // The same filled pill as the app's search field, not an outlined form box.
                TextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Find local files") }, leadingIcon = { Symbol(Glyph.SEARCH) },
                    trailingIcon = if (query.isEmpty()) null else ({ IconButton(onClick = { query = "" }) { Symbol(Glyph.CLOSE) } }),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                androidx.compose.animation.AnimatedVisibility(selected.isNotEmpty()) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val chosen = files.filter { it.uri in selected }
                            onTrash(chosen)
                            selected = emptySet()
                        }, colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) {
                            Symbol(Glyph.DELETE, size = 18.dp); Spacer(Modifier.width(8.dp)); Text("Move ${selected.size} to trash")
                        }
                        TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                    }
                }
            }
        }
        if (visible.isEmpty()) item(key = "overview-empty") {
            Text(if (query.isBlank()) "No local files" else "No matching files", Modifier.padding(16.dp), color = cs.onSurfaceVariant)
        }
        items(visible, key = { it.uri }) { track ->
            val checked = track.uri in selected
            val toggle = { selected = if (checked) selected - track.uri else selected + track.uri }
            // Tap a row to choose it, as Google Files does: the row turns tonal and a tick covers the cover.
            val container by androidx.compose.animation.animateColorAsState(if (checked) cs.secondaryContainer else cs.surfaceContainerLow, label = "file")
            ListItem(headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Box(Modifier.size(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Artwork(track.artworkUri, Modifier.size(40.dp), 10.dp)
                        androidx.compose.animation.AnimatedVisibility(checked, enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
                            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(cs.primary),
                                contentAlignment = androidx.compose.ui.Alignment.Center) { Symbol(Glyph.CHECK, color = cs.onPrimary, size = 22.dp) }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = container),
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .toggleable(checked, onValueChange = { toggle() }, role = androidx.compose.ui.semantics.Role.Checkbox))
        }
    }
}
