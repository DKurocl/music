@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.gestures.scrollBy
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
import ua.olex.music.playback.PlayerState

/**
 * The play queue. Each row can be dragged by its handle: the other rows make way under the finger
 * (a tick at each step) and one move is sent when it is dropped; removed with its button, or played with a tap; screen readers get "Move up", "Move
 * down" and "Remove" as actions. Edits address stable occurrences, so repeated copies of one song
 * stay independent.
 */
@Composable
internal fun QueueSheet(state: PlayerState, onDismiss: () -> Unit, onPlay: (String) -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit, mixFrom: String? = null,
    /** Keeps the whole queue as a playlist (opens the playlist picker). */
    onSave: (() -> Unit)? = null,
    /** Removes every song after the one playing. */
    onClearNext: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.queueIndex - 1).coerceAtLeast(0))
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragDirection by remember { mutableIntStateOf(0) }
    var previewOrder by remember { mutableStateOf<List<ua.olex.music.playback.QueueItem>?>(null) }
    var originalKeys by remember { mutableStateOf<List<String>?>(null) }
    val keys = remember(state.queue) { state.queue.map { it.key } }
    val latestKeys by rememberUpdatedState(keys)
    val latestQueue by rememberUpdatedState(state.queue)
    val order = previewOrder ?: state.queue
    val currentKey = state.queue.getOrNull(state.queueIndex)?.key
    val move by rememberUpdatedState(onMove)

    fun resetDrag() {
        draggingKey = null
        dragOffset = 0f
        dragDirection = 0
        previewOrder = null
        originalKeys = null
    }

    fun moveAcrossVisibleRows(key: String) {
        val preview = previewOrder ?: return
        val items = list.layoutInfo.visibleItemsInfo
        val row = items.firstOrNull { it.key == key } ?: return
        val from = preview.indexOfFirst { it.key == key }
        // Wait for LazyColumn to lay out a previous move before evaluating the next midpoint.
        if (row.index != from) return
        val centre = row.offset + dragOffset + row.size / 2f
        val to = when {
            dragOffset > 0 -> items.lastOrNull { it.index > from && centre >= it.offset + it.size / 2f }
            dragOffset < 0 -> items.firstOrNull { it.index < from && centre <= it.offset + it.size / 2f }
            else -> null
        } ?: return
        if (preview.getOrNull(to.index)?.key != to.key) return
        previewOrder = preview.toMutableList().apply { add(to.index, removeAt(from)) }
        val targetTop = if (to.index > from) to.offset + to.size - row.size else to.offset
        dragOffset += row.offset - targetTop
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    LaunchedEffect(keys) {
        if (originalKeys != null && originalKeys != keys) resetDrag()
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(draggingKey, density) {
        val key = draggingKey ?: return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxSpeed = with(density) { 720.dp.toPx() }
        var lastFrame = withFrameNanos { it }
        while (draggingKey == key) {
            val frame = withFrameNanos { it }
            val time = ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.04f)
            lastFrame = frame
            moveAcrossVisibleRows(key)
            val layout = list.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            if (previewOrder?.getOrNull(row.index)?.key != key) continue
            val top = row.offset + dragOffset
            val bottom = top + row.size
            val ratio = when {
                dragDirection < 0 && top < layout.viewportStartOffset + edge -> (top - layout.viewportStartOffset - edge) / edge
                dragDirection > 0 && bottom > layout.viewportEndOffset - edge -> (bottom - layout.viewportEndOffset + edge) / edge
                else -> 0f
            }.coerceIn(-1f, 1f)
            if (ratio != 0f) {
                val consumed = list.scrollBy(maxSpeed * ratio * time)
                if (draggingKey != key) break
                // Compensate actual travel so the held row follows the finger while the list scrolls.
                dragOffset += consumed
            }
        }
    }

    AppSheet(onDismiss, cs.surfaceContainerLow, gestures = draggingKey == null) { CompositionLocalProvider(LocalIsPlaying provides state.isPlaying) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    listOfNotNull(mixFrom, "${state.queue.size} songs", if (state.shuffle) "shuffled" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
            // Two quiet actions where the queue's header is, as in YouTube Music: keep it, or clear what's next.
            if (onSave != null && state.queue.isNotEmpty()) KickIconButton(onClick = onSave, modifier = Modifier.semantics { contentDescription = "Save queue as a playlist" }) { k ->
                Symbol(Glyph.PLAYLIST_ADD, color = cs.onSurfaceVariant, kick = k)
            }
            if (onClearNext != null && state.queueIndex < state.queue.lastIndex) KickIconButton(onClick = onClearNext, modifier = Modifier.semantics { contentDescription = "Clear songs after this one" }) { k ->
                Symbol(Glyph.REMOVE, color = cs.onSurfaceVariant, kick = k)
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
                            Text(item.sourceError ?: item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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
                                            previewOrder = latestQueue
                                            originalKeys = latestKeys
                                            draggingKey = item.key
                                            dragOffset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onVerticalDrag = { change, amount ->
                                            change.consume()
                                            if (draggingKey == item.key) {
                                                dragOffset += amount
                                                if (amount != 0f) dragDirection = amount.compareTo(0f)
                                                moveAcrossVisibleRows(item.key)
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggingKey == item.key && originalKeys == latestKeys) {
                                                val from = latestKeys.indexOf(item.key)
                                                val to = previewOrder?.indexOfFirst { it.key == item.key } ?: -1
                                                if (from >= 0 && to >= 0 && to != from) {
                                                    move(item.key, to - from)
                                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                }
                                            }
                                            resetDrag()
                                        },
                                        onDragCancel = { resetDrag() },
                                    )
                                },
                                contentAlignment = Alignment.Center,
                            ) { Symbol(Glyph.DRAG, color = if (dragging) cs.primary else cs.onSurfaceVariant) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (dragging) cs.surfaceContainerHighest else cs.surfaceContainerLow),
                    modifier = Modifier
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .zIndexWhen(dragging)
                        .graphicsLayer { if (dragging) { translationY = dragOffset; shadowElevation = 12f } }
                        .clickable { onPlay(item.key) }
                        .semantics {
                            contentDescription = "${item.title}, ${item.artist}, queue item ${position + 1}" + item.sourceError?.let { ", $it" }.orEmpty()
                            selected = current
                            customActions = listOfNotNull(
                                if (position > 0) CustomAccessibilityAction("Move up") { onMove(item.key, -1); true } else null,
                                if (position < order.lastIndex) CustomAccessibilityAction("Move down") { onMove(item.key, 1); true } else null,
                                if (!current) CustomAccessibilityAction("Remove from queue") { onRemove(item.key); true } else null,
                            )
                        },
                )
            }
        }
    } }
}

private fun Modifier.androidxClip() = this.then(androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
private fun Modifier.zIndexWhen(on: Boolean) = if (on) this.zIndex(1f) else this
