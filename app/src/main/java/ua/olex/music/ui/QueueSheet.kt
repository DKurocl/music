@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ua.olex.music.playback.PlayerState
import kotlin.math.roundToInt

/**
 * The play queue. Each row can be dragged by its handle to a new place (one move is sent when it
 * is dropped), removed with its button, or played with a tap; screen readers get "Move up", "Move
 * down" and "Remove" as actions. Edits address stable occurrences, so repeated copies of one song
 * stay independent.
 */
@Composable
internal fun QueueSheet(state: PlayerState, onDismiss: () -> Unit, onPlay: (String) -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.queueIndex - 1).coerceAtLeast(0))
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }

    AppSheet(onDismiss, cs.surfaceContainerLow, gestures = draggingKey == null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    listOfNotNull("${state.queue.size} songs", if (state.shuffle) "shuffled" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue, key = { _, item -> item.key }) { position, item ->
                val current = position == state.queueIndex
                val dragging = draggingKey == item.key
                ListItem(
                    headlineContent = {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) cs.primary else cs.onSurface,
                            style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (current) Symbol(Glyph.WAVE, size = 16.dp, color = cs.primary, bold = true)
                            Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingContent = { Artwork(item.artworkUri, Modifier.size(48.dp), 12.dp) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!current) IconButton(onClick = { onRemove(item.key) }, modifier = Modifier.semantics { contentDescription = "Remove ${item.title} from queue" }) {
                                Symbol(Glyph.CLOSE, size = 20.dp, color = cs.onSurfaceVariant)
                            }
                            Box(
                                Modifier.size(48.dp).pointerInput(item.key, state.queue.size) {
                                    detectVerticalDragGestures(
                                        onDragStart = { draggingKey = item.key; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        onVerticalDrag = { change, amount -> change.consume(); dragOffset += amount },
                                        onDragEnd = {
                                            val delta = (dragOffset / rowHeightPx).roundToInt().coerceIn(-position, state.queue.lastIndex - position)
                                            if (delta != 0) { onMove(item.key, delta); haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
                                            draggingKey = null; dragOffset = 0f
                                        },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                    )
                                },
                                contentAlignment = Alignment.Center,
                            ) { Symbol(Glyph.DRAG, color = if (dragging) cs.primary else cs.onSurfaceVariant) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (dragging) cs.surfaceContainerHighest else cs.surfaceContainerLow),
                    modifier = Modifier
                        .zIndexWhen(dragging)
                        .graphicsLayer { if (dragging) { translationY = dragOffset; shadowElevation = 12f } }
                        .clickable { onPlay(item.key) }
                        .semantics {
                            contentDescription = "${item.title}, ${item.artist}, queue item ${position + 1}"
                            selected = current
                            customActions = listOfNotNull(
                                if (position > 0) CustomAccessibilityAction("Move up") { onMove(item.key, -1); true } else null,
                                if (position < state.queue.lastIndex) CustomAccessibilityAction("Move down") { onMove(item.key, 1); true } else null,
                                CustomAccessibilityAction("Remove from queue") { onRemove(item.key); true },
                            )
                        },
                )
            }
        }
    }
}

private fun Modifier.zIndexWhen(on: Boolean) = if (on) this.zIndex(1f) else this
