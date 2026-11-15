package ua.olex.music.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ua.olex.music.model.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Read/write only owner-selected documents; audio files and existing playlists are never modified. */
internal object PlaylistDocuments {
    data class Loaded(val document: M3uDocument, val name: String, val folder: String?)
    fun read(context: Context, uri: Uri): Loaded {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(M3uPlaylist.MAX_BYTES + 1) }
            ?: throw IllegalArgumentException("Could not open playlist")
        require(bytes.size <= M3uPlaylist.MAX_BYTES) { "Playlist is too large" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val document = M3uPlaylist.parse(text)
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        // Other document providers do not expose filesystem locations; ask in the import preview.
        val relative = id?.takeIf { uri.authority == "com.android.externalstorage.documents" && it.startsWith("primary:") }
            ?.removePrefix("primary:")
        val folder = relative?.substringBeforeLast('/', "")?.ifBlank { "." }
        return Loaded(document, document.name ?: displayName?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported playlist", folder)
    }
    fun write(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalArgumentException("Could not create playlist")
    }
    fun export(name: String, songs: List<SavedSong>): String =
        M3uPlaylist.encode(name, songs, Environment.getExternalStorageDirectory().absolutePath)
    fun filename(name: String): String = name.filterNot { it == '/' || it == '\\' || it.isISOControl() }
        .take(80).trim().ifBlank { "Playlist" } + ".m3u8"
}

/** A cancellable preview exposes missing, ambiguous, relocated, unsupported and repeated references. */
@Composable
internal fun PlaylistImportDialog(loaded: PlaylistDocuments.Loaded, library: LibraryIndex,
    onDismiss: () -> Unit, onImport: (String, List<SavedSong>) -> Unit) {
    var committing by remember(loaded) { mutableStateOf(false) }
    var name by remember(loaded) { mutableStateOf(loaded.name) }
    var folder by remember(loaded) { mutableStateOf(loaded.folder.orEmpty()) }
    var computed by remember(loaded) { mutableStateOf<Pair<String, M3uImport>?>(null) }
    var computedIndex by remember(loaded) { mutableStateOf<LibraryIndex?>(null) }
    LaunchedEffect(loaded, folder, library) {
        delay(150)
        val result = withContext(Dispatchers.Default) { M3uPlaylist.resolve(loaded.document, library, folder.takeIf { it.isNotBlank() }) }
        computed = folder to result; computedIndex = library
    }
    val preview = computed?.takeIf { it.first == folder && computedIndex === library }?.second
    AppDialog(onDismissRequest = onDismiss, title = { Text("Import playlist") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
            OutlinedTextField(folder, { folder = it }, label = { Text("Playlist folder") }, singleLine = true,
                supportingText = { Text("Relative to internal storage, for example Music/Playlists. Use . for the storage root.") },
                isError = !PlaylistPathIndex.validBaseFolder(folder) || preview?.count(M3uMatch.NEEDS_FOLDER)?.let { it > 0 } == true)
            if (preview == null) Text("Resolving paths...") else {
                Text("${preview.count(M3uMatch.EXACT)} found · ${preview.count(M3uMatch.RELOCATED)} relocated")
                Text("${preview.count(M3uMatch.MISSING)} missing · ${preview.count(M3uMatch.AMBIGUOUS)} ambiguous")
                Text("${preview.count(M3uMatch.UNSUPPORTED)} unsupported · ${preview.count(M3uMatch.REPEATED)} repeats skipped")
                if (preview.count(M3uMatch.NEEDS_FOLDER) > 0) Text("Enter the playlist folder to resolve paths containing ../.", color = MaterialTheme.colorScheme.error)
                if (preview.songs.isEmpty() && preview.count(M3uMatch.NEEDS_FOLDER) == 0) Text("No local file references to import.")
                Text("Missing and ambiguous files are kept. Repeated file entries are kept once. No music files are changed.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().height(200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(preview.entries) { row ->
                        Column {
                            Text("Line ${row.entry.line}: ${row.entry.path}", style = MaterialTheme.typography.bodySmall)
                            Text(when (row.match) {
                                M3uMatch.EXACT -> "Found"
                                M3uMatch.RELOCATED -> "Unique relocated match"
                                M3uMatch.MISSING -> "Missing, kept"
                                M3uMatch.AMBIGUOUS -> "Ambiguous, kept"
                                M3uMatch.UNSUPPORTED -> "Unsupported, skipped"
                                M3uMatch.NEEDS_FOLDER -> "Playlist folder required"
                                M3uMatch.REPEATED -> "Repeated file, skipped"
                            }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    items(loaded.document.warnings) { warning -> Text(warning, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !committing && name.isNotBlank() && PlaylistPathIndex.validBaseFolder(folder) && preview?.canImport == true, onClick = {
            val current = computed?.takeIf { it.first == folder && computedIndex === library }?.second
            if (!committing && name.isNotBlank() && PlaylistPathIndex.validBaseFolder(folder)) current?.takeIf { it.canImport }?.let {
                committing = true; onImport(name.trim(), it.songs)
            }
        }) { Text("Import") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
