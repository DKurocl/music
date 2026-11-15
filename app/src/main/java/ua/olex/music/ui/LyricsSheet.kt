@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Lyrics", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = list,
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "lyric-source-controls") {
                    val cs = MaterialTheme.colorScheme
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Timing: nudge in 0.2 s steps, reset by tapping the value; fix lines one by one.
                        if (timed) androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp, 24.dp, 4.dp, 4.dp), color = cs.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Timing", style = MaterialTheme.typography.titleSmall, color = cs.primary)
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    androidx.compose.material3.FilledTonalButton(onClick = { onOffset((offsetMs - 200).coerceAtLeast(-60_000)) }) { Text("Later") }
                                    TextButton(onClick = { onOffset(0) }, modifier = Modifier.weight(1f)) { Text(if (offsetMs == 0L) "In sync" else "Offset ${offsetMs} ms") }
                                    androidx.compose.material3.FilledTonalButton(onClick = { onOffset((offsetMs + 200).coerceAtMost(60_000)) }) { Text("Earlier") }
                                }
                                if (onSaveTiming != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!editing) androidx.compose.material3.OutlinedButton(onClick = { editing = true; follow = false; draft = lyrics }) { Text("Fix timing") }
                                    else {
                                        Button(onClick = { onSaveTiming(draft); editing = false }) { Text("Save timing") }
                                        TextButton(onClick = { editing = false; draft = lyrics }) { Text("Cancel") }
                                    }
                                    onResetTiming?.let { TextButton(onClick = it) { Text("Restore original") } }
                                }
                                if (editing) Text("Play the song and tap each line the moment it starts. Words move with their line.",
                                    style = MaterialTheme.typography.bodySmall, color = cs.primary)
                            }
                        }
                        if (timed) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            var lock by remember { mutableStateOf(ua.olex.music.playback.LockScreenLyrics.get(context)) }
                            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = cs.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Line on the lock screen", style = MaterialTheme.typography.bodyLarge)
                                        Text("While the app is closed", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                    Switch(lock, { lock = it; ua.olex.music.playback.LockScreenLyrics.set(context, it) })
                                }
                            }
                        }
                        // Where the lyrics come from, and how to get others.
                        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(if (timed) 4.dp else 24.dp, if (timed) 4.dp else 24.dp, 24.dp, 24.dp), color = cs.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Sources", style = MaterialTheme.typography.titleSmall, color = cs.primary)
                                Text(if (lyrics.lines.isEmpty()) "No lyrics found on the phone." else if (timed) "Synced lines: tap one to jump there." else "Plain lyrics, without timing.",
                                    style = MaterialTheme.typography.bodyMedium)
                                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                                onlineStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    onFindOnline?.let { androidx.compose.material3.AssistChip(onClick = it, label = { Text("Find online") }, leadingIcon = { Symbol(Glyph.SEARCH, size = 18.dp) }) }
                                    onChooseFolder?.let { androidx.compose.material3.AssistChip(onClick = it, label = { Text("Choose music folder") }, leadingIcon = { Symbol(Glyph.FOLDER, size = 18.dp) }) }
                                    onReload?.let { androidx.compose.material3.AssistChip(onClick = it, label = { Text("Reload") }, leadingIcon = { Symbol(Glyph.REFRESH, size = 18.dp) }) }
                                    if (folders.isNotEmpty()) onClearFolders?.let { androidx.compose.material3.AssistChip(onClick = it, label = { Text("Clear folder access") }) }
                                }
                                if (folders.isNotEmpty()) Text(folders.take(3).joinToString(" · ") + if (folders.size > 3) " · +${folders.size - 3} folders" else "",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
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

/**
 * Lyrics in place of the cover on the player page: the sung line follows in the middle, word by
 * word where the lyrics are word-timed; tap a line to jump there; scroll freely and a "Back to the
 * song" chip appears to resume following. A small button opens the full lyrics tools (sources,
 * online search, timing). With no lyrics, one button looks them up.
 */
@Composable
internal fun InlineLyrics(lyrics: Lyrics, state: PlayerState, offsetMs: Long, position: () -> Long, onSeek: (Long) -> Unit,
    onTools: () -> Unit, onFindOnline: (() -> Unit)?, onlineStatus: String?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(state.mediaId) { mutableStateOf(true) }
    var current by remember(state.mediaId, lyrics) { mutableIntStateOf(-1) }
    var now by remember(state.mediaId, lyrics) { mutableLongStateOf(0L) }
    val timed = lyrics.sync != Sync.NONE
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(state.mediaId, state.isPlaying, state.positionMs, lyrics, offsetMs, lifecycle) {
        if (!timed) { current = -1; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                now = position() + offsetMs
                current = lyrics.indexAt(now)
                if (state.isPlaying) delay(if (lyrics.sync == Sync.WORD) 50 else 100)
            } while (state.isPlaying)
        }
    }
    // Keep the sung line about a third from the top, with the previous line still visible above.
    LaunchedEffect(current, follow) { if (follow && current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp), color = cs.surfaceContainer, modifier = modifier) {
        Box {
            if (lyrics.lines.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(Glyph.LYRICS, size = 40.dp, color = cs.onSurfaceVariant)
                    Text("No lyrics for this song yet", style = MaterialTheme.typography.titleMedium)
                    onlineStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
                    if (onFindOnline != null) Button(onClick = onFindOnline) { Text("Find online") }
                }
            } else LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(lyrics.lines) { index, line ->
                    LyricLine(line, index, current, now, timed) { onSeek((line.startMs - offsetMs).coerceAtLeast(0)); follow = true }
                }
            }
            // Tools, top right: sources, online search, timing.
            androidx.compose.material3.FilledTonalIconButton(onClick = onTools, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(40.dp)
                .semantics { contentDescription = "Lyrics tools" }) { Symbol(Glyph.TUNE, size = 20.dp) }
            if (!follow && timed) androidx.compose.material3.AssistChip(onClick = { follow = true }, label = { Text("Back to the song") },
                leadingIcon = { Symbol(Glyph.DOWN, size = 18.dp) }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}
