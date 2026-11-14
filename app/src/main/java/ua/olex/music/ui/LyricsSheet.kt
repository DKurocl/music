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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ua.olex.music.lyrics.Lyrics
import ua.olex.music.lyrics.Sync
import ua.olex.music.playback.PlayerState

/** Local embedded lyrics follow audible playback only while visible; manual scrolling suspends following. */
@Composable
internal fun LyricsSheet(lyrics: Lyrics, state: PlayerState, offsetMs: Long, position: () -> Long,
    onSeek: (Long) -> Unit, onOffset: (Long) -> Unit, onDismiss: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable(state.mediaId) { mutableStateOf(true) }
    var current by remember(state.mediaId, lyrics) { mutableIntStateOf(-1) }
    val timed = lyrics.sync != Sync.NONE
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(state.mediaId, state.isPlaying, state.positionMs, lyrics, offsetMs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                current = lyrics.indexAt(position() + offsetMs)
                if (state.isPlaying) delay(100)
            } while (state.isPlaying)
        }
    }
    LaunchedEffect(current, follow) { if (follow && current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lyrics", style = MaterialTheme.typography.headlineMedium)
                if (timed) FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
            }
            Text(if (lyrics.lines.isEmpty()) "No embedded lyrics in this file." else if (timed) "Tap a line to seek" else "Embedded lyrics · no timing",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (timed) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onOffset((offsetMs - 200).coerceAtLeast(-60_000)) }) { Text("Later") }
                    TextButton(onClick = { onOffset(0) }) { Text("Offset ${offsetMs} ms") }
                    TextButton(onClick = { onOffset((offsetMs + 200).coerceAtMost(60_000)) }) { Text("Earlier") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(lyrics.lines) { index, line ->
                    Text(line.text, style = if (line.background) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                        color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().then(if (timed) Modifier.clickable(onClickLabel = "Seek to lyric") {
                            onSeek((line.startMs - offsetMs).coerceAtLeast(0)); follow = true
                        } else Modifier).semantics { selected = index == current })
                }
            }
        }
    }
}
