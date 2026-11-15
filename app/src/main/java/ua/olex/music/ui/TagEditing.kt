package ua.olex.music.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.olex.music.model.Track
import ua.olex.music.tags.ChannelSource
import ua.olex.music.tags.TagWriter
import ua.olex.music.tags.CoverArt
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Writes tags into the owner's files (FLAC and MP3 for now). Android asks the owner once per batch
 * (MediaStore write request, launched by the screen); then each file is changed in place when the
 * new tags fit in its old header, otherwise rewritten through a temporary copy so a failure can
 * never leave half a file. The audio bytes are copied, never decoded.
 */
object TagEditing {
    /** Edits for one file; names as in AudioTags. An empty list removes the tag. */
    data class Result(val written: Int, val unsupported: List<String>, val failed: List<String>, val renamed: Int = 0)

    suspend fun write(context: Context, tracks: List<Track>, cover: CoverArt? = null, nameTemplate: String? = null, folderTemplate: String? = null,
        edits: (Track) -> Map<String, List<String>>): Result = withContext(Dispatchers.IO) {
        var written = 0
        var renamed = 0
        val unsupported = ArrayList<String>()
        val failed = ArrayList<String>()
        for (track in tracks.distinctBy { it.uri }) {
            try {
                val e = edits(track)
                val ok = if (e.isEmpty() && cover == null) true else writeOne(context, Uri.parse(track.uri), e, cover)
                if (ok) { if (e.isNotEmpty() || cover != null) written++ } else unsupported += track.title
                if ((nameTemplate != null || folderTemplate != null) && move(context, track, e, nameTemplate, folderTemplate)) renamed++
            } catch (e: Exception) {
                failed += track.title
            }
        }
        Result(written, unsupported, failed, renamed)
    }

    /** Renames and/or moves the file by templates such as "%track% - %title%" and "Music/%albumartist%/%album%". */
    private fun move(context: Context, track: Track, edits: Map<String, List<String>>, nameTemplate: String?, folderTemplate: String?): Boolean {
        val values = android.content.ContentValues()
        val ext = track.path?.substringAfterLast('/')?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        nameTemplate?.let { t -> FileTemplate.fill(t, track, edits).takeIf { it.isNotBlank() }?.let { values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, if (ext != null) "$it.$ext" else it) } }
        folderTemplate?.let { t ->
            val folder = FileTemplate.fill(t, track, edits, folder = true).trim('/')
            if (folder.isNotBlank()) values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$folder/")
        }
        if (values.size() == 0) return false
        return context.contentResolver.update(Uri.parse(track.uri), values, null, null) > 0
    }

    private fun writeOne(context: Context, uri: Uri, edits: Map<String, List<String>>, cover: CoverArt?): Boolean {
        val resolver = context.contentResolver
        val plan = resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { TagWriter.plan(ChannelSource(it), edits, cover) }
        } ?: return false
        if (plan.inPlace) {
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { out -> out.write(ByteBuffer.wrap(plan.head), 0) }
            }
            return true
        }
        // New header size: build the whole new file aside, check it, then replace the original.
        val tmp = File.createTempFile("tags", ".tmp", context.cacheDir)
        try {
            FileOutputStream(tmp).channel.use { out ->
                out.write(ByteBuffer.wrap(plan.head))
                resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { src ->
                        var pos = plan.keepFrom
                        val size = src.size()
                        while (pos < size) pos += src.transferTo(pos, size - pos, out)
                    }
                }
            }
            val check = FileInputStream(tmp).channel.use { ua.olex.music.tags.TagReader.read(ChannelSource(it)) }
                ?: throw IllegalStateException("rewritten file does not read back")
            check.codec ?: throw IllegalStateException("rewritten file has no audio")
            resolver.openFileDescriptor(uri, "wt")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { out ->
                    FileInputStream(tmp).channel.use { src -> var pos = 0L; val size = src.size(); while (pos < size) pos += src.transferTo(pos, size - pos, out) }
                }
            }
        } finally { tmp.delete() }
        return true
    }
}

/**
 * The tag editor for one song or many: fields that differ between the chosen songs show "Keep" and
 * are left alone unless typed in. Title and track number are only offered for a single song.
 */
@Composable
internal fun TagEditorDialog(tracks: List<Track>, onDismiss: () -> Unit, onSave: (TagEditRequest) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cover by remember(tracks) { mutableStateOf<CoverArt?>(null) }
    var coverError by remember(tracks) { mutableStateOf<String?>(null) }
    val pickCover = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > (8 shl 20) || mime !in setOf("image/jpeg", "image/png")) coverError = "Choose a JPEG or PNG up to 8 MB."
            else { cover = CoverArt(bytes, mime); coverError = null }
        }
    }
    var rename by remember(tracks) { mutableStateOf(false) }
    var nameTemplate by remember(tracks) { mutableStateOf(if (tracks.size == 1) "%artist% - %title%" else "%track% - %title%") }
    var moveFiles by remember(tracks) { mutableStateOf(false) }
    var folderTemplate by remember(tracks) { mutableStateOf("Music/%albumartist%/%album%") }
    val single = tracks.size == 1
    fun common(get: (Track) -> String?): String = tracks.map { get(it).orEmpty() }.distinct().singleOrNull().orEmpty()
    val fields = remember(tracks) {
        listOfNotNull(
            if (single) "TITLE" to ("Title" to common { it.title }) else null,
            "ARTIST" to ("Artists (separate with ;)" to common { it.artists.joinToString("; ") }),
            "ALBUM" to ("Album" to common { it.album }),
            "ALBUMARTIST" to ("Album artist" to common { it.albumArtist }),
            "DATE" to ("Year" to common { it.year?.toString() }),
            "GENRE" to ("Genres (separate with ;)" to common { it.genres.joinToString("; ") }),
            if (single) "TRACKNUMBER" to ("Track number" to common { it.trackNumber?.toString() }) else null,
            "DISCNUMBER" to ("Disc number" to common { it.discNumber?.toString() }),
            "COMPOSER" to ("Composers (separate with ;)" to common { it.metadata?.composers?.joinToString("; ") }),
        )
    }
    val values = remember(tracks) { mutableStateMapOf<String, String>().apply { fields.forEach { (k, v) -> put(k, v.second) } } }
    val touched = remember(tracks) { mutableStateMapOf<String, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single) "Edit tags" else "Edit tags of ${tracks.size} songs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { (key, labelAndValue) ->
                    val varied = !single && labelAndValue.second.isEmpty() && touched[key] != true
                    OutlinedTextField(
                        value = values[key].orEmpty(), onValueChange = { values[key] = it; touched[key] = true },
                        label = { Text(labelAndValue.first) }, singleLine = true,
                        placeholder = if (varied) ({ Text("Keep") }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickCover.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (cover == null) "Choose cover" else "Cover chosen (${cover!!.bytes.size / 1024} KB)")
                    }
                    if (cover != null) TextButton(onClick = { cover = null }) { Text("Keep old") }
                }
                coverError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(rename, { rename = it }); Text("Rename files")
                }
                if (rename) OutlinedTextField(nameTemplate, { nameTemplate = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(moveFiles, { moveFiles = it }); Text("Move into folders")
                }
                if (moveFiles) OutlinedTextField(folderTemplate, { folderTemplate = it }, label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (rename || moveFiles) Text("Use %artist%, %albumartist%, %album%, %title%, %track%, %disc%, %year%, %genre%.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Written into the files (FLAC and MP3). Android asks once to allow changing them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!android.provider.MediaStore.canManageMedia(context)) TextButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
                        android.net.Uri.parse("package:" + context.packageName))) }
                }) { Text("Allow changing music files without asking") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only fields the owner changed (or, for one song, every field) are written.
                val changed = fields.map { it.first }.filter { single || touched[it] == true }.associateWith { values[it].orEmpty() }
                onSave(TagEditRequest(changed, cover, nameTemplate.takeIf { rename }, folderTemplate.takeIf { moveFiles }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Turns editor text into tag values: lists split on ';', empty text removes the tag. */
internal fun tagEdits(fields: Map<String, String>): Map<String, List<String>> = fields.mapValues { (key, text) ->
    val parts = if (key in setOf("ARTIST", "GENRE", "COMPOSER")) text.split(';') else listOf(text)
    parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/** Everything the tag editor asks to change in the chosen files. */
internal data class TagEditRequest(val fields: Map<String, String>, val cover: CoverArt? = null,
    val nameTemplate: String? = null, val folderTemplate: String? = null, val quiet: Boolean = false)

/** File and folder names from tags: "%track% - %title%" -> "03 - Song". Unsafe characters are replaced. */
internal object FileTemplate {
    fun fill(template: String, track: Track, edits: Map<String, List<String>> = emptyMap(), folder: Boolean = false): String {
        fun v(key: String, current: String?) = edits[key]?.joinToString(", ")?.takeIf { key in edits } ?: current.orEmpty()
        val values = mapOf(
            "artist" to v("ARTIST", track.artists.joinToString(", ")),
            "albumartist" to v("ALBUMARTIST", track.albumArtist ?: track.artist).ifBlank { track.artist },
            "album" to v("ALBUM", track.album).ifBlank { "Unknown album" },
            "title" to v("TITLE", track.title),
            "track" to v("TRACKNUMBER", track.trackNumber?.toString()).substringBefore('/').let { it.toIntOrNull()?.let { n -> "%02d".format(n) } ?: it },
            "disc" to v("DISCNUMBER", track.discNumber?.toString()).substringBefore('/'),
            "year" to v("DATE", track.year?.toString()).take(4),
            "genre" to v("GENRE", track.genres.firstOrNull()),
        )
        val filled = Regex("%([a-z]+)%").replace(template) { m -> safe(values[m.groupValues[1]] ?: m.value) }
        val parts = filled.split('/').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        return if (folder) parts.joinToString("/") else parts.joinToString(" ").trim()
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
}
