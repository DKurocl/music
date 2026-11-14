@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.playback.PlayerState

/** Queue edits address stable occurrences, so repeated copies of the same song remain independent. */
@Composable
internal fun QueueSheet(state: PlayerState, onDismiss: () -> Unit, onPlay: (String) -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val index = state.queue.indexOfFirst { it.key == editingKey }
    if (index >= 0) {
        AlertDialog(onDismissRequest = { editingKey = null }, title = { Text(state.queue[index].title) }, text = {
            Column {
                TextButton(enabled = index > 0, onClick = { onMove(state.queue[index].key, -1); editingKey = null }) { Text("Move up") }
                TextButton(enabled = index < state.queue.lastIndex, onClick = { onMove(state.queue[index].key, 1); editingKey = null }) { Text("Move down") }
                TextButton(onClick = { onRemove(state.queue[index].key); editingKey = null }) { Text("Remove from queue") }
            }
        }, confirmButton = { TextButton(onClick = { editingKey = null }) { Text("Close") } })
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Play queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        if (state.shuffle) Text("Shuffle is on. This is the editable queue order.", Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue, key = { _, item -> item.key }) { position, item ->
                ListItem(
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (position == state.queueIndex) "Current · ${item.artist}" else item.artist) },
                    leadingContent = { Text("${position + 1}") },
                    trailingContent = { TextButton(onClick = { editingKey = item.key }) { Text("More") } },
                    modifier = Modifier.clickable { onPlay(item.key) },
                )
            }
        }
    }
}
