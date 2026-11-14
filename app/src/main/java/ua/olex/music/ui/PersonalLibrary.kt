package ua.olex.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.Track

/** Full playlist editing keeps unavailable entries visible and never deletes their audio files. */
@Composable
internal fun PlaylistBrowser(
    playlists: List<PlaylistUi>, actions: PersonalLibraryActions, modifier: Modifier = Modifier,
    currentId: String?, onPlay: (List<Track>, Int) -> Unit, onTrackActions: (Track) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var namingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var create by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingSong by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = playlists.firstOrNull { it.id == selectedId }
    BackHandler(selected != null) { selectedId = null }

    if (create || namingId != null) {
        val name = playlists.firstOrNull { it.id == namingId }?.name.orEmpty()
        PlaylistNameDialog(if (create) "New playlist" else "Rename playlist", name,
            onDismiss = { create = false; namingId = null },
            onSave = { entered ->
                if (create) actions.create(entered, emptyList()) else namingId?.let { actions.rename(it, entered) }
                create = false; namingId = null
            })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete playlist?") },
            text = { Text("Your music files and favourites will be kept.") },
            confirmButton = { TextButton(onClick = { actions.delete(id); deleting = null; selectedId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    val editRow = selected?.songs?.firstOrNull { it.song.id == editingSong }
    if (selected != null && editRow != null) {
        val index = selected.songs.indexOf(editRow)
        AlertDialog(onDismissRequest = { editingSong = null }, title = { Text(editRow.song.title) }, text = {
            Column {
                if (editRow.track != null) TextButton(onClick = { onTrackActions(editRow.track); editingSong = null }) { Text("Song actions") }
                TextButton(enabled = index > 0, onClick = { actions.move(selected.id, editRow.song.id, -1); editingSong = null }) { Text("Move up") }
                TextButton(enabled = index < selected.songs.lastIndex, onClick = { actions.move(selected.id, editRow.song.id, 1); editingSong = null }) { Text("Move down") }
                TextButton(onClick = { actions.remove(selected.id, editRow.song.id); editingSong = null }) { Text("Remove from playlist") }
            }
        }, confirmButton = { TextButton(onClick = { editingSong = null }) { Text("Close") } })
    }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (selected == null) {
            item { TextButton(onClick = { create = true }, modifier = Modifier.padding(16.dp)) { Text("New playlist") } }
            if (playlists.isEmpty()) item { Text("Create a playlist, then add songs or an entire album from their actions.", Modifier.padding(16.dp)) }
            items(playlists, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text("${playlist.songs.size} songs") },
                    modifier = Modifier.clickable { selectedId = playlist.id },
                )
            }
        } else {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedId = null }) { Text("Back to playlists") }
                    Text(selected.name, style = MaterialTheme.typography.headlineMedium)
                    Text("${selected.songs.size} songs · ${selected.songs.size - selected.playable.size} unavailable")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = selected.playable.isNotEmpty(), onClick = { onPlay(selected.playable, 0) }) { Text("Play all") }
                        TextButton(onClick = { namingId = selected.id }) { Text("Rename") }
                        TextButton(onClick = { deleting = selected.id }) { Text("Delete") }
                    }
                }
            }
            if (selected.songs.isEmpty()) item { Text("Add music from a song's More menu or an album's Add all to playlist action.", Modifier.padding(16.dp)) }
            items(selected.songs, key = { it.song.id }) { row ->
                SavedSongItem(row, currentId, onPlay = {
                    val index = selected.playable.indexOfFirst { it.id == row.track?.id }
                    if (index >= 0) onPlay(selected.playable, index)
                }, onMore = { editingSong = row.song.id })
            }
        }
    }
}

@Composable
internal fun SavedSongItem(row: SavedSongRow, currentId: String?, onPlay: () -> Unit, onMore: (() -> Unit)?) {
    if (row.track != null) TrackRow(row.track, current = row.track.id.value == currentId, onClick = onPlay, onMore = onMore)
    else ListItem(
        headlineContent = { Text(row.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("Unavailable on this phone · ${row.song.artist}") },
        trailingContent = { if (onMore != null) TextButton(onClick = onMore) { Text("More") } },
    )
}

@Composable
internal fun PlaylistNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Allows a whole collection to be saved in one action, including creation of a new playlist. */
@Composable
internal fun PlaylistPicker(playlists: List<PlaylistUi>, tracks: List<Track>, actions: PersonalLibraryActions, onDismiss: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        PlaylistNameDialog("New playlist", onDismiss = { creating = false }, onSave = { actions.create(it, tracks); onDismiss() })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to playlist") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text("${tracks.size} songs selected") }
            items(playlists, key = { it.id }) { playlist ->
                TextButton(onClick = { actions.add(playlist.id, tracks); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(playlist.name) }
            }
        }
    }, confirmButton = { TextButton(onClick = { creating = true }) { Text("New playlist") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
