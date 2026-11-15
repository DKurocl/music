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
    data class Result(val written: Int, val unsupported: List<String>, val failed: List<String>)

    suspend fun write(context: Context, tracks: List<Track>, edits: (Track) -> Map<String, List<String>>): Result = withContext(Dispatchers.IO) {
        var written = 0
        val unsupported = ArrayList<String>()
        val failed = ArrayList<String>()
        for (track in tracks.distinctBy { it.uri }) {
            try {
                if (writeOne(context, Uri.parse(track.uri), edits(track))) written++ else unsupported += track.title
            } catch (e: Exception) {
                failed += track.title
            }
        }
        Result(written, unsupported, failed)
    }

    private fun writeOne(context: Context, uri: Uri, edits: Map<String, List<String>>): Boolean {
        val resolver = context.contentResolver
        val plan = resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { TagWriter.plan(ChannelSource(it), edits) }
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
internal fun TagEditorDialog(tracks: List<Track>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
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
                Text("Written into the files (FLAC and MP3). Android asks once to allow changing them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only fields the owner changed (or, for one song, every field) are written.
                onSave(fields.map { it.first }.filter { single || touched[it] == true }.associateWith { values[it].orEmpty() })
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
