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
    shortcuts: (@Composable () -> Unit)? = null,
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
    var playlistMenu by remember { mutableStateOf(false) }
    var adding by rememberSaveable(selectedId) { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingSong by rememberSaveable(selectedId) { mutableStateOf<Int?>(null) }
    var editingSongId by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
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
    var exportText by remember { mutableStateOf<String?>(null) }
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
        val prepared = exportText
        val source = playlists.firstOrNull { it.id == exportPlaylistId }
        exportText = null; exportPlaylistId = null
        if (uri != null && prepared == null && source == null) documentMessage = "The playlist is no longer available. Open it and try exporting again."
        if (uri != null && (prepared != null || source != null)) scope.launch {
            loadingDocument = true
            try {
                // Android may recreate the activity while the system document picker is open.
                val text = prepared ?: withContext(Dispatchers.Default) {
                    val playlist = requireNotNull(source)
                    PlaylistDocuments.export(playlist.name, playlist.songs.map { row -> row.track?.let(SavedSong::from) ?: row.song })
                }
                withContext(Dispatchers.IO) { PlaylistDocuments.write(context, uri, text) }
                documentMessage = "Playlist exported. Local file references and YouTube Music links are included."
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
    var remoteRetry by remember(accountScope) { mutableStateOf(0) }
    var remoteFailed by remember(accountScope, youTubeOn) { mutableStateOf(false) }
    var remoteLoading by remember(accountScope, youTubeOn) { mutableStateOf(false) }
    val youTubePlaylists = androidx.compose.runtime.key(accountScope, youTubeOn) {
        val loaded by androidx.compose.runtime.produceState(emptyList<YouTubeMusic.Card>(), network, remoteRetry) {
            if (accountScope != "public" && youTubeOn) {
                remoteLoading = true
                remoteFailed = false
                value = try { graph.youTube.accountPlaylists(offline = !network) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { remoteFailed = true; value }
                finally { remoteLoading = false }
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
    val editRow = editingSong?.let { selected?.songs?.getOrNull(it) }?.takeIf { it.song.id == editingSongId }
    androidx.compose.runtime.LaunchedEffect(editingSong, editRow) { if (editRow == null) editingSong = null }
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

    val playlistList = androidx.compose.foundation.lazy.rememberLazyListState()
    val songList = androidx.compose.runtime.key(selected?.id) { androidx.compose.foundation.lazy.rememberLazyListState() }
    val nav = LocalOnlineNav.current
    LazyColumn(modifier.libraryBackMotion(back).arrivalMotion(selected?.id, "playlists", if (selected == null) 0 else 1),
        state = if (selected == null) playlistList else songList, contentPadding = PaddingValues(bottom = 16.dp + LocalPlayerInset.current)) {
        if (loadingDocument) item { Text("Working with playlist file...", Modifier.padding(16.dp)) }
        if (selected == null) {
            if (shortcuts != null) item(key = "playlist-shortcuts") { shortcuts() }
            item(key = "playlist-heading") {
                SectionHeader("Your playlists", trailing = {
                    Box {
                        FilledTonalIconButton(onClick = { playlistMenu = true }, enabled = !loadingDocument,
                            modifier = Modifier.semantics { contentDescription = "Add a playlist" }) { Symbol(Glyph.ADD) }
                        DropdownMenu(expanded = playlistMenu, onDismissRequest = { playlistMenu = false }) {
                            DropdownMenuItem(text = { Text("New playlist") }, leadingIcon = { Symbol(Glyph.ADD) },
                                onClick = { playlistMenu = false; create = true })
                            DropdownMenuItem(text = { Text("Import M3U8") }, leadingIcon = { Symbol(Glyph.FOLDER) }, onClick = {
                                playlistMenu = false
                                importFile.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream"))
                            })
                        }
                    }
                })
            }
            if (remoteLoading && youTubePlaylists.isEmpty()) item(key = "loading-remote-playlists") { PlaceholderRow() }
            if (remoteFailed) item(key = "retry-remote-playlists") { ErrorNotice("Couldn't load your YouTube Music playlists.") { remoteRetry++ } }
            if (!remoteLoading && !remoteFailed && playlists.isEmpty() && youTubePlaylists.isEmpty()) item(key = "empty-playlists") {
                Text("No playlists yet", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            items(playlists, key = { it.id }) { playlist ->
                PlaylistListRow(playlist.name, songCount(playlist.songs.size),
                    cover = { PlaylistCover(playlist.playable, 52.dp, 13.dp) }, onOpen = { selectedId = playlist.id })
            }
            items(youTubePlaylists, key = { "yt-" + it.id }) { card ->
                val edit: (() -> Unit)? = if (card.id.removePrefix("VL").startsWith("PL") && network) ({ editingRemote = card }) else null
                PlaylistListRow(card.title, card.subtitle, fromYouTube = true, onEdit = edit,
                    cover = { Artwork(card.cover, Modifier.size(52.dp), 13.dp) },
                    onOpen = { nav.open(OnlineTarget.Album(card.id, card.title, playlist = true, cover = card.cover, subtitle = card.subtitle)) })
            }
        } else {
            item {
                // Like an album page: big mosaic, name, counts, Play all; the playlist's own tools as chips.
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        PlaylistCover(selected.playable, if (selected.songs.isEmpty()) 96.dp else 200.dp, 28.dp)
                        KickIconButton(onClick = { selectedId = null }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopStart)
                            .semantics { contentDescription = "Back to playlists" }) { k -> Symbol(Glyph.BACK, kick = k) }
                    }
                    Text(selected.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    val missing = selected.songs.size - selected.playable.size
                    Text(listOfNotNull(if (selected.songs.size == 1) "1 song" else "${selected.songs.size} songs", if (missing > 0) "$missing unavailable" else null).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected.playable.isNotEmpty()) Button(onClick = { playPlaylist(selected, 0) }, modifier = Modifier.height(52.dp)) {
                            Symbol(Glyph.PLAY, size = 20.dp, filled = true); Spacer(Modifier.width(8.dp)); Text("Play all")
                        }
                        FilledTonalButton(onClick = { adding = true }, modifier = Modifier.height(52.dp)) { Text("Add songs") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.AssistChip(enabled = !loadingDocument && selected.songs.isNotEmpty(), leadingIcon = { Symbol(Glyph.FOLDER, size = 18.dp) }, onClick = {
                            loadingDocument = true
                            scope.launch {
                                try {
                                    exportText = withContext(Dispatchers.Default) {
                                        PlaylistDocuments.export(selected.name, selected.songs.map { row -> row.track?.let(SavedSong::from) ?: row.song })
                                    }
                                    exportPlaylistId = selected.id
                                    exportFile.launch(PlaylistDocuments.filename(selected.name))
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { documentMessage = "Couldn't export this playlist. Check that each song has a file path or a YouTube Music link." }
                                finally { loadingDocument = false }
                            }
                        }, label = { Text("Export M3U8") })
                        androidx.compose.material3.AssistChip(leadingIcon = { Symbol(Glyph.EDIT, size = 18.dp) }, onClick = { namingId = selected.id }, label = { Text("Rename") })
                        androidx.compose.material3.AssistChip(leadingIcon = { Symbol(Glyph.DELETE, size = 18.dp) }, onClick = { deleting = selected.id }, label = { Text("Delete") })
                    }
                }
            }
            if (adding) item(key = "add-songs") {
                var query by rememberSaveable { mutableStateOf("") }
                val matches = rememberOffMain(library, query, initial = emptyList()) {
                    val words = ua.olex.music.model.TextFold.fold(query).split(' ').filter { it.isNotBlank() }
                    library.songs.asSequence().filter { track ->
                        val name = ua.olex.music.model.TextFold.fold("${track.title} ${track.artist}")
                        words.all { it in name }
                    }.take(20).toList()
                }
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find songs to add") })
                    matches.forEach { track ->
                        ListItem(headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { IconButton(onClick = { actions.add(selected.id, listOf(track), false) },
                                modifier = Modifier.semantics { contentDescription = "Add ${track.title}" }) { Symbol(Glyph.ADD) } })
                    }
                    TextButton(onClick = { adding = false }) { Text("Done") }
                }
            }
            // Keyed by position: the same song can be in a playlist twice.
            itemsIndexed(selected.songs, key = { position, row -> "$position:${row.song.id}" }) { position, row ->
                SavedSongItem(row, currentId, onPlay = {
                    if (row.track != null) playPlaylist(selected, selected.songs.take(position).count { it.track != null })
                }, onMore = { editingSongId = row.song.id; editingSong = position })
            }
        }
    }
}

/** Local and online collections share row and cover geometry. */
@Composable
private fun PlaylistListRow(title: String, subtitle: String?, cover: @Composable () -> Unit, onOpen: () -> Unit,
    fromYouTube: Boolean = false, onEdit: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Surface(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp), color = cs.surfaceContainerLow) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClickLabel = "Open playlist") { haptics.tick(); onOpen() }
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cover()
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank() || fromYouTube) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (fromYouTube) Symbol(Glyph.CLOUD, size = 15.dp, color = cs.onSurfaceVariant,
                            modifier = Modifier.semantics { contentDescription = "On YouTube Music" })
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (onEdit == null) Symbol(Glyph.CHEVRON, size = 18.dp, color = cs.onSurfaceVariant)
            }
            if (onEdit != null) IconButton(onClick = onEdit, modifier = Modifier.semantics {
                contentDescription = "Edit $title on YouTube Music"
            }) { Symbol(Glyph.MORE) }
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
        OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name.trim()) }))
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text(if (initial.isBlank()) "Create" else "Save") } },
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
