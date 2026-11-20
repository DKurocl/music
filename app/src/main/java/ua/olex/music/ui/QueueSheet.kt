@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import ua.olex.music.model.TextFold
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.queueIndex.coerceAtLeast(0))
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtering = searchOpen && query.isNotBlank()
    val searchList = rememberLazyListState()
    val matches: List<Int>? = if (filtering) {
        val request = remember(state.queue, query) { Any() }
        key(request) {
            rememberOffMain<List<Int>?>(initial = null) {
                val words = TextFold.fold(query).split(' ').filter(String::isNotEmpty)
                state.queue.indices.filter { index ->
                    val item = state.queue[index]
                    val text = TextFold.fold(item.title + " " + item.artist)
                    words.all(text::contains)
                }
            }
        }
    } else null
    LaunchedEffect(query) { if (filtering) searchList.requestScrollToItem(0) }
    var jumpToCurrent by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun closeSearch() {
        keyboard?.hide()
        searchOpen = false
        query = ""
    }
    LaunchedEffect(jumpToCurrent) {
        if (jumpToCurrent) {
            if (state.queueIndex in state.queue.indices) list.scrollToItem(state.queueIndex)
            jumpToCurrent = false
        }
    }
    LaunchedEffect(state.playbackContext) {
        if (!searchOpen && PlaybackOrigin.decode(state.playbackContext)?.kind == "mix" && state.queueIndex in state.queue.indices) {
            list.requestScrollToItem(state.queueIndex)
        }
    }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searchOpen) {
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Find in queue") }, modifier = Modifier.weight(1f).focusRequester(focus),
                    leadingIcon = {
                        KickIconButton(onClick = ::closeSearch, modifier = Modifier.semantics { contentDescription = "Close queue search" }) {
                            Symbol(Glyph.BACK, kick = it)
                        }
                    },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Up next", style = MaterialTheme.typography.headlineSmallEmphasized)
                    val total = if (!state.shuffle && state.queueIndex in state.queue.indices) "${state.queue.lastIndex - state.queueIndex} upcoming"
                        else songCount(state.queue.size)
                    Text(listOfNotNull(mixFrom, total, if (state.shuffle) "shuffled" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (state.queue.size > 1) KickIconButton(onClick = { searchOpen = true }, enabled = draggingKey == null,
                    modifier = Modifier.semantics { contentDescription = "Find in queue" }) { Symbol(Glyph.SEARCH, kick = it) }
            }
            if (currentKey != null && state.queue.size > 1) KickIconButton(onClick = {
                closeSearch()
                jumpToCurrent = true
            }, enabled = draggingKey == null, modifier = Modifier.semantics { contentDescription = "Show current song" }) {
                Symbol(Glyph.FOCUS, kick = it)
            }
            if (!searchOpen && (onSave != null || onClearNext != null) && state.queue.isNotEmpty()) Box {
                var menu by remember { mutableStateOf(false) }
                KickIconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Queue actions" }) {
                    Symbol(Glyph.MORE, kick = it)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onSave != null) DropdownMenuItem(text = { Text("Save queue as a playlist") },
                        leadingIcon = { Symbol(Glyph.PLAYLIST_ADD) }, onClick = { menu = false; onSave() })
                    if (onClearNext != null) DropdownMenuItem(text = { Text("Clear songs after this one") },
                        enabled = state.queueIndex in 0 until state.queue.lastIndex, leadingIcon = { Symbol(Glyph.REMOVE) },
                        onClick = { menu = false; onClearNext() })
                }
            }
        }
        LazyColumn(state = if (filtering) searchList else list, modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (filtering && matches.isNullOrEmpty()) item {
                Text(if (matches == null) "Searching..." else "No songs match your search.",
                    modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(count = if (filtering) matches?.size ?: 0 else order.size,
                key = { index -> order[matches?.get(index) ?: index].key }) { index ->
                val position = matches?.get(index) ?: index
                val item = order[position]
                val current = item.key == currentKey
                val dragging = draggingKey == item.key
                ListItem(
                    headlineContent = {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) cs.primary else cs.onSurface,
                            style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text((if (filtering) "${position + 1} · " else "") + (item.sourceError ?: item.artist), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            // The same source mark as every song list: a cloud for a song that streams.
                            if (item.mediaId.startsWith("yt:")) Symbol(Glyph.CLOUD, size = 16.dp, color = cs.onSurfaceVariant,
                                modifier = Modifier.semantics { contentDescription = "Streams" })
                        }
                    },
                    leadingContent = {
                        Box(Modifier.size(48.dp).androidxClip()) {
                            Artwork(item.artworkUri, Modifier.fillMaxSize(), 12.dp)
                            NowPlayingOverlay(current, Modifier.matchParentSize())
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!current) KickIconButton(onClick = { onRemove(item.key) }, modifier = Modifier.semantics { contentDescription = "Remove ${item.title} from queue" }) { taps ->
                                Symbol(Glyph.CLOSE, size = 20.dp, color = cs.onSurfaceVariant, kick = taps)
                            }
                            if (!filtering) Box(
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
                    // One connected group (M3 Expressive): tight joins, round ends, the playing song
                    // tonal; a lifted song rounds fully and floats above the others while it moves.
                    colors = ListItemDefaults.colors(containerColor = when { dragging -> cs.surfaceContainerHighest
                        current -> cs.secondaryContainer; else -> cs.surfaceContainer }),
                    modifier = Modifier
                        .then(if (dragging) Modifier else Modifier.animateItem())
                        .zIndexWhen(dragging)
                        .graphicsLayer { if (dragging) { translationY = dragOffset; shadowElevation = 12f; shape = RoundedCornerShape(24.dp); clip = true } }
                        .padding(horizontal = 12.dp).padding(bottom = 2.dp)
                        .clip(if (dragging) RoundedCornerShape(24.dp) else segmentShape(index == 0, index == (if (filtering) (matches?.size ?: 0) else order.size) - 1))
                        .clickable { onPlay(item.key) }
                        .semantics {
                            contentDescription = "${item.title}, ${item.artist}, queue item ${position + 1}" + item.sourceError?.let { ", $it" }.orEmpty()
                            selected = current
                            customActions = listOfNotNull(
                                if (!filtering && position > 0) CustomAccessibilityAction("Move up") { onMove(item.key, -1); true } else null,
                                if (!filtering && position < order.lastIndex) CustomAccessibilityAction("Move down") { onMove(item.key, 1); true } else null,
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
