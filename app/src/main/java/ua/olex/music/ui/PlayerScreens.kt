@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import ua.olex.music.model.ArtworkPlan
import ua.olex.music.model.Track
import ua.olex.music.playback.OutputRoute
import ua.olex.music.playback.PlayerState
import ua.olex.music.playback.RepeatMode

// ---- shared pieces --------------------------------------------------------------------------

/** A cover from the shared artwork cache, on a tonal placeholder with a note while it loads or when there is none. */
@Composable
internal fun Artwork(uri: String?, modifier: Modifier, corner: Dp, pixels: Int = ArtworkPlan.SMALL) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(corner)).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Symbol(Glyph.NOTE, size = 24.dp, color = cs.onSurfaceVariant, filled = true)
        if (uri != null) AsyncImage(
            model = ua.olex.music.library.ArtworkUris.variant(uri, LocalContext.current.packageName, pixels),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A song in any list: cover, title, artist and the file's quality, an overflow for actions. The
 * playing song is marked with a filled wave and the accent color, never only by color.
 */
@Composable
internal fun TrackRow(
    track: Track, current: Boolean, onMore: (() -> Unit)? = null,
    selecting: Boolean = false, checked: Boolean = false, onSelect: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    /** In an album: the track number instead of the cover, and the artist only when it differs. */
    number: Int? = null, albumArtist: String? = null, showQuality: Boolean = true, onClick: () -> Unit,
) {
    if ((onPlayNext == null && onQueue == null) || selecting) { TrackRowContent(track, current, onMore, selecting, checked, onSelect, number, albumArtist, showQuality, onClick); return }
    // Swipe right: play next. Swipe left: add to queue. The row springs back; a tick marks the threshold.
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(swipe.progress, swipe.dismissDirection) {
        val past = swipe.progress > 0.35f && swipe.dismissDirection != SwipeToDismissBoxValue.Settled
        if (past && !armed) haptics.threshold()
        armed = past
    }
    LaunchedEffect(swipe.currentValue) {
        when (swipe.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> { onPlayNext?.invoke(); haptics.confirm(); scope.launch { snackbar?.showSnackbar("Playing next: ${track.title}") } }
            SwipeToDismissBoxValue.EndToStart -> { onQueue?.invoke(); haptics.confirm(); scope.launch { snackbar?.showSnackbar("Added to queue: ${track.title}") } }
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        swipe.reset()
    }
    val cs = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = swipe, enableDismissFromStartToEnd = onPlayNext != null, enableDismissFromEndToStart = onQueue != null,
        backgroundContent = {
            val next = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (next) cs.secondaryContainer else cs.tertiaryContainer
            Row(Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                horizontalArrangement = if (next) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Symbol(if (next) Glyph.NEXT else Glyph.QUEUE, filled = true, color = if (next) cs.onSecondaryContainer else cs.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(if (next) "Play next" else "Add to queue", style = MaterialTheme.typography.labelLarge, color = if (next) cs.onSecondaryContainer else cs.onTertiaryContainer)
            }
        },
    ) { TrackRowContent(track, current, onMore, selecting, checked, onSelect, number, albumArtist, showQuality, onClick) }
}

@Composable
private fun TrackRowContent(
    track: Track, current: Boolean, onMore: (() -> Unit)?, selecting: Boolean, checked: Boolean, onSelect: (() -> Unit)?,
    number: Int?, albumArtist: String?, showQuality: Boolean, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val container by animateColorAsState(if (selecting && checked) cs.secondaryContainer else cs.surface, label = "row")
    ListItem(
        headlineContent = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) cs.primary else cs.onSurface,
                style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current) Symbol(Glyph.WAVE, size = 16.dp, color = cs.primary, bold = true)
                if (number == null || track.artist != albumArtist) Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                else Text(clock(track.durationMs), maxLines = 1)
                if (showQuality) track.format?.let { QualityTag(it.label, it.lossless) }
            }
        },
        leadingContent = {
            if (selecting) Checkbox(checked = checked, onCheckedChange = { onSelect?.invoke() },
                modifier = Modifier.semantics { contentDescription = "Select ${track.title}" })
            else if (number != null) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = if (current) cs.primary else cs.onSurfaceVariant)
            }
            else Artwork(track.artworkUri, Modifier.size(52.dp), 12.dp)
        },
        trailingContent = {
            if (onMore != null && !selecting) IconButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = "Actions for ${track.title}" }) {
                Symbol(Glyph.MORE, color = cs.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = container,
            headlineColor = if (selecting && checked) cs.onSecondaryContainer else cs.onSurface,
            supportingColor = if (selecting && checked) cs.onSecondaryContainer else cs.onSurfaceVariant),
        modifier = Modifier.combinedClickable(
            onLongClickLabel = if (onSelect != null) "Select song" else null, onLongClick = onSelect?.let { select -> { haptics.longPress(); select() } },
            onClick = { if (selecting) onSelect?.invoke() else onClick() },
        ).semantics { selected = if (selecting) checked else current },
    )
}

/** "FLAC 24/96" as a small tonal tag; lossless files get the accent so the eye finds them. */
@Composable
internal fun QualityTag(label: String, lossless: Boolean) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
        color = if (lossless) cs.onTertiaryContainer else cs.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(if (lossless) cs.tertiaryContainer else cs.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---- mini player ----------------------------------------------------------------------------

/**
 * The song playing, as a floating card above the navigation bar: cover, title, artist, play and
 * next, and a thin line of progress that is read only while music plays and the app is visible.
 */
@Composable
internal fun MiniPlayer(state: PlayerState, position: () -> Long, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val progress = rememberProgress(state, position, everyMs = 1_000)
    val haptics = rememberHaptics()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Follows the finger sideways (skip) and upward (open), then springs back.
    val dragX = remember { androidx.compose.animation.core.Animatable(0f) }
    var dragUp by remember { mutableFloatStateOf(0f) }
    var cardWidth by remember { mutableIntStateOf(1) }
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Surface(
        color = cs.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp, shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth()
            .onSizeChanged { cardWidth = it.width.coerceAtLeast(1) }
            .graphicsLayer { translationX = dragX.value; translationY = -dragUp.coerceAtLeast(0f) * 0.3f; alpha = 1f - (kotlin.math.abs(dragX.value) / cardWidth) * 0.6f }
            .pointerInput(state.hasNext) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val x = dragX.value
                        scope.launch {
                            when {
                                x < -cardWidth * 0.3f && state.hasNext -> { haptics.threshold(); onNext() }
                                x > cardWidth * 0.3f -> { haptics.threshold(); onPrevious() }
                            }
                            dragX.animateTo(0f, spring)
                        }
                    },
                    onDragCancel = { scope.launch { dragX.animateTo(0f, spring) } },
                ) { change, amount -> change.consume(); scope.launch { dragX.snapTo(dragX.value + amount) } }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dragUp > 60.dp.toPx()) { haptics.threshold(); onOpen() }; dragUp = 0f },
                    onDragCancel = { dragUp = 0f },
                ) { change, amount -> change.consume(); dragUp -= amount }
            }
            .clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        Box {
            Row(
                Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(state.artworkUri, Modifier.size(48.dp), 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(enabled = state.connected, onClick = { haptics.toggle(!state.playWhenReady); onToggle() }, modifier = Modifier.semantics { contentDescription = if (state.playWhenReady) "Pause" else "Play" }) {
                    Symbol(if (state.playWhenReady) Glyph.PAUSE else Glyph.PLAY, size = 28.dp, filled = true)
                }
                IconButton(enabled = state.hasNext, onClick = onNext, modifier = Modifier.semantics { contentDescription = "Next" }) {
                    Symbol(Glyph.NEXT, filled = true)
                }
            }
            // Progress along the bottom edge, inside the card's rounded corners.
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(cs.outlineVariant.copy(alpha = 0.4f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress()).background(cs.primary))
            }
        }
    }
}

/**
 * The playback position for a progress display, as a 0..1 fraction read every [everyMs] while the
 * song plays and the screen is started. A paused song is read once; nothing runs otherwise.
 */
@Composable
private fun rememberProgress(state: PlayerState, position: () -> Long, everyMs: Long): () -> Float {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var at by remember(state.mediaId) { mutableLongStateOf(position()) }
    LaunchedEffect(lifecycle, state.isPlaying, state.mediaId, state.positionMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { at = position(); if (state.isPlaying) delay(everyMs) } while (state.isPlaying)
        }
    }
    return { if (state.durationMs > 0) (at.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f }
}

// ---- player page ----------------------------------------------------------------------------

/** The player surface is independent of service/route bindings so its real layouts can be reviewed in tests. */
@Composable
internal fun PlayerPage(
    state: PlayerState, format: String?, route: OutputRoute?, favourite: Boolean, position: () -> Long,
    onClose: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onFavourite: (() -> Unit)?, onMore: (() -> Unit)?,
    onOutput: () -> Unit, onQueue: () -> Unit, onLyrics: () -> Unit, onAudioPath: () -> Unit, onTimer: () -> Unit,
    onOpenAlbum: (() -> Unit)? = null, onOpenArtist: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // The cover settles back a little while paused and comes forward when the music plays.
    val coverScale by animateFloatAsState(if (state.playWhenReady) 1f else 0.9f, motion.defaultSpatialSpec(), label = "cover")
    // Text colours follow the theme (a page drawn outside a Surface would otherwise inherit black).
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides cs.onSurface) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to cs.surfaceContainerHigh, 0.55f to cs.surface, 1f to cs.surface)),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close player" }) { Symbol(Glyph.COLLAPSE, size = 28.dp) }
                // Where the music comes from; a tap opens that album.
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).then(if (onOpenAlbum != null) Modifier.clickable(onClickLabel = "Open album", onClick = onOpenAlbum) else Modifier)
                    .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text(state.album ?: "Your library", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onQueue, modifier = Modifier.semantics { contentDescription = "Open queue" }) { Symbol(Glyph.QUEUE) }
            }
            Spacer(Modifier.height(16.dp))
            // Swipe the cover for the next or previous song; double tap for favourites; long press for actions.
            val haptics = rememberHaptics()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val swipe = remember { androidx.compose.animation.core.Animatable(0f) }
            var coverWidth by remember { mutableIntStateOf(1) }
            var heart by remember { androidx.compose.runtime.mutableStateOf(false) }
            val heartScale by animateFloatAsState(if (heart) 1f else 0f, motion.fastSpatialSpec(), label = "heart",
                finishedListener = { if (heart) heart = false })
            val spatial = motion.defaultSpatialSpec<Float>()
            Box(contentAlignment = Alignment.Center) {
            Artwork(
                state.artworkUri,
                Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f)
                    .onSizeChanged { coverWidth = it.width.coerceAtLeast(1) }
                    .pointerInput(onFavourite, onMore) {
                        detectTapGestures(
                            onDoubleTap = { if (onFavourite != null) { if (!favourite) heart = true; haptics.confirm(); onFavourite() } },
                            onLongPress = { if (onMore != null) { haptics.longPress(); onMore() } },
                        )
                    }
                    .pointerInput(state.hasNext) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val x = swipe.value
                                scope.launch {
                                    when {
                                        x < -coverWidth * 0.25f && state.hasNext -> { haptics.threshold(); swipe.animateTo(-coverWidth.toFloat(), spatial); onNext(); swipe.snapTo(coverWidth.toFloat()) }
                                        x > coverWidth * 0.25f -> { haptics.threshold(); swipe.animateTo(coverWidth.toFloat(), spatial); onPrevious(); swipe.snapTo(-coverWidth.toFloat()) }
                                    }
                                    swipe.animateTo(0f, spatial)
                                }
                            },
                            onDragCancel = { scope.launch { swipe.animateTo(0f, spatial) } },
                        ) { change, amount -> change.consume(); scope.launch { swipe.snapTo(swipe.value + amount) } }
                    }
                    .graphicsLayer {
                        scaleX = coverScale; scaleY = coverScale; translationX = swipe.value
                        rotationZ = swipe.value / coverWidth * 6f; alpha = 1f - (kotlin.math.abs(swipe.value) / coverWidth * 0.5f)
                    }
                    .shadow(24.dp, RoundedCornerShape(32.dp), clip = false, ambientColor = androidx.compose.ui.graphics.Color.Black, spotColor = androidx.compose.ui.graphics.Color.Black),
                32.dp, pixels = ArtworkPlan.LARGE,
            )
            if (heartScale > 0f) Symbol(Glyph.FAVOURITE, size = 120.dp, filled = true, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.graphicsLayer { scaleX = heartScale; scaleY = heartScale; alpha = heartScale })
            }
            Spacer(Modifier.height(32.dp))
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 1_500))
                    Text(state.artist, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (onOpenArtist != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Open artist", onClick = onOpenArtist) else Modifier)
                }
                IconButton(enabled = onFavourite != null, onClick = { onFavourite?.invoke() }, modifier = Modifier.semantics {
                    contentDescription = if (favourite) "Remove from favourites" else "Add to favourites"; selected = favourite
                }) { Symbol(Glyph.FAVOURITE, size = 28.dp, filled = favourite, color = if (favourite) cs.primary else cs.onSurfaceVariant) }
            }
            state.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) }
            Spacer(Modifier.height(20.dp))
            PlaybackProgress(state, position, onSeek, Modifier.widthIn(max = 420.dp))
            Spacer(Modifier.height(4.dp))
            SignalPath(format, route, onAudioPath)
            state.sleepDeadlineMs?.let { deadline -> SleepCountdown(deadline, onTimer) }
            Spacer(Modifier.height(20.dp))
            Transport(state, onToggle, onNext, onPrevious)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ToggleAction(Glyph.SHUFFLE, "Shuffle", state.shuffle, state.connected, if (state.shuffle) "On" else "Off", onShuffle)
                ToggleAction(
                    if (state.repeat == RepeatMode.ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, "Repeat", state.repeat != RepeatMode.OFF, state.connected,
                    when (state.repeat) { RepeatMode.OFF -> "Off"; RepeatMode.ALL -> "All"; RepeatMode.ONE -> "One" }, onRepeat,
                )
                ToggleAction(Glyph.LYRICS, "Lyrics", false, true, null, onLyrics)
                ToggleAction(Glyph.SLEEP, "Sleep timer", state.sleepDeadlineMs != null, state.connected, if (state.sleepDeadlineMs != null) "On" else "Off", onTimer)
                ToggleAction(route.glyph(), "Change output", false, true, null, onOutput)
                if (onMore != null) ToggleAction(Glyph.MORE, "Song actions", false, true, null, onMore)
            }
            state.sleepTimerError?.let { Text(it, color = cs.error, modifier = Modifier.padding(top = 8.dp)) }
            // What comes next, one tap from the queue.
            state.queue.getOrNull(state.queueIndex + 1)?.let { next ->
                Spacer(Modifier.height(20.dp))
                Surface(onClick = onQueue, shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(next.artworkUri, Modifier.size(40.dp), 10.dp)
                        Column(Modifier.weight(1f)) {
                            Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Text("${next.title} · ${next.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Symbol(Glyph.QUEUE, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

/**
 * Previous, play/pause, next. The play button is the largest thing on the page and changes shape
 * with the state, as in Google's players: round while paused, a wider rounded square while playing.
 */
@Composable
private fun Transport(state: PlayerState, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val playing = state.playWhenReady
    val width by animateDpAsState(if (playing) 128.dp else 96.dp, motion.fastSpatialSpec(), label = "width")
    val corner by animateDpAsState(if (playing) 28.dp else 48.dp, motion.fastSpatialSpec(), label = "corner")
    val haptics = rememberHaptics()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalIconButton(onClick = { haptics.tick(); onPrevious() }, modifier = Modifier.size(72.dp).semantics { contentDescription = "Previous" }) {
            Symbol(Glyph.PREVIOUS, size = 36.dp, filled = true)
        }
        Surface(
            onClick = { haptics.toggle(!playing); onToggle() }, enabled = state.connected, shape = RoundedCornerShape(corner), color = cs.primary, contentColor = cs.onPrimary,
            modifier = Modifier.width(width).height(96.dp).semantics { contentDescription = if (playing) "Pause" else "Play" },
        ) {
            Box(contentAlignment = Alignment.Center) { Symbol(if (playing) Glyph.PAUSE else Glyph.PLAY, size = 44.dp, filled = true) }
        }
        FilledTonalIconButton(enabled = state.hasNext, onClick = { haptics.tick(); onNext() }, modifier = Modifier.size(72.dp).semantics { contentDescription = "Next" }) {
            Symbol(Glyph.NEXT, size = 36.dp, filled = true)
        }
    }
}

/** A secondary control: tonal fill and a filled, bolder icon when on; the state is also spoken. */
@Composable
private fun ToggleAction(glyph: Glyph, label: String, on: Boolean, enabled: Boolean, state: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (on) cs.secondaryContainer else cs.surface.copy(alpha = 0f), label = "toggle")
    IconButton(
        onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = container),
        modifier = Modifier.size(48.dp).semantics { contentDescription = label; if (state != null) { stateDescription = state; selected = on } },
    ) { Symbol(glyph, filled = on, bold = on, color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant) }
}

/** What the file is and where it goes, as far as Android tells; a tap opens the full signal path. */
@Composable
private fun SignalPath(format: String?, route: OutputRoute?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).clickable(onClickLabel = "Audio path", onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(Glyph.WAVE, size = 16.dp, color = cs.primary)
        Text(
            listOfNotNull(format, route?.label()).joinToString("  ·  ").ifEmpty { "Audio path" },
            style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The seek bar, in the page's accent, with the time played and the length under it. */
@Composable
internal fun PlaybackProgress(state: PlayerState, position: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    // Keep the frequent position updates out of artwork, metadata and controls. Composition may
    // survive screen-off, so the poller also follows lifecycle rather than only visibility.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var at by remember(state.mediaId) { mutableLongStateOf(position()) }
    var dragged by remember(state.mediaId) { mutableStateOf<Float?>(null) }
    LaunchedEffect(lifecycle, state.isPlaying, state.mediaId, state.positionMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { at = position(); if (state.isPlaying) delay(250) } while (state.isPlaying)
        }
    }
    val duration = state.durationMs.coerceAtLeast(1)
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        WavySeekBar(
            progress = { (at.toFloat() / duration).coerceIn(0f, 1f) }, playing = state.isPlaying, enabled = state.durationMs > 0,
            description = "${clock(at)} of ${clock(state.durationMs)}",
            onDrag = { dragged = it },
            onSeek = { onSeek((it * duration).toLong()); at = (it * duration).toLong(); dragged = null },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(dragged?.let { (it * duration).toLong() } ?: at), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(clock(state.durationMs), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

private fun OutputRoute?.glyph(): Glyph = when (this?.kind) {
    OutputRoute.Kind.BLUETOOTH, OutputRoute.Kind.LE_AUDIO -> Glyph.HEADPHONES
    OutputRoute.Kind.WIRED, OutputRoute.Kind.USB -> Glyph.HEADPHONES
    else -> Glyph.SPEAKER
}

internal fun OutputRoute.label(): String = when (kind) {
    OutputRoute.Kind.SPEAKER -> "Phone speaker"
    OutputRoute.Kind.BLUETOOTH -> name.ifEmpty { "Bluetooth" }
    OutputRoute.Kind.LE_AUDIO -> name.ifEmpty { "LE Audio" }
    OutputRoute.Kind.USB -> name.ifEmpty { "USB audio" }
    OutputRoute.Kind.WIRED -> "Headphones"
    OutputRoute.Kind.REMOTE, OutputRoute.Kind.OTHER -> name.ifEmpty { "Other output" }
}

internal fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}

// ---- song actions ---------------------------------------------------------------------------

internal class TrackAction(val glyph: Glyph, val label: String, val filled: Boolean = false, val run: () -> Unit)

/** A song's actions as a bottom sheet: the song on top, then one row per action with its icon. */
@Composable
internal fun TrackActionsSheet(
    track: Track, favourite: Boolean, onDismiss: () -> Unit, actions: List<TrackAction>,
    rating: Int = 0, onRate: ((Int) -> Unit)? = null, plays: Int = 0,
    ratingsToFiles: Boolean = false, onRatingsToFiles: ((Boolean) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    AppSheet(onDismiss, cs.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            ListItem(
                headlineContent = { Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(listOfNotNull(track.artist, track.album).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Artwork(track.artworkUri, Modifier.size(56.dp), 14.dp) },
                trailingContent = { if (favourite) Symbol(Glyph.FAVOURITE, filled = true, color = cs.primary) },
                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerLow),
            )
            if (onRate != null) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Tapping the current rating again clears it.
                (1..5).forEach { star ->
                    IconButton(onClick = { onRate(if (star == rating) 0 else star) }, modifier = Modifier.semantics {
                        contentDescription = "Rate $star of 5"; selected = star <= rating
                    }) { Symbol(Glyph.STAR, size = 28.dp, filled = star <= rating, color = if (star <= rating) cs.primary else cs.onSurfaceVariant) }
                }
                Spacer(Modifier.weight(1f))
                Text(if (plays == 1) "1 play" else "$plays plays", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp))
            }
            if (onRate != null && onRatingsToFiles != null) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Save ratings in the music files", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(ratingsToFiles, onRatingsToFiles)
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = cs.outlineVariant)
            actions.forEach { action ->
                ListItem(
                    headlineContent = { Text(action.label) },
                    leadingContent = { Symbol(action.glyph, filled = action.filled, color = cs.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = cs.surfaceContainerLow),
                    modifier = Modifier.clickable { action.run(); onDismiss() },
                )
            }
        }
    }
}

/** "Stops in 23 min" under the signal path while a sleep timer runs; a tap changes it. Read once a minute. */
@Composable
private fun SleepCountdown(deadline: Long, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(30_000) } }
    val text = if (deadline == ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK_DEADLINE) "Stops after this song"
        else "Stops in ${((deadline - now).coerceAtLeast(0) + 59_999) / 60_000} min"
    Row(Modifier.clip(CircleShape).clickable(onClickLabel = "Sleep timer", onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol(Glyph.SLEEP, size = 16.dp, color = cs.tertiary, filled = true)
        Text(text, style = MaterialTheme.typography.labelLarge, color = cs.tertiary)
    }
}
