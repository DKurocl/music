@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
 * Writes tags into the owner's files (FLAC, MP3, M4A, Ogg Vorbis and Opus). Android asks the owner once per batch
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
                FileOutputStream(pfd.fileDescriptor).channel.use { out -> out.write(ByteBuffer.wrap(plan.head), plan.start) }
            }
            return true
        }
        // New header size: build the whole new file aside, check it, then replace the original.
        val tmp = File.createTempFile("tags", ".tmp", context.cacheDir)
        try {
            FileOutputStream(tmp).channel.use { out ->
                resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { src ->
                        var pos = 0L
                        while (pos < plan.start) pos += src.transferTo(pos, plan.start - pos, out)
                        out.write(ByteBuffer.wrap(plan.head))
                        pos = plan.keepFrom
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
            "ARTIST" to ("Artists (a; b)" to common { it.artists.joinToString("; ") }),
            "ALBUM" to ("Album" to common { it.album }),
            "ALBUMARTIST" to ("Album artist" to common { it.albumArtist }),
            "DATE" to ("Year" to common { it.year?.toString() }),
            "GENRE" to ("Genres (a; b)" to common { it.genres.joinToString("; ") }),
            if (single) "TRACKNUMBER" to ("Track" to common { it.trackNumber?.toString() }) else null,
            "DISCNUMBER" to ("Disc" to common { it.discNumber?.toString() }),
            "COMPOSER" to ("Composers (a; b)" to common { it.metadata?.composers?.joinToString("; ") }),
        )
    }
    val values = remember(tracks) { mutableStateMapOf<String, String>().apply { fields.forEach { (k, v) -> put(k, v.second) } } }
    val touched = remember(tracks) { mutableStateMapOf<String, Boolean>() }
    val cs = MaterialTheme.colorScheme
    // Looking the song (or album) up online: candidates, then a before/after comparison to apply.
    val online = remember { OnlineTags() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var lookup by remember(tracks) { mutableStateOf<Lookup?>(null) }
    fun findOnline() {
        lookup = Lookup.Searching
        scope.launch {
            val found = runCatching { online.search(tracks) }.getOrNull()
            lookup = when { found == null -> Lookup.Failed("Couldn't reach the catalogue. Check the connection and try again.")
                found.isEmpty() -> Lookup.Failed("Nothing matched. Fix the artist or title a little, then search again.")
                else -> Lookup.Results(found) }
        }
    }
    // One field, filled and rounded (M3E), "Keep" when the chosen songs differ.
    @Composable
    fun field(key: String, modifier: Modifier = Modifier.fillMaxWidth()) {
        val (label, initial) = fields.firstOrNull { it.first == key }?.second ?: return
        val varied = !single && initial.isEmpty() && touched[key] != true
        androidx.compose.material3.TextField(
            value = values[key].orEmpty(), onValueChange = { values[key] = it; touched[key] = true },
            label = { Text(label, maxLines = 1) }, singleLine = true, placeholder = if (varied) ({ Text("Keep") }) else null,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = cs.surfaceContainerHighest, unfocusedContainerColor = cs.surfaceContainerHighest),
            modifier = modifier,
        )
    }
    @Composable
    fun group(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 12.dp, start = 4.dp))

    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp)) {
            // The songs being edited, so it is clear what changes.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (single) Artwork(tracks.first().artworkUri, Modifier.size(56.dp), 14.dp) else PlaylistCover(tracks, 56.dp, 14.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("Edit tags", style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(if (single) "${tracks.first().title} · ${tracks.first().artist}" else "${tracks.size} songs · fields that differ say Keep",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (lookup == null) androidx.compose.material3.FilledTonalIconButton(onClick = { findOnline() },
                    modifier = Modifier.semantics { contentDescription = if (single) "Find this song online" else "Find this album online" }) { Symbol(Glyph.SEARCH) }
            }
            lookup?.let { state ->
                OnlineLookup(state, tracks, values, onBack = { lookup = if (state is Lookup.Compare) Lookup.Results(state.all) else null },
                    onPick = { candidate, all ->
                        lookup = Lookup.Compare(candidate, null, all, loadingCover = candidate.coverUrl != null)
                        candidate.coverUrl?.let { url -> scope.launch {
                            val bytes = runCatching { online.cover(url) }.getOrNull()
                            (lookup as? Lookup.Compare)?.takeIf { it.candidate == candidate }?.let { lookup = it.copy(cover = bytes, loadingCover = false) }
                        } }
                    },
                    onApply = { chosen, newCover ->
                        chosen.forEach { (key, value) -> if (fields.any { it.first == key }) { values[key] = value; touched[key] = true } }
                        newCover?.let { cover = CoverArt(it, "image/jpeg"); coverError = null }
                        lookup = null
                    }, onRetry = { findOnline() })
                return@Column
            }
            // A hairline under the header once the form scrolls, so fields never butt against it.
            val formScroll = rememberScrollState()
            val scrolled by remember(formScroll) { androidx.compose.runtime.derivedStateOf { formScroll.value > 0 } }
            if (scrolled) androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
            Column(Modifier.weight(1f, fill = false).verticalScroll(formScroll).padding(horizontal = 24.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group("Song")
                field("TITLE")
                field("ARTIST")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field("DATE", Modifier.weight(1f))
                    field("TRACKNUMBER", Modifier.weight(1f))
                    field("DISCNUMBER", Modifier.weight(1f))
                }
                field("GENRE")
                field("COMPOSER")
                group("Album")
                field("ALBUM")
                field("ALBUMARTIST")
                group("Cover")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { pickCover.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Symbol(Glyph.PHOTO, size = 18.dp); Spacer(Modifier.width(8.dp)); Text(if (cover == null) "Choose cover" else "New cover · ${cover!!.bytes.size / 1024} KB")
                    }
                    if (cover != null) TextButton(onClick = { cover = null }) { Text("Keep old") }
                }
                coverError?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                group("Files")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Rename by tags", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); androidx.compose.material3.Switch(rename, { rename = it })
                }
                if (rename) OutlinedTextField(nameTemplate, { nameTemplate = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Move into folders", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); androidx.compose.material3.Switch(moveFiles, { moveFiles = it })
                }
                if (moveFiles) OutlinedTextField(folderTemplate, { folderTemplate = it }, label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (rename || moveFiles) Text("Use %artist%, %albumartist%, %album%, %title%, %track%, %disc%, %year%, %genre%.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text("Written into the files (FLAC, MP3, M4A, Ogg, Opus).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (!android.provider.MediaStore.canManageMedia(context)) TextButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
                        android.net.Uri.parse("package:" + context.packageName))) }
                }) { Text("Let Music change files without asking") }
            }
            // Save stays in reach at the bottom, however long the form.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    // Only fields the owner changed (or, for one song, every field) are written.
                    val changed = fields.map { it.first }.filter { single || touched[it] == true }.associateWith { values[it].orEmpty() }
                    onSave(TagEditRequest(changed, cover, nameTemplate.takeIf { rename }, folderTemplate.takeIf { moveFiles }))
                }, modifier = Modifier.height(48.dp)) { Text("Save") }
            }
        }
    }
}

/** Where an online lookup in the tag editor is. */
internal sealed interface Lookup {
    data object Searching : Lookup
    data class Failed(val message: String) : Lookup
    data class Results(val candidates: List<OnlineTags.Candidate>) : Lookup
    data class Compare(val candidate: OnlineTags.Candidate, val cover: ByteArray?, val all: List<OnlineTags.Candidate>, val loadingCover: Boolean) : Lookup
}

/**
 * The lookup in place of the form: matches ranked by how well they fit the file (the best marked),
 * then for the chosen one every field as "now -> found", each with its own tick (on where they
 * differ), and the covers side by side. Apply fills the form; Save writes the files as usual.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.OnlineLookup(state: Lookup, tracks: List<Track>, values: Map<String, String>, onBack: () -> Unit,
    onPick: (OnlineTags.Candidate, List<OnlineTags.Candidate>) -> Unit, onApply: (Map<String, String>, ByteArray?) -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val single = tracks.size == 1
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            Lookup.Searching -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.LoadingIndicator(Modifier.size(40.dp)); Text("Looking for matches…", modifier = Modifier.padding(start = 12.dp))
            }
            is Lookup.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onRetry) { Text("Search again") }; TextButton(onClick = onBack) { Text("Back to the form") } }
            }
            is Lookup.Results -> {
                Text(if (single) "Matches for this song" else "Matches for this album", style = MaterialTheme.typography.titleMedium)
                state.candidates.forEachIndexed { i, c ->
                    Surface(onClick = { onPick(c, state.candidates) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(if (i == 0) 24.dp else 16.dp),
                        color = if (i == 0) cs.secondaryContainer else cs.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            coil3.compose.AsyncImage(c.coverUrl?.replace("1200x1200bb", "200x200bb"), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(c.title ?: c.album.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(listOfNotNull(c.artist, c.album.takeIf { single }, c.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (i == 0) Text("Best match", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                            }
                            c.durationMs?.takeIf { single }?.let { Text(clock(it), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
                        }
                    }
                }
                TextButton(onClick = onBack) { Text("Back to the form") }
            }
            is Lookup.Compare -> {
                val found = state.candidate.fields(single)
                val labels = mapOf("TITLE" to "Title", "ARTIST" to "Artists", "ALBUM" to "Album", "ALBUMARTIST" to "Album artist", "DATE" to "Year",
                    "TRACKNUMBER" to "Track", "DISCNUMBER" to "Disc", "GENRE" to "Genres")
                val ticks = remember(state.candidate) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
                    found.forEach { (k, v) -> put(k, v != values[k].orEmpty()) } } }
                var takeCover by remember(state.candidate) { mutableStateOf(true) }
                Text("Now → found", style = MaterialTheme.typography.titleMedium)
                // Covers side by side, with their sizes, so the better picture is obvious.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Artwork(tracks.first().artworkUri, Modifier.size(112.dp), 16.dp, pixels = ua.olex.music.model.ArtworkPlan.MEDIUM)
                        Text("Now", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    Symbol(Glyph.CHEVRON, color = cs.onSurfaceVariant)
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Box(Modifier.size(112.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            if (state.loadingCover) androidx.compose.material3.LoadingIndicator(Modifier.size(36.dp))
                            else if (state.cover != null) coil3.compose.AsyncImage(state.cover, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Text("None", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(if (state.cover != null) "Found · 1200 px · ${state.cover.size / 1024} KB" else "Found", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Checkbox(takeCover && state.cover != null, { takeCover = it }, enabled = state.cover != null,
                        modifier = Modifier.semantics { contentDescription = "Use the found cover" })
                }
                found.forEach { (key, value) ->
                    val now = values[key].orEmpty()
                    val differs = value != now
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(labels[key] ?: key, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                if (differs) {
                                    Text(now.ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(value, style = MaterialTheme.typography.bodyLarge, color = cs.primary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                } else Text("$value · same", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            androidx.compose.material3.Checkbox(ticks[key] == true, { ticks[key] = it }, enabled = differs,
                                modifier = Modifier.semantics { contentDescription = "Use the found ${labels[key] ?: key}" })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onBack) { Text("Other matches") }
                    Button(onClick = { onApply(found.filterKeys { ticks[it] == true }, state.cover.takeIf { takeCover }) }) { Text("Apply") }
                }
                Text("Apply fills the form; Save writes it into the files, so it stays with the songs.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
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
