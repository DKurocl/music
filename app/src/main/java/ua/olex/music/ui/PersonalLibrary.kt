@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.olex.music.model.LibraryIndex
import ua.olex.music.model.SavedSong
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.toShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
    library: LibraryIndex = LibraryIndex.EMPTY,
    openPlaylist: Long? = null, onOpenedPlaylist: () -> Unit = {},
    onPlayFrom: ((List<Track>, Int, String) -> Unit)? = null,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(openPlaylist, playlists) {
        if (openPlaylist != null && playlists.any { it.id == openPlaylist }) {
            selectedId = openPlaylist
            onOpenedPlaylist()
        }
    }
    fun playPlaylist(playlist: PlaylistUi, index: Int) {
        val origin = PlaybackOrigin("playlist", playlist.id.toString(), playlist.name).encode()
        if (onPlayFrom != null) onPlayFrom(playlist.playable, index, origin) else onPlay(playlist.playable, index)
    }
    var namingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var create by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingSong by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    fun removeOccurrence(id: Long, song: String, index: Int) {
        val remove = actions.removeWithUndo
        if (remove == null || snackbar == null) {
            actions.remove(id, song, index)
            return
        }
        scope.launch {
            try {
                val undo = remove(id, song, index)
                if (undo == null) {
                    snackbar.showSnackbar("The playlist changed. Try again.")
                } else if (snackbar.showSnackbar("Song removed", actionLabel = "Undo", withDismissAction = true,
                        duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed && !undo()) {
                    snackbar.showSnackbar("The playlist changed. The song couldn't be restored.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("Couldn't update the playlist.") }
        }
    }
    var loadingDocument by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<PlaylistDocuments.Loaded?>(null) }
    var documentMessage by remember { mutableStateOf<String?>(null) }
    var exportSnapshot by remember { mutableStateOf<Pair<String, List<SavedSong>>?>(null) }
    var exportPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loadingDocument = true
            try { loaded = withContext(Dispatchers.IO) { PlaylistDocuments.read(context, uri) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { documentMessage = "Couldn't read this playlist. Use a UTF-8 M3U8 file, up to 8 MB and 20000 entries. HLS streams are not local playlists." }
            finally { loadingDocument = false }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val snapshot = exportSnapshot ?: playlists.firstOrNull { it.id == exportPlaylistId }?.let { playlist ->
            playlist.name to playlist.songs.map { row -> row.track?.let(SavedSong::from) ?: row.song }
        }
        exportSnapshot = null; exportPlaylistId = null
        if (uri != null && snapshot == null) documentMessage = "The playlist is no longer available. Open it and try exporting again."
        if (uri != null && snapshot != null) scope.launch {
            loadingDocument = true
            try {
                val text = withContext(Dispatchers.Default) { PlaylistDocuments.export(snapshot.first, snapshot.second) }
                withContext(Dispatchers.IO) { PlaylistDocuments.write(context, uri, text) }
                documentMessage = "Exported ${snapshot.second.size} songs. Missing file references are included; YouTube Music songs are saved as their music.youtube.com links."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { documentMessage = "Couldn't complete the export. Some songs may lack a file path, or the destination could not be written. The selected document may be incomplete." }
            finally { loadingDocument = false }
        }
    }
    loaded?.let { document -> PlaylistImportDialog(document, library, onDismiss = { loaded = null }, onImport = { name, songs ->
        actions.importPlaylist(name, songs); loaded = null
    }) }
    documentMessage?.let { message -> AppDialog(onDismissRequest = { documentMessage = null }, title = { Text("Playlist file") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { documentMessage = null }) { Text("Close") } }) }
    val selected = playlists.firstOrNull { it.id == selectedId }
    val back = rememberLibraryBack(selected != null) { selectedId = null }
    // Signed in to YouTube Music: his playlists there too (Liked music first), loaded once a session.
    val graph = (context.applicationContext as ua.olex.music.MusicApp).graph
    val accountScope by graph.youTubeAccount.cacheScope.collectAsStateWithLifecycle()
    val youTubeOn by graph.youTubeOn.collectAsStateWithLifecycle()
    val network = LocalNetwork.current
    val youTubePlaylists = androidx.compose.runtime.key(accountScope, youTubeOn) {
        val loaded by androidx.compose.runtime.produceState(emptyList<YouTubeMusic.Card>(), network) {
            if (accountScope != "public" && youTubeOn) {
                value = try { graph.youTube.accountPlaylists(offline = !network) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { value }
            }
        }
        loaded
    }

    var editingRemote by remember(accountScope) { mutableStateOf<YouTubeMusic.Card?>(null) }
    editingRemote?.let { card ->
        RemotePlaylistSheet(card, graph.youTube, accountScope, onDismiss = { editingRemote = null })
    }

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
        AppDialog(onDismissRequest = { deleting = null }, title = { Text("Delete playlist?") },
            text = { Text("Your music files and favourites will be kept.") },
            confirmButton = { TextButton(onClick = { actions.delete(id); deleting = null; selectedId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    val editRow = editingSong?.let { selected?.songs?.getOrNull(it) }
    if (selected != null && editRow != null) {
        val index = editingSong!!
        AppDialog(onDismissRequest = { editingSong = null }, title = { Text(editRow.song.title) }, text = {
            Column {
                if (editRow.track != null) TextButton(onClick = { onTrackActions(editRow.track); editingSong = null }) { Text("Song actions") }
                TextButton(enabled = index > 0, onClick = { actions.move(selected.id, editRow.song.id, -1, index); editingSong = null }) { Text("Move up") }
                TextButton(enabled = index < selected.songs.lastIndex, onClick = { actions.move(selected.id, editRow.song.id, 1, index); editingSong = null }) { Text("Move down") }
                TextButton(onClick = { removeOccurrence(selected.id, editRow.song.id, index); editingSong = null }) { Text("Remove from playlist") }
            }
        }, confirmButton = { TextButton(onClick = { editingSong = null }) { Text("Close") } })
    }

    LazyColumn(modifier.libraryBackMotion(back), contentPadding = PaddingValues(bottom = 16.dp + LocalPlayerInset.current)) {
        if (loadingDocument) item { Text("Working with playlist file...", Modifier.padding(16.dp)) }
        if (selected == null) {
            // Two starts as cards: make one, or bring one from another player (M3U8).
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Surface(onClick = { create = true }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.weight(1f).height(64.dp).squish()) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Symbol(Glyph.ADD, bold = true); Spacer(Modifier.width(10.dp)); Text("New playlist", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    androidx.compose.material3.Surface(enabled = !loadingDocument, onClick = { importFile.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream")) },
                        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(64.dp).squish()) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Symbol(Glyph.FOLDER); Spacer(Modifier.width(10.dp)); Text("Import M3U8", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            if (playlists.isEmpty() && youTubePlaylists.isEmpty()) item { EmptyState(Glyph.PLAYLIST, "No playlists yet", "Make one here, then add songs or whole albums from their menus.") }
            items(playlists, key = { it.id }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(if (playlist.songs.size == 1) "1 song" else "${playlist.songs.size} songs") },
                    leadingContent = { PlaylistCover(playlist.playable, 56.dp) },
                    trailingContent = { Symbol(Glyph.CHEVRON, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).clickable { selectedId = playlist.id },
                )
            }
            // One list. Remote rows retain their source and editing capabilities.
            items(youTubePlaylists, key = { "yt-" + it.id }) { card ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { OnlineResultRow(card) }
                    if (card.id.removePrefix("VL").startsWith("PL")) {
                        IconButton(onClick = { editingRemote = card }, enabled = network,
                            modifier = Modifier.semantics { contentDescription = "Edit ${card.title} on YouTube Music" }) { Symbol(Glyph.MORE) }
                    }
                }
            }
        } else {
            item {
                // Like an album page: big mosaic, name, counts, Play all; the playlist's own tools as chips.
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) {
                        KickIconButton(onClick = { selectedId = null }, modifier = Modifier.semantics { contentDescription = "Back to playlists" }) { k -> Symbol(Glyph.BACK, kick = k) }
                    }
                    PlaylistCover(selected.playable, 200.dp, 28.dp)
                    Text(selected.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    val missing = selected.songs.size - selected.playable.size
                    Text(listOfNotNull(if (selected.songs.size == 1) "1 song" else "${selected.songs.size} songs", if (missing > 0) "$missing unavailable" else null).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = selected.playable.isNotEmpty(), onClick = { playPlaylist(selected, 0) }, modifier = Modifier.height(52.dp)) {
                        Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.width(8.dp)); Text("Play all")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.AssistChip(enabled = !loadingDocument, leadingIcon = { Symbol(Glyph.FOLDER, size = 18.dp) }, onClick = {
                            exportPlaylistId = selected.id
                            exportSnapshot = selected.name to selected.songs.map { row -> row.track?.let(SavedSong::from) ?: row.song }
                            exportFile.launch(PlaylistDocuments.filename(selected.name))
                        }, label = { Text("Export M3U8") })
                        androidx.compose.material3.AssistChip(leadingIcon = { Symbol(Glyph.EDIT, size = 18.dp) }, onClick = { namingId = selected.id }, label = { Text("Rename") })
                        androidx.compose.material3.AssistChip(leadingIcon = { Symbol(Glyph.DELETE, size = 18.dp) }, onClick = { deleting = selected.id }, label = { Text("Delete") })
                    }
                }
            }
            if (selected.songs.isEmpty()) item { EmptyState(Glyph.PLAYLIST_ADD, "Empty playlist", "Add songs from their menu, or a whole album with its Add button.") }
            // Keyed by position: the same song can be in a playlist twice.
            itemsIndexed(selected.songs, key = { position, row -> "$position:${row.song.id}" }) { position, row ->
                SavedSongItem(row, currentId, onPlay = {
                    if (row.track != null) playPlaylist(selected, selected.songs.take(position).count { it.track != null })
                }, onMore = { editingSong = position })
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
    AppDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
    var again by rememberSaveable { mutableStateOf(false) }
    // A sheet: new playlist first (the most common reason to open this), then each playlist with its cover.
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(if (tracks.size == 1) "Add to playlist" else "Add ${tracks.size} songs to", style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    ListItem(headlineContent = { Text("New playlist", style = MaterialTheme.typography.titleMedium) },
                        leadingContent = { androidx.compose.foundation.layout.Box(Modifier.size(56.dp).clip(androidx.compose.material3.MaterialShapes.Cookie6Sided.toShape())
                            .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = androidx.compose.ui.Alignment.Center) { Symbol(Glyph.ADD, bold = true, color = MaterialTheme.colorScheme.onPrimaryContainer) } },
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).clickable { creating = true })
                }
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(headlineContent = { Text(playlist.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = { Text(if (playlist.songs.size == 1) "1 song" else "${playlist.songs.size} songs") },
                        leadingContent = { PlaylistCover(playlist.playable, 56.dp) },
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).clickable { actions.add(playlist.id, tracks, again); onDismiss() })
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Add again if already there", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(again, { again = it })
            }
        }
    }
}
