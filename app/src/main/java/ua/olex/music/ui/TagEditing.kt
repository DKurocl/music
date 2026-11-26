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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.olex.music.model.Track
import ua.olex.music.tags.ChannelSource
import ua.olex.music.tags.TagWriter
import ua.olex.music.tags.CoverArt
import ua.olex.music.tags.FileCopy
import ua.olex.music.tags.TagRewriteStage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val TAG_COVER_MAX_BYTES = 8 * 1024 * 1024
private val TAG_COVER_TYPES = setOf("image/jpeg", "image/png")

internal fun readTagCover(input: InputStream, mime: String): CoverArt? {
    if (mime !in TAG_COVER_TYPES) return null
    val bytes = input.readNBytes(TAG_COVER_MAX_BYTES + 1)
    return bytes.takeIf { it.isNotEmpty() && it.size <= TAG_COVER_MAX_BYTES }?.let { CoverArt(it, mime) }
}

/**
 * Writes tags into the owner's files (FLAC, MP3, M4A, Ogg Vorbis and Opus). Android asks the owner once per batch
 * (MediaStore write request, launched by the screen); then each file is changed in place when the
 * new tags fit in its old header. A resized file is built and verified before the provider write.
 * The audio bytes are copied without decoding.
 */
object TagEditing {
    private val writer = Mutex()
    /** Edits for one file; names as in AudioTags. An empty list removes the tag. */
    data class Result(val written: Int, val unsupported: List<String>, val failed: List<String>, val renamed: Int = 0)

    suspend fun write(context: Context, tracks: List<Track>, cover: CoverArt? = null, nameTemplate: String? = null, folderTemplate: String? = null,
        edits: (Track) -> Map<String, List<String>>): Result = withContext(Dispatchers.IO) {
        var written = 0
        var renamed = 0
        val unsupported = ArrayList<String>()
        val failed = ArrayList<String>()
        for (track in tracks.distinctBy { it.uri }) {
            currentCoroutineContext().ensureActive()
            try {
                writer.withLock {
                    val e = edits(track)
                    val ok = if (e.isEmpty() && cover == null) true else writeOne(context, Uri.parse(track.uri), e, cover)
                    if (ok) { if (e.isNotEmpty() || cover != null) written++ } else unsupported += track.title
                    if ((nameTemplate != null || folderTemplate != null) && move(context, track, e, nameTemplate, folderTemplate)) renamed++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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

    private suspend fun writeOne(context: Context, uri: Uri, edits: Map<String, List<String>>, cover: CoverArt?): Boolean {
        val resolver = context.contentResolver
        val job = currentCoroutineContext()
        val directory = File(context.noBackupFilesDir, "tag-rewrites-v1")
        val previous = if (directory.isDirectory) rewriteStage(context, uri, directory) else null
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open media for tag editing")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor.dup()).use { output ->
                if (previous?.exists == true) previous.apply(input.channel, output.channel, job::ensureActive)
                val plan = TagWriter.plan(ChannelSource(input.channel), edits, cover) ?: return false
                job.ensureActive()
                if (plan.inPlace) {
                    FileCopy.writeFully(output.channel, ByteBuffer.wrap(plan.head), plan.start)
                    output.channel.force(true)
                } else {
                    val stage = previous ?: rewriteStage(context, uri, directory)
                        ?: throw IOException("Cannot identify the media row for recovery")
                    stage.prepare(input.channel, plan, job::ensureActive)
                    stage.apply(input.channel, output.channel, job::ensureActive)
                }
            }
        }
        return true
    }

    private fun rewriteStage(context: Context, uri: Uri, directory: File): TagRewriteStage? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.DATE_ADDED)
        val key = context.contentResolver.query(uri, columns, null, null, null)?.use { row ->
            if (!row.moveToFirst() || (0..2).any(row::isNull)) return@use null
            val volume = row.getString(0)
            val generation = row.getLong(1)
            val added = row.getLong(2)
            val version = MediaStore.getVersion(context, volume)
            if (version.isNullOrBlank() || generation < 0 || (generation == 0L && added <= 0)) return@use null
            val identity = "${uri.normalizeScheme()}\n$volume\n$version\n$generation\n$added"
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } ?: return null
        return TagRewriteStage(File(directory, "$key.pending"))
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
    var coverUri by remember(tracks) { mutableStateOf<Uri?>(null) }
    val pickCover = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) coverUri = uri
    }
    LaunchedEffect(coverUri, tracks) {
        val uri = coverUri ?: return@LaunchedEffect
        val candidate = runInterruptible(Dispatchers.IO) {
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                if (mime !in TAG_COVER_TYPES) null else context.contentResolver.openInputStream(uri)?.use { readTagCover(it, mime) }
            } catch (_: IOException) { null }
            catch (_: SecurityException) { null }
        }
        if (coverUri != uri) return@LaunchedEffect
        if (candidate == null) coverError = "Choose a JPEG or PNG up to 8 MB."
        else { cover = candidate; coverError = null }
        coverUri = null
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
                OnlineLookup(state, tracks, values, online, onBack = { lookup = if (state is Lookup.Compare) Lookup.Results(state.all) else null },
                    onPick = { candidate, all ->
                        lookup = Lookup.Compare(candidate, null, all, loadingCover = candidate.coverUrl != null)
                        candidate.coverUrl?.let { url -> scope.launch {
                            val bytes = runCatching { online.cover(url) }.getOrNull()
                            (lookup as? Lookup.Compare)?.takeIf { it.candidate == candidate }?.let { lookup = it.copy(cover = bytes, loadingCover = false) }
                        } }
                    },
                    onApply = { chosen, newCover ->
                        chosen.forEach { (key, value) -> if (fields.any { it.first == key }) { values[key] = value; touched[key] = true } }
                        newCover?.let { coverUri = null; cover = CoverArt(it, "image/jpeg"); coverError = null }
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
                    if (cover != null) TextButton(onClick = { coverUri = null; cover = null }) { Text("Keep old") }
                }
                coverError?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                group("Files")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Rename by tags", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); AppSwitch(rename, { rename = it })
                }
                if (rename) OutlinedTextField(nameTemplate, { nameTemplate = it }, label = { Text("File name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Move into folders", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); AppSwitch(moveFiles, { moveFiles = it })
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
                }, modifier = Modifier.height(48.dp), enabled = coverUri == null) { Text("Save") }
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
private fun androidx.compose.foundation.layout.ColumnScope.OnlineLookup(state: Lookup, tracks: List<Track>, values: Map<String, String>, online: OnlineTags, onBack: () -> Unit,
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
                            val thumb by androidx.compose.runtime.produceState<ByteArray?>(null, c.coverUrl) { value = c.coverUrl?.let { runCatching { online.thumb(it) }.getOrNull() } }
                            coil3.compose.AsyncImage(thumb, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
