@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText

import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawWithContent
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
    onSaveTiming: ((Lyrics) -> Unit)? = null, onResetTiming: (() -> Unit)? = null,
    textScale: Float = 1f, onTextScale: (Float) -> Unit = {}, synced: Boolean = true, onSynced: (Boolean) -> Unit = {},
    romanized: Boolean = false, onRomanized: (Boolean) -> Unit = {},
    translated: Boolean = false, onTranslated: (Boolean) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Timing editor: tap a line while it is sung to move it there; Save keeps it for this song.
    var editing by rememberSaveable(state.mediaId) { mutableStateOf(false) }
    var draft by remember(state.mediaId, lyrics) { mutableStateOf(lyrics) }
    val shown = if (editing) draft else lyrics
    val shownNow by rememberUpdatedState(shown)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable(state.mediaId) { mutableStateOf(true) }
    var current by remember(state.mediaId, lyrics) { mutableIntStateOf(-1) }
    // The lyric clock: read every 50 ms for word-timed lyrics (the word fill), 100 ms for lines.
    var now by remember(state.mediaId, lyrics) { mutableLongStateOf(0L) }
    val timed = lyrics.sync != Sync.NONE
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(state.mediaId, state.isPlaying, state.positionMs, lyrics, offsetMs, lifecycle) {
        if (!timed) { current = -1; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                now = position() + offsetMs
                current = shownNow.indexAt(now)
                if (state.isPlaying) { if (lyrics.sync == Sync.WORD) androidx.compose.animation.core.withInfiniteAnimationFrameNanos { } else delay(100) }
            } while (state.isPlaying)
        }
    }
    // While fixing timing the list follows the song (the "editing" note comes first, so the line before stays visible).
    LaunchedEffect(current, editing) {
        if (editing && current >= 0) list.animateScrollToItem(current)
    }
    AppSheet(onDismiss, MaterialTheme.colorScheme.surfaceContainerLow) {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Lyrics", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(listOfNotNull(source, onlineStatus).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (editing) {
                    item(key = "editing") {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Play the song and tap each line the moment it starts. Words move with their line.", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onSaveTiming?.invoke(draft); editing = false }) { Text("Save timing") }
                                TextButton(onClick = { editing = false; draft = lyrics }) { Text("Cancel") }
                            }
                        }
                    }
                    itemsIndexed(shown.lines) { index, line ->
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            LyricLine(line, index, current, { now }, timed) { draft = ua.olex.music.lyrics.LyricsTiming.retime(draft, index, position() + offsetMs) }
                        }
                    }
                } else {
                    // One group of plain rows, as in Settings: no colours, each row says what it does.
                    if (lyrics.lines.isNotEmpty()) item(key = "appearance") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = synced, onClick = { onSynced(true) }, enabled = timed, label = { Text("Synced") })
                                FilterChip(selected = !synced || !timed, onClick = { onSynced(false) }, label = { Text("Static") })
                            }
                            Text("Text size · ${(textScale * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                            Slider(textScale, onTextScale, valueRange = 0.8f..1.4f, steps = 5)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = romanized, onClick = { onRomanized(!romanized) }, label = { Text("Romanize") })
                                if (lyrics.lines.any { !it.translation.isNullOrBlank() }) {
                                    FilterChip(selected = translated, onClick = { onTranslated(!translated) }, label = { Text("Show translation") })
                                }
                            }
                        }
                    }
                    val rows = buildList<@Composable (Boolean, Boolean) -> Unit> {
                        if (timed) add { first, last ->
                            ToolRow(Glyph.TIMER, "Timing", if (offsetMs == 0L) "In sync with the song" else "Moved ${kotlin.math.abs(offsetMs) / 100 / 10.0} s ${if (offsetMs > 0) "earlier" else "later"}", first, last, onClick = { onOffset(0) }) {
                                TextButton(onClick = { onOffset((offsetMs - 200).coerceAtLeast(-60_000)) }) { Text("Later") }
                                TextButton(onClick = { onOffset((offsetMs + 200).coerceAtMost(60_000)) }) { Text("Earlier") }
                            }
                        }
                        if (lyrics.lines.isNotEmpty()) add { first, last ->
                            ToolRow(Glyph.SHARE, "Share lyrics", null, first, last, onClick = {
                                val text = lyrics.lines.joinToString("\n") { it.text }
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, text)
                                context.startActivity(android.content.Intent.createChooser(intent, null))
                            })
                        }
                        if (timed && onSaveTiming != null) add { first, last -> ToolRow(Glyph.EDIT, "Fix timing", "Tap each line as it is sung", first, last, onClick = { editing = true; follow = false; draft = lyrics }) }
                        if (onResetTiming != null) add { first, last -> ToolRow(Glyph.REFRESH, "Restore original", "Undo your timing", first, last, onClick = onResetTiming) }
                        if (timed) add { first, last ->
                            val context = androidx.compose.ui.platform.LocalContext.current
                            var lock by remember { mutableStateOf(ua.olex.music.playback.LockScreenLyrics.get(context)) }
                            ToolRow(Glyph.LOCK, "Line on the lock screen", "While the app is closed", first, last, onClick = { lock = !lock; ua.olex.music.playback.LockScreenLyrics.set(context, lock) }) {
                                AppSwitch(lock, { lock = it; ua.olex.music.playback.LockScreenLyrics.set(context, it) })
                            }
                        }
                        onFindOnline?.let { find -> add { first, last -> ToolRow(Glyph.SEARCH, "Find online", "Replaces these lyrics and saves them in the file", first, last, onClick = find) } }
                        onChooseFolder?.let { choose -> add { first, last -> ToolRow(Glyph.FOLDER, "Choose music folder",
                            note ?: if (folders.isEmpty()) "To read .lrc files beside your songs" else folders.take(2).joinToString(" · "), first, last, onClick = choose) } }
                        onReload?.let { reload -> add { first, last -> ToolRow(Glyph.REFRESH, "Reload", "Read the lyrics files again", first, last, onClick = reload) } }
                        if (folders.isNotEmpty()) onClearFolders?.let { clear -> add { first, last -> ToolRow(Glyph.HIDDEN, "Clear folder access", null, first, last, onClick = clear) } }
                    }
                    itemsIndexed(rows) { i, row -> row(i == 0, i == rows.lastIndex) }
                }
            }
        }
    }
}

/** A row of the lyrics options group: the first and last round the group's corners. */
@Composable
private fun ToolRow(glyph: Glyph, title: String, subtitle: String?, first: Boolean, last: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val big = 24.dp; val small = 4.dp
    androidx.compose.material3.Surface(onClick = onClick, color = cs.surfaceContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(glyph, color = cs.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke()
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
internal fun LyricLine(line: ua.olex.music.lyrics.Line, index: Int, current: Int, now: () -> Long, timed: Boolean, textScale: Float = 1f, onSeek: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = index == current
    val distance = if (current < 0) 0 else kotlin.math.abs(index - current)
    val motion = MaterialTheme.motionScheme
    // As Apple Music shows lyrics: every line the same bold size; the sung one bright and full size,
    // the rest quieter and a little smaller, the far ones softly out of focus. All on springs.
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        when { !timed || active -> 1f; distance == 1 -> 0.42f; else -> 0.26f }, motion.defaultEffectsSpec(), label = "lyric-alpha")
    val scale by androidx.compose.animation.core.animateFloatAsState(if (!timed || active) 1f else 0.94f, motion.defaultSpatialSpec(), label = "lyric-scale")
    // Distant lines go soft, as in Apple Music; in Battery Saver they only dim (blur is GPU work per line).
    val blur by androidx.compose.animation.core.animateDpAsState(if (timed && distance >= 2 && !LocalPowerSave.current) 1.5.dp else 0.dp, motion.defaultEffectsSpec(), label = "lyric-blur")
    val base = if (line.background) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
    val style = base.copy(fontSize = base.fontSize * textScale, lineHeight = base.lineHeight * textScale, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = (-0.2).sp)
    val dim = cs.onSurface.copy(alpha = 0.32f)
    val text = if (active && line.words.isNotEmpty()) line.words.joinToString("") { it.text } else line.text
    var layout by remember(line, active) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val wordFill = if (!active || line.words.isEmpty()) Modifier else Modifier.drawWithCache {
        val measured = layout
        var offset = 0
        val paths = if (measured == null) emptyList() else line.words.map { word ->
            val start = offset
            offset += word.text.length
            word to measured.getPathForRange(start, offset)
        }
        onDrawWithContent {
            drawContent()
            if (measured != null) {
                val clock = now()
                for ((word, path) in paths) {
                    val progress = word.progress(clock)
                    if (progress > 0f) clipPath(path) {
                        drawText(measured, color = androidx.compose.ui.graphics.lerp(dim, cs.onSurface, 0.3f + 0.7f * progress))
                    }
                }
            }
        }
    }
    val secondVoice = line.agent != null && line.agent !in setOf("v1", "M")
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha; scaleX = scale; scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (secondVoice) 1f else 0f, 0.5f)
            }
            .then(if (blur > 0.dp) Modifier.blur(blur, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier)
            .then(if (timed) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable(onClickLabel = "Seek to lyric", onClick = onSeek) else Modifier)
            .semantics { selected = active },
        horizontalAlignment = if (secondVoice) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start,
    ) {
        Text(text, style = style, color = if (line.words.isNotEmpty() && active) dim else cs.onSurface,
            modifier = wordFill, onTextLayout = { layout = it },
            textAlign = if (secondVoice) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
    }
}

/** Scrolling the lyrics past their end stays with the lyrics: it must not open the queue or close the player. */
private val KeepScroll = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) = available
    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = available
}

/**
 * Lyrics in place of the cover on the player page: the sung line follows in the middle, word by
 * word where the lyrics are word-timed; tap a line to jump there; scroll freely and a "Back to the
 * song" chip appears to resume following. A small button opens the full lyrics tools (sources,
 * online search, timing). With no lyrics, one button looks them up.
 */
@Composable
internal fun InlineLyrics(lyrics: Lyrics, state: PlayerState, offsetMs: Long, position: () -> Long, onSeek: (Long) -> Unit,
    onTools: () -> Unit, onFindOnline: (() -> Unit)?, onlineStatus: String?, modifier: Modifier = Modifier, textScale: Float = 1f, synced: Boolean = true, romanized: Boolean = false, translated: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(state.mediaId) { mutableStateOf(true) }
    var current by remember(state.mediaId, lyrics) { mutableIntStateOf(-1) }
    var now by remember(state.mediaId, lyrics) { mutableLongStateOf(0L) }
    val timed = synced && lyrics.sync != Sync.NONE
    val romanizedLines = rememberOffMain(lyrics, romanized, initial = null as List<ua.olex.music.lyrics.Line>?) {
        if (!romanized || lyrics.lines.isEmpty()) return@rememberOffMain null
        val transliterator = android.icu.text.Transliterator.getInstance("Any-Latin")
        lyrics.lines.map { line ->
            line.copy(text = transliterator.transliterate(line.text),
                words = line.words.map { word -> word.copy(text = transliterator.transliterate(word.text)) })
        }
    }
    val lines = if (romanized) romanizedLines ?: lyrics.lines else lyrics.lines
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(state.mediaId, state.isPlaying, state.positionMs, lyrics, offsetMs, lifecycle, timed) {
        if (!timed) { current = -1; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                now = position() + offsetMs
                current = lyrics.indexAt(now)
                if (state.isPlaying) { if (lyrics.sync == Sync.WORD) androidx.compose.animation.core.withInfiniteAnimationFrameNanos { } else delay(100) }
            } while (state.isPlaying)
        }
    }
    // Keep the sung line about a third from the top, with the previous line still visible above.
    LaunchedEffect(current, follow) { if (follow && current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    // No card: the lyrics take the cover's place and fade out at the top and bottom edges.
    Box(modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color.Transparent, 0.12f to androidx.compose.ui.graphics.Color.Black,
                0.82f to androidx.compose.ui.graphics.Color.Black, 1f to androidx.compose.ui.graphics.Color.Transparent),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }) {
        if (lyrics.lines.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No lyrics for this song yet", style = MaterialTheme.typography.titleLarge)
                onlineStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onFindOnline != null) Button(onClick = onFindOnline) { Text("Find online") }
                    androidx.compose.material3.OutlinedButton(onClick = onTools) { Text("More") }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().nestedScroll(KeepScroll), state = list, contentPadding = PaddingValues(top = 48.dp, bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            itemsIndexed(lines) { index, line ->
                Column {
                    LyricLine(line, index, current, { now }, timed, textScale) {
                        if (timed) { onSeek((line.startMs - offsetMs).coerceAtLeast(0)); follow = true }
                    }
                    if (translated) line.translation?.let { translation ->
                        Text(translation, style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
        if (!follow && timed) androidx.compose.material3.FilledTonalButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            Symbol(Glyph.DOWN, size = 18.dp); androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp)); Text("Back to the song")
        }
    }
}
