package ua.olex.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RemotePlaylistSheet(card: YouTubeMusic.Card, source: YouTubeMusic, account: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember(card.id, account) { mutableStateOf<YouTubeMusic.PlaylistSnapshot?>(null) }
    var busy by remember(card.id, account) { mutableStateOf(false) }
    var error by remember(card.id, account) { mutableStateOf<String?>(null) }
    var entryToRemove by remember(card.id, account) { mutableStateOf<YouTubeMusic.PlaylistOccurrence?>(null) }
    var input by remember(card.id, account) { mutableStateOf("") }

    fun perform(change: YouTubeMusic.PlaylistChange? = null) {
        if (busy) return
        val expected = page?.entries
        if (change != null && expected == null) return
        busy = true
        error = null
        scope.launch {
            try {
                page = if (change == null) source.remotePlaylist(card.id, account)
                    else source.editRemotePlaylist(card.id, account, requireNotNull(expected), change)
                if (change?.kind == YouTubeMusic.PlaylistChange.Kind.ADD) input = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Anything at all (a malformed or unexpectedly deep answer included) becomes a message
                // here, never a crash of the app.
                if (change != null) page = null
                error = failure.message?.takeIf { it.length < 200 } ?: "YouTube Music couldn't update this playlist."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(card.id, account) { perform() }

    val video = remember(input) {
        val entered = input.trim()
        val uri = runCatching { android.net.Uri.parse(entered) }.getOrNull()
        val id = when {
            uri?.scheme == "https" && uri.host in setOf("music.youtube.com", "www.youtube.com", "youtube.com") -> uri.getQueryParameter("v")
            uri?.scheme == "https" && uri.host == "youtu.be" -> uri.lastPathSegment
            else -> entered
        }
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, style = MaterialTheme.typography.headlineSmall)
            Text("Edit on YouTube Music", style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val entries = page?.entries
            if (entries != null) {
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("YouTube link or video ID") }, singleLine = true)
                TextButton(enabled = video != null && !busy, onClick = {
                    perform(YouTubeMusic.PlaylistChange(YouTubeMusic.PlaylistChange.Kind.ADD, videoId = video))
                }) { Text("Add song") }
            }
            TextButton(enabled = !busy, onClick = { perform() }) { Text("Refresh") }
            if (entries != null) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                // The position is part of the key: a playlist answer can repeat an entry id (or leave several
                // without one), and a repeated key closes the app.
                itemsIndexed(entries, key = { index, entry -> "$index:${entry.entryId ?: entry.videoId}" }) { index, entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.entryId != null) {
                            val previous = entries.getOrNull(index - 1)?.entryId
                            val next = entries.getOrNull(index + 1)
                            val afterNext = entries.getOrNull(index + 2)
                            TextButton(enabled = !busy && previous != null, onClick = {
                                perform(YouTubeMusic.PlaylistChange(YouTubeMusic.PlaylistChange.Kind.MOVE, entry, previous))
                            }) { Text("Up") }
                            TextButton(enabled = !busy && next?.entryId != null && (afterNext == null || afterNext.entryId != null), onClick = {
                                perform(YouTubeMusic.PlaylistChange(YouTubeMusic.PlaylistChange.Kind.MOVE, entry, afterNext?.entryId))
                            }) { Text("Down") }
                            TextButton(enabled = !busy, onClick = { entryToRemove = entry }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
    entryToRemove?.let { entry ->
        AppDialog(
            onDismissRequest = { entryToRemove = null },
            title = { Text("Remove this occurrence?") },
            text = { Text(entry.title) },
            confirmButton = {
                TextButton(onClick = {
                    entryToRemove = null
                    perform(YouTubeMusic.PlaylistChange(YouTubeMusic.PlaylistChange.Kind.REMOVE, entry))
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { entryToRemove = null }) { Text("Cancel") } },
        )
    }
}
