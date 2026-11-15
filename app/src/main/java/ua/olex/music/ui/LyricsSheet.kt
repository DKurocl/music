@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ua.olex.music.lyrics.Lyrics
import ua.olex.music.lyrics.Sync
import ua.olex.music.playback.PlayerState

/** Local lyrics follow only while visible. Source controls share the scrollable body at large fonts. */
@Composable
internal fun LyricsSheet(lyrics: Lyrics, state: PlayerState, offsetMs: Long, position: () -> Long,
    onSeek: (Long) -> Unit, onOffset: (Long) -> Unit, onDismiss: () -> Unit,
    source: String = "Embedded lyrics", note: String? = null, folders: List<String> = emptyList(),
    onChooseFolder: (() -> Unit)? = null, onClearFolders: (() -> Unit)? = null, onReload: (() -> Unit)? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable(state.mediaId) { mutableStateOf(true) }
    var sourcesOpen by rememberSaveable(state.mediaId) { mutableStateOf(lyrics.lines.isEmpty()) }
    var current by remember(state.mediaId, lyrics) { mutableIntStateOf(-1) }
    val timed = lyrics.sync != Sync.NONE
    val hasSources = onChooseFolder != null || onReload != null || note != null || folders.isNotEmpty()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(sourcesOpen) { if (sourcesOpen) follow = false }
    LaunchedEffect(state.mediaId, state.isPlaying, state.positionMs, lyrics, offsetMs, lifecycle) {
        if (!timed) { current = -1; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                current = lyrics.indexAt(position() + offsetMs)
                if (state.isPlaying) delay(100)
            } while (state.isPlaying)
        }
    }
    // One metadata item precedes the lyrics, so current puts the preceding lyric above the active one.
    LaunchedEffect(current, follow, sourcesOpen) {
        if (follow && !sourcesOpen && current >= 0) list.animateScrollToItem(current)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Lyrics", style = MaterialTheme.typography.headlineMedium)
                if (timed) FilterChip(selected = follow, onClick = { follow = !follow; if (follow) sourcesOpen = false }, label = { Text("Follow") })
                if (hasSources) TextButton(onClick = { sourcesOpen = !sourcesOpen }) { Text(if (note == null) "Sources" else "Sources · info") }
            }
            Text(source, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = list,
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "lyric-source-controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sourcesOpen && hasSources) {
                            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                onChooseFolder?.let { TextButton(onClick = it) { Text("Choose music folder") } }
                                onReload?.let { TextButton(onClick = it) { Text("Reload") } }
                                if (folders.isNotEmpty()) onClearFolders?.let { TextButton(onClick = it) { Text("Clear folder access") } }
                            }
                            if (folders.isNotEmpty()) Text(folders.take(3).joinToString(" · ") + if (folders.size > 3) " · +${folders.size - 3} folders" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (lyrics.lines.isEmpty()) "No local lyrics available." else if (timed) "Tap a line to seek" else "Plain lyrics · no timing",
                            style = MaterialTheme.typography.bodyMedium)
                        if (timed) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOffset((offsetMs - 200).coerceAtLeast(-60_000)) }) { Text("Later") }
                            TextButton(onClick = { onOffset(0) }) { Text("Offset ${offsetMs} ms") }
                            TextButton(onClick = { onOffset((offsetMs + 200).coerceAtMost(60_000)) }) { Text("Earlier") }
                        }
                    }
                }
                itemsIndexed(lyrics.lines) { index, line ->
                    Text(line.text, style = if (line.background) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                        color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().then(if (timed) Modifier.clickable(onClickLabel = "Seek to lyric") {
                            onSeek((line.startMs - offsetMs).coerceAtLeast(0)); follow = true; sourcesOpen = false
                        } else Modifier).semantics { selected = index == current })
                }
            }
        }
    }
}
