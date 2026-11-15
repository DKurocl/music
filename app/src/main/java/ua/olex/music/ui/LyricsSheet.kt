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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.withStyle
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
    onChooseFolder: (() -> Unit)? = null, onClearFolders: (() -> Unit)? = null, onReload: (() -> Unit)? = null,
    onFindOnline: (() -> Unit)? = null, onlineStatus: String? = null,
    onSaveTiming: ((Lyrics) -> Unit)? = null, onResetTiming: (() -> Unit)? = null) {
    // Timing editor: tap a line while it is sung to move it there; Save keeps it for this song.
    var editing by rememberSaveable(state.mediaId) { mutableStateOf(false) }
    var draft by remember(state.mediaId, lyrics) { mutableStateOf(lyrics) }
    val shown = if (editing) draft else lyrics
    val shownNow by rememberUpdatedState(shown)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable(state.mediaId) { mutableStateOf(true) }
    var sourcesOpen by rememberSaveable(state.mediaId) { mutableStateOf(lyrics.lines.isEmpty()) }
    var current by remember(state.mediaId, lyrics) { mutableIntStateOf(-1) }
    // The lyric clock: read every 50 ms for word-timed lyrics (the word fill), 100 ms for lines.
    var now by remember(state.mediaId, lyrics) { mutableLongStateOf(0L) }
    val timed = lyrics.sync != Sync.NONE
    val hasSources = onChooseFolder != null || onReload != null || note != null || folders.isNotEmpty()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(sourcesOpen) { if (sourcesOpen) follow = false }
    LaunchedEffect(state.mediaId, state.isPlaying, state.positionMs, lyrics, offsetMs, lifecycle) {
        if (!timed) { current = -1; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                now = position() + offsetMs
                current = shownNow.indexAt(now)
                if (state.isPlaying) delay(if (lyrics.sync == Sync.WORD) 50 else 100)
            } while (state.isPlaying)
        }
    }
    // One metadata item precedes the lyrics, so current puts the preceding lyric above the active one.
    LaunchedEffect(current, follow, sourcesOpen) {
        if (follow && !sourcesOpen && current >= 0) list.animateScrollToItem(current)
    }
    AppSheet(onDismiss, MaterialTheme.colorScheme.surfaceContainerLow) {
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
                                onFindOnline?.let { TextButton(onClick = it) { Text("Find online") } }
                                if (folders.isNotEmpty()) onClearFolders?.let { TextButton(onClick = it) { Text("Clear folder access") } }
                            }
                            if (folders.isNotEmpty()) Text(folders.take(3).joinToString(" · ") + if (folders.size > 3) " · +${folders.size - 3} folders" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (lyrics.lines.isEmpty()) "No local lyrics available." else if (timed) "Tap a line to seek" else "Plain lyrics · no timing",
                            style = MaterialTheme.typography.bodyMedium)
                        onlineStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        if (lyrics.lines.isEmpty() && onFindOnline != null) Button(onClick = onFindOnline) { Text("Find lyrics online") }
                        if (timed) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOffset((offsetMs - 200).coerceAtLeast(-60_000)) }) { Text("Later") }
                            TextButton(onClick = { onOffset(0) }) { Text("Offset ${offsetMs} ms") }
                            TextButton(onClick = { onOffset((offsetMs + 200).coerceAtMost(60_000)) }) { Text("Earlier") }
                            if (onSaveTiming != null) {
                                if (!editing) TextButton(onClick = { editing = true; follow = false; draft = lyrics }) { Text("Fix timing") }
                                else {
                                    Button(onClick = { onSaveTiming(draft); editing = false }) { Text("Save timing") }
                                    TextButton(onClick = { editing = false; draft = lyrics }) { Text("Cancel") }
                                }
                            }
                            onResetTiming?.let { TextButton(onClick = it) { Text("Restore original") } }
                        }
                        if (timed) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            var lock by remember { mutableStateOf(ua.olex.music.playback.LockScreenLyrics.get(context)) }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Line on the lock screen", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(lock, { lock = it; ua.olex.music.playback.LockScreenLyrics.set(context, it) })
                            }
                        }
                        if (editing) Text("Play the song and tap each line the moment it starts. Words move with their line.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                itemsIndexed(shown.lines) { index, line ->
                    LyricLine(line, index, current, now, timed) {
                        if (editing) draft = ua.olex.music.lyrics.LyricsTiming.retime(draft, index, position() + offsetMs)
                        else { onSeek((line.startMs - offsetMs).coerceAtLeast(0)); follow = true; sourcesOpen = false }
                    }
                }
            }
        }
    }
}

/**
 * One lyric line. The line being sung is large and in the accent; with word timing its words fill
 * in as they are sung (sung, then the current word blending in, then the rest dim). Lines already
 * sung and lines to come are quieter, more so the further they are. Backing vocals are smaller;
 * a second singer ("v2", "F") sits on the other side, as Apple Music shows duets.
 */
@Composable
internal fun LyricLine(line: ua.olex.music.lyrics.Line, index: Int, current: Int, now: Long, timed: Boolean, onSeek: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = index == current
    val distance = if (current < 0) 0 else kotlin.math.abs(index - current)
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        when { !timed || active -> 1f; distance == 1 -> 0.55f; else -> 0.35f }, label = "lyric-alpha")
    val dim = cs.onSurface.copy(alpha = 0.35f)
    val style = when {
        line.background -> MaterialTheme.typography.titleMedium
        active -> MaterialTheme.typography.headlineMediumEmphasized
        else -> MaterialTheme.typography.headlineSmallEmphasized
    }
    val text = if (active && line.words.isNotEmpty()) androidx.compose.ui.text.buildAnnotatedString {
        for (word in line.words) {
            val p = word.progress(now)
            val color = when { p >= 1f -> cs.primary; p <= 0f -> dim; else -> androidx.compose.ui.graphics.lerp(dim, cs.primary, 0.35f + 0.65f * p) }
            withStyle(androidx.compose.ui.text.SpanStyle(color = color)) { append(word.text) }
        }
    } else androidx.compose.ui.text.AnnotatedString(line.text)
    val secondVoice = line.agent != null && line.agent !in setOf("v1", "M")
    Column(
        Modifier.fillMaxWidth().graphicsLayerAlpha(alpha)
            .then(if (timed) Modifier.clickable(onClickLabel = "Seek to lyric", onClick = onSeek) else Modifier)
            .semantics { selected = active },
        horizontalAlignment = if (secondVoice) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start,
    ) {
        Text(text, style = style, color = if (active && line.words.isEmpty()) cs.primary else if (active) dim else cs.onSurface,
            textAlign = if (secondVoice) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
        line.translation?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant) }
    }
}

private fun Modifier.graphicsLayerAlpha(alpha: Float) = this.then(androidx.compose.ui.Modifier.graphicsLayer { this.alpha = alpha })
