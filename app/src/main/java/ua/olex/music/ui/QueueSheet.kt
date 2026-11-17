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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import ua.olex.music.playback.PlayerState
import kotlin.math.roundToInt

/**
 * The play queue. Each row can be dragged by its handle: the other rows make way under the finger
 * (a tick at each step) and one move is sent when it is dropped; removed with its button, or played with a tap; screen readers get "Move up", "Move
 * down" and "Remove" as actions. Edits address stable occurrences, so repeated copies of one song
 * stay independent.
 */
@Composable
internal fun QueueSheet(state: PlayerState, onDismiss: () -> Unit, onPlay: (String) -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit, mixFrom: String? = null) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.queueIndex - 1).coerceAtLeast(0))
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(1f) }
    // The order on screen: changed live while dragging, sent to the player once on drop.
    var order by remember(state.queue) { mutableStateOf(state.queue) }
    var startIndex by remember { mutableIntStateOf(-1) }
    val currentKey = state.queue.getOrNull(state.queueIndex)?.key
    val latestOrder by rememberUpdatedState(order)

    AppSheet(onDismiss, cs.surfaceContainerLow, gestures = draggingKey == null) { CompositionLocalProvider(LocalIsPlaying provides state.isPlaying) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    listOfNotNull(mixFrom, "${state.queue.size} songs", if (state.shuffle) "shuffled" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(order, key = { _, item -> item.key }) { position, item ->
                val current = item.key == currentKey
                val dragging = draggingKey == item.key
                ListItem(
                    headlineContent = {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) cs.primary else cs.onSurface,
                            style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            // The same source mark as every song list: a cloud for a song that streams.
                            if (item.mediaId.startsWith("yt:")) Symbol(Glyph.CLOUD, size = 16.dp, color = cs.onSurfaceVariant,
                                modifier = Modifier.semantics { contentDescription = "Streams" })
                        }
                    },
                    leadingContent = {
                        Box(Modifier.size(48.dp).androidxClip()) {
                            Artwork(item.artworkUri, Modifier.fillMaxSize(), 12.dp)
                            if (current) NowPlayingOverlay(Modifier.matchParentSize())
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!current) IconButton(onClick = { onRemove(item.key) }, modifier = Modifier.semantics { contentDescription = "Remove ${item.title} from queue" }) {
                                Symbol(Glyph.CLOSE, size = 20.dp, color = cs.onSurfaceVariant)
                            }
                            Box(
                                Modifier.size(48.dp).pointerInput(item.key) {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            draggingKey = item.key; dragOffset = 0f
                                            startIndex = latestOrder.indexOfFirst { it.key == item.key }
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onVerticalDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount
                                            val now = latestOrder.indexOfFirst { it.key == item.key }
                                            // Past half a row, the neighbour swaps places with the dragged row.
                                            val step = when { dragOffset > rowHeightPx / 2 && now < latestOrder.lastIndex -> 1; dragOffset < -rowHeightPx / 2 && now > 0 -> -1; else -> 0 }
                                            if (step != 0) {
                                                order = latestOrder.toMutableList().apply { add(now + step, removeAt(now)) }
                                                dragOffset -= step * rowHeightPx
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                        },
                                        onDragEnd = {
                                            val end = latestOrder.indexOfFirst { it.key == item.key }
                                            if (startIndex >= 0 && end != startIndex) { onMove(item.key, end - startIndex); haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
                                            draggingKey = null; dragOffset = 0f; startIndex = -1
                                        },
                                        onDragCancel = { order = state.queue; draggingKey = null; dragOffset = 0f; startIndex = -1 },
                                    )
                                },
                                contentAlignment = Alignment.Center,
                            ) { Symbol(Glyph.DRAG, color = if (dragging) cs.primary else cs.onSurfaceVariant) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (dragging) cs.surfaceContainerHighest else cs.surfaceContainerLow),
                    modifier = Modifier
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
                        .zIndexWhen(dragging)
                        .graphicsLayer { if (dragging) { translationY = dragOffset; shadowElevation = 12f } }
                        .clickable { onPlay(item.key) }
                        .semantics {
                            contentDescription = "${item.title}, ${item.artist}, queue item ${position + 1}"
                            selected = current
                            customActions = listOfNotNull(
                                if (position > 0) CustomAccessibilityAction("Move up") { onMove(item.key, -1); true } else null,
                                if (position < order.lastIndex) CustomAccessibilityAction("Move down") { onMove(item.key, 1); true } else null,
                                CustomAccessibilityAction("Remove from queue") { onRemove(item.key); true },
                            )
                        },
                )
            }
        }
    } }
}

private fun Modifier.androidxClip() = this.then(androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
private fun Modifier.zIndexWhen(on: Boolean) = if (on) this.zIndex(1f) else this
