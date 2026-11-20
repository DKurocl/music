@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package ua.olex.music.ui

import coil3.toBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onLongClick
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

/** A cover from the shared cache, with a tonal fallback when it has no image. */
@Composable
internal fun Artwork(uri: String?, modifier: Modifier, corner: Dp, pixels: Int = ArtworkPlan.SMALL) {
    ArtworkFrame(modifier, RoundedCornerShape(corner)) {
        Symbol(Glyph.NOTE, size = 24.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, filled = true)
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
    track: Track, current: Boolean,
    /** For a row in a lazy list: Modifier.animateItem(), so rows glide when the list changes instead of jumping. */
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    selecting: Boolean = false, checked: Boolean = false, onSelect: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    /** In an album: the track number instead of the cover, and the artist only when it differs. */
    number: Int? = null, albumArtist: String? = null, showQuality: Boolean = true, onClick: () -> Unit,
) {
    val marked = current || track.matchesPlayback(LocalPlayingTrack.current)
    if ((onPlayNext == null && onQueue == null) || selecting) { Box(modifier) { TrackRowContent(track, marked, onMore, selecting, checked, onSelect, number, albumArtist, showQuality, onClick) }; return }
    // Swipe right: play next. Swipe left: add to queue. The row follows the finger with growing
    // resistance (it never leaves the screen), the action shows underneath, a tick marks the point
    // of no return, and on release the row springs back into place.
    val haptics = rememberHaptics()
    val snackbar = LocalSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var raw by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var armed by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val back = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.coerceAtLeast(1) }) {
        val x = offset.value
        if (x != 0f) {
            val next = x > 0
            val color by animateColorAsState(when { armed && next -> cs.primary; armed -> cs.tertiary; next -> cs.secondaryContainer; else -> cs.tertiaryContainer }, label = "swipe")
            val ink = when { armed && next -> cs.onPrimary; armed -> cs.onTertiary; next -> cs.onSecondaryContainer; else -> cs.onTertiaryContainer }
            val iconScale by androidx.compose.animation.core.animateFloatAsState(if (armed) 1.1f else 0.9f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "swipe-icon")
            Row(Modifier.matchParentSize().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp)).background(color).padding(horizontal = 24.dp),
                horizontalArrangement = if (next) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // The icon sits in a solid circle of the label's ink, so it reads at a glance even
                // before the row has moved far; it grows when the release would act.
                Box(Modifier.size(36.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }.clip(CircleShape).background(ink),
                    contentAlignment = Alignment.Center) {
                    Symbol(if (next) Glyph.PLAY_NEXT else Glyph.ADD_TO_QUEUE, filled = true, color = color, size = 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Text(if (next) "Play next" else "Add to queue",
                    style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1)
            }
        }
        Box(Modifier.graphicsLayer { translationX = offset.value }
            .pointerInput(onPlayNext, onQueue) {
                detectHorizontalDragGestures(
                    onDragStart = { raw = offset.value },
                    onDragEnd = {
                        val dx = offset.value
                        val fire = armed
                        armed = false
                        scope.launch { offset.animateTo(0f, back) }
                        if (fire) {
                            haptics.confirm()
                            if (dx > 0) { onPlayNext?.invoke(); scope.launch { snackbar?.showSnackbar("Playing next: ${track.title}") } }
                            else { onQueue?.invoke(); scope.launch { snackbar?.showSnackbar("Added to queue: ${track.title}") } }
                        }
                    },
                    onDragCancel = { armed = false; scope.launch { offset.animateTo(0f, back) } },
                ) { change, amount ->
                    change.consume()
                    raw += amount
                    if (raw > 0 && onPlayNext == null || raw < 0 && onQueue == null) raw = 0f
                    val shown = rubber(raw, width * 0.6f)
                    val past = kotlin.math.abs(shown) > width * 0.3f
                    if (past != armed) { if (past) haptics.threshold(); armed = past }
                    scope.launch { offset.snapTo(shown) }
                }
            }) {
            TrackRowContent(track, marked, onMore, selecting, checked, onSelect, number, albumArtist, showQuality, onClick, lifted = offset.value != 0f)
        }
    }
}

@Composable
private fun TrackRowContent(
    track: Track, current: Boolean, onMore: (() -> Unit)?, selecting: Boolean, checked: Boolean, onSelect: (() -> Unit)?,
    number: Int?, albumArtist: String?, showQuality: Boolean, onClick: () -> Unit, lifted: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    // Confirmed current files and complete stream cache copies are separate offline sources.
    // Keep the original online request for every action; only the source mark uses the file.
    val online = YouTubeMusic.isOnline(track)
    val network = LocalNetwork.current
    val cacheRevision = if (online) ua.olex.music.playback.CacheBudget.revision.collectAsStateWithLifecycle().value else 0L
    val kept = if (online) remember(track.uri, current, network, cacheRevision) { ua.olex.music.playback.CacheBudget.isStreamCached(track.uri) } else false
    val owned = if (online) LocalOwnedSources.current[track.uri] else null
    val unavailable = online && !network && !kept && owned == null
    // Rows sit as soft rounded segments; the playing one is a tonal pill, the selected ones stronger.
    val container by animateColorAsState(when {
        selecting && checked -> cs.secondaryContainer
        // While swiped the row is solid, so the action underneath never shows through it.
        lifted -> cs.surfaceContainerHigh
        current && !selecting -> cs.secondaryContainer.copy(alpha = 0.45f)
        else -> cs.surface.copy(alpha = 0f)
    }, label = "row")
    ListItem(
        headlineContent = {
            TrackLabel(track.title, color = if (current) cs.primary else cs.onSurface,
                style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            // One line's height whatever the mark (a quality tag, a cloud, a ring): every row is the same height.
            val line = with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
            // Fixed, not a minimum: a baseline-aligned tag sits lower than the text and used to stretch the row.
            Row(Modifier.height(line), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The tag shares the text's baseline: ListItem reads two different baselines as a
                // second line and grows the row, which is what made rows with a tag taller.
                if (number == null || track.artist != albumArtist) TrackLabel(track.artist, MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                else Text(clock(track.durationMs), maxLines = 1, modifier = Modifier.alignByBaseline())
                // On the text's baseline: two different baselines make ListItem a three-line (taller) row.
                if (showQuality) SourceMark(owned ?: track, Modifier.alignByBaseline(), kept = kept && owned == null, unavailable = unavailable)
            }
        },
        leadingContent = {
            // Choosing keeps the row's own picture (as Google Photos and Files do): a ring sits on
            // it, and a tap fills the ring with a tick that pops in; numbered rows show the ring alone.
            if (selecting) Box(Modifier.size(if (number != null) 40.dp else 52.dp)
                .toggleable(checked, onValueChange = { onSelect?.invoke() }, role = androidx.compose.ui.semantics.Role.Checkbox)
                .semantics { contentDescription = "Select ${track.title}" }, contentAlignment = Alignment.Center) {
                if (number == null) Artwork(track.artworkUri, Modifier.fillMaxSize().graphicsLayer {
                    val s = if (checked) 0.86f else 1f; scaleX = s; scaleY = s }, 12.dp)
                SelectMark(checked, onArtwork = number == null)
            }
            else if (number != null) Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                val numberAlpha by animateFloatAsState(
                    if (current && LocalIsPlaying.current) 0f else 1f,
                    androidx.compose.animation.core.tween(if (LocalPowerSave.current) 0 else 120),
                    label = "Track number",
                )
                Text(number.toString(), modifier = Modifier.graphicsLayer { alpha = numberAlpha }, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                NowPlayingOverlay(current, Modifier.matchParentSize(), onArtwork = false)
            }
            else Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))) {
                Artwork(track.artworkUri, Modifier.fillMaxSize(), 12.dp)
                NowPlayingOverlay(current, Modifier.matchParentSize())
            }
        },
        trailingContent = {
            if (onMore != null && !selecting) IconButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = "Actions for ${track.title}" }) {
                Symbol(Glyph.MORE, color = cs.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (lifted) cs.surfaceContainerHigh else container,
            headlineColor = if (selecting && checked) cs.onSecondaryContainer else cs.onSurface,
            supportingColor = if (selecting && checked) cs.onSecondaryContainer else cs.onSurfaceVariant),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
            .graphicsLayer { alpha = if (unavailable) 0.45f else 1f }.combinedClickable(
            onLongClickLabel = if (onSelect != null) "Select song" else null, onLongClick = onSelect?.let { select -> { haptics.longPress(); select() } },
            onClick = { if (selecting) onSelect?.invoke() else onClick() },
        ).semantics { selected = if (selecting) checked else current },
    )
}

/**
 * Where a song plays from, always in the same place after the artist (docs/specs/M10_ONLINE.md, D2):
 * a file shows its quality tag; an online song a small cloud, a filled-in cloud once the stream
 * cache keeps it whole (plays without the network), and a filling ring while Soulseek downloads it.
 * Every mark is the tag's height, so rows never change size when a mark changes.
 */
@Composable
internal fun SourceMark(track: Track, modifier: Modifier = Modifier, kept: Boolean = false, unavailable: Boolean = false) {
    if (!YouTubeMusic.isOnline(track)) { track.format?.let { QualityTag(it.label, it.lossless, modifier) }; return }
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as ua.olex.music.MusicApp).graph
    val cs = MaterialTheme.colorScheme
    val key = remember(track.artist, track.title) { ua.olex.music.soulseek.SoulseekDownloads.songKey(track.artist.substringBefore(",").trim(), track.title) }
    // Only this song's download: other downloads' progress doesn't redraw this row.
    val job by remember(key) { graph.soulseek.jobs.map { all -> all.lastOrNull { it.key == key }?.takeIf { it.running } } }.collectAsStateWithLifecycle(null)
    val mark = when {
        job != null -> "Downloading a local copy"
        unavailable -> "Requires internet"
        kept -> "Cached audio available offline"
        else -> "Streams from YouTube Music"
    }
    Box(modifier.size(18.dp).semantics { contentDescription = mark }, contentAlignment = Alignment.Center) {
        when {
            job != null -> {
                val p = job?.progress
                if (p == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary, trackColor = cs.primary.copy(alpha = 0.2f))
                else CircularProgressIndicator(progress = { p }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary, trackColor = cs.primary.copy(alpha = 0.2f))
            }
            unavailable -> Symbol(Glyph.CLOUD_OFF, size = 16.dp, color = cs.onSurfaceVariant)
            kept -> Symbol(Glyph.CLOUD_DONE, size = 16.dp, color = cs.onSurfaceVariant)
            else -> Symbol(Glyph.CLOUD, size = 16.dp, color = cs.onSurfaceVariant)
        }
    }
}

/** Whether the phone is online now (AppGraph.network); rows of online songs read it. True in previews. */
internal val LocalNetwork = androidx.compose.runtime.compositionLocalOf { true }

/** "FLAC 24/96" as a small tonal tag; lossless files get the accent so the eye finds them. */
@Composable
internal fun QualityTag(label: String, lossless: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, maxLines = 1,
        // Trimmed to the letters, so the tag is no taller than the line it sits in.
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.fontSize * 1.2f,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)),
        color = if (lossless) cs.onTertiaryContainer else cs.onSurfaceVariant,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
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
internal fun MiniPlayer(state: PlayerState, position: () -> Long, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit = {}, covered: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val progress = rememberProgress(state, position, everyMs = 1_000, active = !covered)
    val haptics = rememberHaptics()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Follows the finger sideways (skip) and upward (open), then springs back.
    var dragX by remember { mutableFloatStateOf(0f) }
    var settleX by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val travel = LocalPlayerTravel.current
    val still = LocalPowerSave.current
    val open by rememberUpdatedState(onOpen)
    var dragUp by remember { mutableFloatStateOf(0f) }
    var cardWidth by remember { mutableIntStateOf(1) }
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    fun releaseDrag() {
        settleX?.cancel()
        settleX = scope.launch {
            androidx.compose.animation.core.animate(dragX, 0f, animationSpec = spring) { value, _ -> dragX = value }
        }
    }
    Surface(
        color = cs.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp, shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth()
            .onSizeChanged { cardWidth = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = rubber(dragX, 48.dp.toPx())
                // Up opens the player; down has nowhere to go, so it stretches a little and springs back.
                alpha = 1f - (travel?.fraction ?: 0f)
                translationY = if (dragUp < 0f) -rubber(dragUp, 16.dp.toPx()) else 0f
            }
            .pointerInput(state.hasNext) {
                detectHorizontalDragGestures(
                    onDragStart = { settleX?.cancel() },
                    onDragEnd = {
                        when {
                            dragX < -cardWidth * 0.3f && state.hasNext -> { haptics.skip(true); next() }
                            dragX > cardWidth * 0.3f -> { haptics.skip(false); previous() }
                        }
                        releaseDrag()
                    },
                    onDragCancel = ::releaseDrag,
                ) { change, amount -> change.consume(); dragX += amount }
            }
            .pointerInput(travel, still) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragUp > 60.dp.toPx()) { haptics.threshold(); open() }
                        else travel?.settle(false, still)
                        dragUp = 0f
                    },
                    onDragCancel = { dragUp = 0f; travel?.settle(false, still) },
                ) { change, amount ->
                    change.consume()
                    dragUp -= amount
                    val height = travel?.host?.size?.height ?: 0
                    if (!still && height > 0) travel?.drag((dragUp / height).coerceIn(0f, 1f) - travel.fraction)
                }
            }
            .clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Open player", onClick = onOpen),
    ) {
        Box {
            Row(
                Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlayerArtwork(state.artworkUri, Modifier.size(48.dp), 14.dp, ArtworkPlan.SMALL, miniEndpoint = true)
                // Asked to play but still getting ready (a stream opening, a slow file): shown only
                // when it takes a moment, so a quick start never flashes.
                val waiting = rememberPatient(state.playWhenReady && state.buffering && state.error == null)
                Column(Modifier.weight(1f)) {
                    TrackLabel(state.title, style = MaterialTheme.typography.titleSmall)
                    val subtitle = when {
                        state.error != null -> state.error.orEmpty()
                        waiting -> "Getting ready…"
                        else -> state.artist
                    }
                    TrackLabel(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) cs.error else cs.onSurfaceVariant)
                }
                KickIconButton(enabled = state.connected, onClick = { if (state.playWhenReady) haptics.pause() else haptics.play(); onToggle() },
                    modifier = Modifier.semantics { contentDescription = if (state.playWhenReady) "Pause" else "Play"; if (waiting) stateDescription = "Getting ready" }) { _ ->
                    PlayOrWait(waiting, state.playWhenReady, 28.dp, 30.dp, cs.primary)
                }
                KickIconButton(enabled = state.hasNext, onClick = { haptics.skip(true); onNext() },
                    modifier = Modifier.semantics { contentDescription = "Next" }) { kick ->
                    SkipSymbol(true, 24.dp, kick)
                }
            }
            // Progress along the bottom edge, inside the card's rounded corners.
            Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(cs.outlineVariant.copy(alpha = 0.4f))) {
                // Drawn, not measured: reading the progress while drawing redraws only this line each
                // second, not the whole mini player.
                val bar = cs.primary
                Box(Modifier.fillMaxSize().drawBehind { drawRect(bar, size = size.copy(width = size.width * progress())) })
            }
        }
    }
}

/**
 * The playback position for a progress display, as a 0..1 fraction read every [everyMs] while the
 * song plays and the screen is started. A paused song is read once; nothing runs otherwise.
 */
@Composable
private fun rememberProgress(state: PlayerState, position: () -> Long, everyMs: Long, active: Boolean = true): () -> Float {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val occurrence = state.queue.getOrNull(state.queueIndex)?.key
    var at by remember(state.mediaId, occurrence) { mutableLongStateOf(state.positionMs) }
    val readPosition by rememberUpdatedState(position)
    LaunchedEffect(lifecycle, state.connected, state.isPlaying, state.mediaId, occurrence, state.positionMs, active) {
        // Nothing to read while hidden (the mini player under the open player).
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            at = state.positionMs
            while (state.connected && state.isPlaying) {
                at = readPosition()
                delay(everyMs)
            }
        }
    }
    return { if (state.durationMs > 0) (at.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f }
}

// ---- player page ----------------------------------------------------------------------------

private data class CoverSwipe(
    val from: String?,
    val to: String,
    val fromArtwork: String?,
    val toArtwork: String?,
    val direction: Int,
    val offset: Animatable<Float, AnimationVector1D>,
)

/** The player surface is independent of service/route bindings so its real layouts can be reviewed in tests. */
@Composable
internal fun PlayerPage(
    state: PlayerState, format: String?, route: OutputRoute?, favourite: Boolean, position: () -> Long,
    onClose: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onFavourite: (() -> Unit)?, onMore: (() -> Unit)?,
    onOutput: () -> Unit, onQueue: () -> Unit, onLyrics: () -> Unit, onAudioPath: () -> Unit, onTimer: () -> Unit,
    onOpenAlbum: (() -> Unit)? = null, onOpenArtist: (() -> Unit)? = null,
    /** Lyrics shown in place of the cover, toggled by the Lyrics button; null keeps the old sheet behaviour. */
    inlineLyrics: (@Composable (Modifier) -> Unit)? = null, startWithLyrics: Boolean = false,
    /** A moving cover beside the song (cover.mp4 in its folder), played over the still cover. */
    motionCover: MotionArtwork? = null,
    /** Opens the lyrics options; shown in the top bar while the lyrics are on. */
    onLyricsTools: (() -> Unit)? = null,
    /** What the queue came from ("Top Hits", "Mix from Teardrop", "Search"), when known. */
    from: String? = null,
    restoring: Boolean = false,
    queueOpen: Boolean = false,
    lyricsSheetOpen: Boolean = false,
) {
    var lyricsShown by rememberSaveable { mutableStateOf(startWithLyrics) }
    val travel = LocalPlayerTravel.current
    androidx.compose.runtime.DisposableEffect(travel, lyricsShown, inlineLyrics != null) {
        travel?.lyricsVisible = lyricsShown && inlineLyrics != null
        onDispose { travel?.lyricsVisible = false }
    }
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // Text colours follow the theme (a page drawn outside a Surface would otherwise inherit black).
    // Swipe up anywhere on the page (once it has nowhere left to scroll) opens the queue.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val queueHaptics = rememberHaptics()
    val openQueue by rememberUpdatedState(onQueue)
    val upToQueue = remember(density, queueHaptics, restoring) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (restoring || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                if (consumed.y != 0f || available.y > 0f) pulled = 0f
                if (available.y >= 0f) return androidx.compose.ui.geometry.Offset.Zero
                pulled -= available.y
                if (pulled > with(density) { 72.dp.toPx() }) { pulled = Float.NEGATIVE_INFINITY; queueHaptics.threshold(); openQueue() }
                return androidx.compose.ui.geometry.Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity { pulled = 0f; return androidx.compose.ui.unit.Velocity.Zero }
        }
    }
    val coverTint = rememberCoverTint(state.artworkUri, cs.surfaceContainerHigh)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides cs.onSurface) {
    Box(
        // Material's calm tonal surface, a little lighter at the top; with "Colours from the cover" on,
        // that top takes a soft tint of the cover (as Pixel's own player does), easing between songs.
        Modifier.fillMaxSize().nestedScroll(upToQueue).drawWithCache {
            val background = Brush.verticalGradient(0f to coverTint.value, 0.55f to cs.surface, 1f to cs.surface)
            onDrawBehind { drawRect(background) }
        },
    ) {
        // At least a screen tall, so spare height is shared between the blocks instead of pooling
        // under the toolbar on tall phones; with large text it still scrolls.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val screen = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = screen).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerHeader(
                state = state, from = from, showLyricsTools = lyricsShown && inlineLyrics != null,
                onClose = onClose, onOpenOrigin = onOpenAlbum, onLyricsTools = onLyricsTools, onMore = onMore,
            )
            Spacer(Modifier.height(16.dp))
            // Swipe the cover for the next or previous song; double tap for favourites; long press for actions.
            val haptics = rememberHaptics()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var coverWidth by remember { mutableIntStateOf(1) }
            var heart by remember { androidx.compose.runtime.mutableStateOf(false) }
            val heartScale by animateFloatAsState(if (heart) 1f else 0f, motion.fastSpatialSpec(), label = "heart",
                finishedListener = { if (heart) heart = false })
            val settle = remember { spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium) }
            val nextArt = state.queue.getOrNull(state.nextIndex)?.artworkUri
            val previousArt = state.queue.getOrNull(state.previousIndex)?.artworkUri
            val occurrence = state.queue.getOrNull(state.queueIndex)?.key ?: state.mediaId
            val swipe = remember(occurrence, lyricsShown) { Animatable(0f) }
            val latestOccurrence by rememberUpdatedState(occurrence)
            val latestState by rememberUpdatedState(state)
            val acceptsSkip by rememberUpdatedState(state.connected && state.error == null)
            val latestPosition by rememberUpdatedState(position)
            var committed by remember { mutableStateOf<CoverSwipe?>(null) }
            var dragOffset by remember(swipe) { mutableStateOf<Float?>(null) }
            var revealPrevious by remember { mutableStateOf(false) }
            var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val direction by remember(swipe) {
                derivedStateOf { (dragOffset ?: swipe.value).compareTo(0f) }
            }
            val powerSave = LocalPowerSave.current
            LaunchedEffect(occurrence, lyricsShown) {
                // The session can acknowledge the skip before the artwork reaches the centre.
                val swap = committed
                if (!lyricsShown && swap != null && swap.to == occurrence) return@LaunchedEffect
                settleJob?.cancel()
                committed = null
            }
            androidx.compose.animation.Crossfade(lyricsShown && inlineLyrics != null,
                animationSpec = androidx.compose.animation.core.tween(if (powerSave) 0 else 165), label = "Lyrics and artwork") { words ->
                if (words && inlineLyrics != null) {
                    inlineLyrics(Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f).playerCoverEndpoint(false))
                } else {
                    val currentArt = state.artworkUri
                    val swap = committed?.takeIf { occurrence == it.from || occurrence == it.to }
                    // A painted slide owns its offset until Compose replaces both faces together.
                    val visibleOffset = {
                        (swap?.offset?.value ?: dragOffset ?: swipe.value)
                            .coerceIn(-coverWidth.toFloat(), coverWidth.toFloat())
                    }
                    val covers = listOfNotNull(currentArt, nextArt, previousArt, swap?.fromArtwork, swap?.toArtwork)
                        .distinct().associateWith { uri ->
                            androidx.compose.runtime.key(uri) {
                                rememberCoverImage(uri, ArtworkPlan.LARGE, coverWidth.takeIf { it > 1 } ?: 0)
                            }
                        }
                    val tapFavourite by rememberUpdatedState(onFavourite)
                    val tapMore by rememberUpdatedState(onMore)
                    val isFavourite by rememberUpdatedState(favourite)
                    val next by rememberUpdatedState(onNext)
                    val previous by rememberUpdatedState(onPrevious)
                    val showNext = swap?.direction == -1 || (swap == null && direction < 0 && state.hasNext)
                    val showPrevious = swap?.direction == 1 || (swap == null && direction > 0 && revealPrevious)
                    val shape = if (LocalPlayerTravel.current != null) playerCoverShape else RoundedCornerShape(32.dp)
                    Box(
                        Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f)
                            .onSizeChanged { coverWidth = it.width.coerceAtLeast(1) }
                            .playerCoverEndpoint(false).clip(shape)
                            .background(cs.surfaceContainerHighest)
                            .semantics {
                                contentDescription = "Album artwork"
                                if (tapMore != null) onLongClick("Song actions") { tapMore?.invoke(); true }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = { tapFavourite?.let { action ->
                                        if (!isFavourite) heart = true
                                        haptics.like(!isFavourite)
                                        action()
                                    } },
                                    onLongPress = { tapMore?.let { haptics.longPress(); it() } },
                                )
                            }
                            .pointerInput(swipe, powerSave, restoring) {
                                if (restoring) return@pointerInput
                                fun finishDrag(release: Boolean) {
                                    val x = dragOffset ?: return
                                    val width = coverWidth.toFloat()
                                    val playback = latestState
                                    val goNext = release && x < -width * 0.2f
                                    val goPrevious = release && x > width * 0.2f
                                    val target = when {
                                        goNext && playback.hasNext -> playback.queue.getOrNull(playback.nextIndex)
                                        goPrevious && revealPrevious -> playback.queue.getOrNull(playback.previousIndex)
                                        else -> null
                                    }?.takeUnless { it.key == occurrence }
                                    settleJob = scope.launch {
                                        swipe.snapTo(x)
                                        dragOffset = null
                                        if (occurrence != latestOccurrence) return@launch
                                        if (target != null) {
                                            val side = if (goNext) -1 else 1
                                            committed = CoverSwipe(occurrence, target.key, playback.artworkUri, target.artworkUri, side, swipe)
                                            haptics.skip(goNext)
                                            if (goNext) next() else previous()
                                            if (powerSave) swipe.snapTo(side * width) else swipe.animateTo(side * width, settle)
                                            kotlinx.coroutines.withTimeoutOrNull(3_000) {
                                                androidx.compose.runtime.snapshotFlow { latestOccurrence != occurrence || !acceptsSkip }.first { it }
                                            }
                                            if (latestOccurrence == target.key) {
                                                committed = null
                                                return@launch
                                            }
                                        } else if (goNext && playback.hasNext) {
                                            haptics.skip(true)
                                            next()
                                        } else if (goPrevious) {
                                            haptics.skip(false)
                                            previous()
                                        }
                                        if (powerSave) swipe.snapTo(0f) else swipe.animateTo(0f, settle)
                                        committed = null
                                    }
                                }
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        if (committed == null) {
                                            settleJob?.cancel()
                                            revealPrevious = latestState.hasPrevious && latestPosition() < 3_000
                                            dragOffset = visibleOffset()
                                        }
                                    },
                                    onDragEnd = { if (committed == null) finishDrag(release = true) },
                                    onDragCancel = { if (committed == null) finishDrag(release = false) },
                                ) { change, amount ->
                                    change.consume()
                                    val x = dragOffset
                                    if (committed == null && x != null) {
                                        dragOffset = (x + amount).coerceIn(-coverWidth.toFloat(), coverWidth.toFloat())
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val emptyCover = CoverImage(null, false)
                        if (showNext || showPrevious) {
                            val incomingArt = if (swap != null) swap.toArtwork else if (showNext) nextArt else previousArt
                            PlayerCoverFace(covers[incomingArt] ?: emptyCover,
                                Modifier.matchParentSize().graphicsLayer {
                                    translationX = kotlin.math.round(visibleOffset()) + if (showNext) coverWidth else -coverWidth
                                }, 0.dp, retainWhileLoading = false, shape = RectangleShape)
                        }
                        val outgoingArt = if (swap != null) swap.fromArtwork else currentArt
                        PlayerCoverFace(covers[outgoingArt] ?: emptyCover,
                            Modifier.matchParentSize().graphicsLayer {
                                val x = visibleOffset()
                                translationX = if ((!showNext && x < 0) || (!showPrevious && x > 0)) rubber(x, 60.dp.toPx())
                                    else kotlin.math.round(x)
                            },
                            corner = 0.dp, motion = motionCover.takeIf { swap == null },
                            moving = state.isPlaying, showMotion = direction == 0,
                            retainWhileLoading = false, shape = RectangleShape,
                        )
                        if (heartScale > 0f) Symbol(Glyph.FAVOURITE, size = 120.dp, filled = true, color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.graphicsLayer { scaleX = heartScale; scaleY = heartScale; alpha = heartScale })
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TrackLabel(state.title, style = MaterialTheme.typography.headlineSmallEmphasized, marquee = true)
                    TrackLabel(state.artist, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant,
                        modifier = if (onOpenArtist != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Open artist", onClick = onOpenArtist) else Modifier)
                }
                KickIconButton(enabled = onFavourite != null, onClick = { haptics.like(!favourite); onFavourite?.invoke() }, modifier = Modifier.semantics {
                    contentDescription = if (favourite) "Remove from favourites" else "Add to favourites"; selected = favourite
                }) { kick -> Symbol(Glyph.FAVOURITE, size = 28.dp, filled = favourite, color = if (favourite) cs.primary else cs.onSurfaceVariant, kick = kick) }
            }
            Spacer(Modifier.height(20.dp))
            PlaybackProgress(state, position, onSeek, Modifier.widthIn(max = 420.dp), restoring)
            if (restoring) Spacer(Modifier.height(48.dp)) else Row(
                Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val deadline = state.sleepDeadlineMs
                SignalPath(format, state.buffering && state.error == null, onAudioPath,
                    if (deadline == null) Modifier else Modifier.weight(1f, fill = false))
                if (deadline != null) SleepCountdown(deadline, onTimer, Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.weight(1f))
            Transport(state, onToggle, onNext, onPrevious, onShuffle, onRepeat, onSkipHeld = { forward ->
                onSeek((position() + if (forward) 10_000L else -10_000L).coerceIn(0L, state.durationMs.coerceAtLeast(0L)))
            })
            Spacer(Modifier.height(28.dp))
            val context = LocalContext.current
            var feel by remember(context) { mutableStateOf(ua.olex.music.playback.MusicHaptics.enabled(context)) }
            PlayerDock(
                lyrics = if (inlineLyrics != null) lyricsShown else lyricsSheetOpen,
                inlineLyrics = inlineLyrics != null, timer = state.sleepDeadlineMs != null,
                queue = queueOpen, output = when (route?.glyph()) {
                    Glyph.HEADPHONES -> PlayerDockMark.HEADPHONES
                    Glyph.USB -> PlayerDockMark.USB
                    else -> PlayerDockMark.SPEAKER
                },
                feel = feel, ready = !restoring, connected = state.connected,
                onLyrics = { if (inlineLyrics != null) lyricsShown = !lyricsShown else onLyrics() },
                onTimer = onTimer, onQueue = onQueue, onOutput = onOutput, onFeel = {
                    feel = !feel
                    ua.olex.music.playback.MusicHaptics.set(context, feel, ua.olex.music.playback.MusicHaptics.strength(context))
                },
            )
            state.sleepTimerError?.let { Text(it, color = cs.error, modifier = Modifier.padding(top = 8.dp)) }
            state.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) }
            Spacer(Modifier.height(24.dp))
        }
        }
    }
    }
}

@Composable
internal fun Transport(
    state: PlayerState, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onSkipHeld: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    val still = LocalPowerSave.current
    androidx.compose.foundation.layout.BoxWithConstraints {
        val side = 56.dp
        val playSlot = (maxWidth - side * 4 - 8.dp).coerceIn(88.dp, 112.dp)
        val playing = state.playWhenReady
        val waiting = rememberPatient(state.connecting && !state.isPlaying || playing && state.buffering && state.error == null)
        androidx.compose.material3.ButtonGroup(
            overflowIndicator = { menu ->
                IconButton(onClick = { menu.show() }, modifier = Modifier.semantics { contentDescription = "More playback controls" }) { Symbol(Glyph.MORE) }
            },
            expandedRatio = 0.15f, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            fun item(label: String, enabled: Boolean, action: () -> Unit,
                content: @Composable (Modifier, MutableInteractionSource) -> Unit) {
                customItem(buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    val width = if (still) Modifier else Modifier.animateWidth(interaction, compressionLimit = 8.dp)
                    content(width, interaction)
                }, menuContent = { menu ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, enabled = enabled,
                        onClick = { menu.dismiss(); action() })
                })
            }
            item("Shuffle", state.connected, onShuffle) { width, input ->
                ModeToggle(shuffle = state.shuffle, enabled = state.connected, size = side, modifier = width, interactionSource = input, onClick = onShuffle)
            }
            item("Previous", state.connected, onPrevious) { width, input ->
                SkipButton(false, state.connected, size = side, onHold = if (state.seekable) ({ onSkipHeld(false) }) else null,
                    modifier = width, interactionSource = input) { haptics.skip(false); onPrevious() }
            }
            item(if (playing) "Pause" else "Play", state.connected, onToggle) { width, input ->
                PlayerPlayToggle(playing, state.connected, waiting, size = playSlot, modifier = width, interactionSource = input, onClick = onToggle)
            }
            item("Next", state.hasNext, onNext) { width, input ->
                SkipButton(true, state.hasNext, size = side, onHold = if (state.seekable) ({ onSkipHeld(true) }) else null,
                    modifier = width, interactionSource = input) { haptics.skip(true); onNext() }
            }
            item("Repeat", state.connected, onRepeat) { width, input ->
                ModeToggle(repeat = state.repeat, enabled = state.connected, size = side, modifier = width, interactionSource = input, onClick = onRepeat)
            }
        }
    }
}

@Composable
internal fun PlayerPlayToggle(
    playing: Boolean, enabled: Boolean, waiting: Boolean, modifier: Modifier = Modifier, size: Dp = 112.dp,
    interactionSource: MutableInteractionSource? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = playerControlSelection(playing)
    val push by playerPressRelease(pressed)
    val height = minOf(size, 72.dp)
    Box(modifier.width(size).height(height).drawBehind {
        val t = active.value.coerceIn(0f, 1f)
        val pressure = push.coerceIn(-1f, 1f)
        val width = minOf(this.size.width, (88f + 24f * t).dp.toPx()) * (1f - pressure * 0.06f)
        val bodyHeight = this.size.height * (1f - pressure * 0.06f)
        val rounding = (minOf(width, bodyHeight) * (0.5f - 0.16f * t) - 8.dp.toPx() * pressure).coerceAtLeast(0f)
        val position = androidx.compose.ui.geometry.Offset(center.x - width / 2, center.y - bodyHeight / 2)
        val box = androidx.compose.ui.geometry.Size(width, bodyHeight)
        val corner = androidx.compose.ui.geometry.CornerRadius(rounding)
        drawRoundRect(colors.primary, position, box, corner)
        if (focused) drawRoundRect(colors.onPrimary, position, box, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }.toggleable(playing, interactionSource = interaction, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Checkbox) {
        if (it) haptics.play() else haptics.pause()
        onClick()
    }.semantics {
        contentDescription = if (playing) "Pause" else "Play"
        if (waiting) stateDescription = "Getting ready"
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { scaleX = 1f - push.coerceIn(-1f, 1f) * 0.06f; scaleY = scaleX }) {
            if (waiting) androidx.compose.material3.LoadingIndicator(Modifier.size(38.dp), color = colors.onPrimary)
            else PlayPauseSymbol(playing, minOf(size - 8.dp, 40.dp), pressed = false, color = colors.onPrimary)
        }
    }
}

@Composable
internal fun SkipButton(
    forward: Boolean, enabled: Boolean, modifier: Modifier = Modifier, size: Dp = 52.dp, onHold: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null, onClick: () -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(size)
        .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button,
            onLongClickLabel = if (forward) "Forward 10 seconds" else "Back 10 seconds", onLongClick = onHold, onClick = onClick)
        .semantics { contentDescription = if (forward) "Next" else "Previous" }, contentAlignment = Alignment.Center) {
        SkipSymbol(forward, 28.dp, kick = 0, pressed = pressed,
            color = if (!enabled) colors.onSurface.copy(alpha = 0.38f) else colors.onSurfaceVariant)
    }
}

/** Two shuffle states and three repeat states share the same fixed transport slots. */
@Composable
internal fun ModeToggle(
    enabled: Boolean, modifier: Modifier = Modifier, shuffle: Boolean = false, repeat: RepeatMode? = null, size: Dp = 52.dp,
    interactionSource: MutableInteractionSource? = null, onClick: () -> Unit,
) {
    val on = repeat?.let { it != RepeatMode.OFF } ?: shuffle
    val label = if (repeat == null) "Shuffle" else "Repeat"
    val state = when (repeat) {
        null -> if (shuffle) "On" else "Off"
        RepeatMode.OFF -> "Off"
        RepeatMode.ALL -> "All"
        RepeatMode.ONE -> "One"
    }
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = playerControlSelection(on && enabled)
    val push = playerControlPress(pressed)
    val click = if (repeat == null) {
        Modifier.toggleable(on, interactionSource = interaction, indication = null, enabled = enabled,
            role = androidx.compose.ui.semantics.Role.Switch) {
            haptics.toggle(it)
            onClick()
        }
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled,
            role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Change repeat mode") {
            when (repeat) {
                RepeatMode.OFF -> haptics.toggle(true)
                RepeatMode.ALL -> haptics.tick()
                RepeatMode.ONE -> haptics.toggle(false)
            }
            onClick()
        }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(18.dp)).playerControlContainer(active, push, focused, colors.secondaryContainer, colors.primary)
        .then(click).semantics { contentDescription = label; stateDescription = state }, contentAlignment = Alignment.Center) {
        PlayerModeSymbol(shuffle = repeat == null, on = on, one = repeat == RepeatMode.ONE,
            active = active, push = push, offInk = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f),
            onInk = colors.onSecondaryContainer)
    }
}

/** The dock stays mounted under its sheets, so its entrance runs once per player opening. */
@Composable
internal fun PlayerDock(
    lyrics: Boolean, inlineLyrics: Boolean, timer: Boolean, queue: Boolean, output: PlayerDockMark, feel: Boolean,
    ready: Boolean, connected: Boolean, onLyrics: () -> Unit, onTimer: () -> Unit, onQueue: () -> Unit,
    onOutput: () -> Unit, onFeel: () -> Unit,
) {
    val still = LocalPowerSave.current
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val entrance = remember { List(5) { Animatable(if (still) 1f else 0f) } }
    LaunchedEffect(still) {
        if (still || coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor == 0f) {
            entrance.forEach { it.snapTo(1f) }
        } else kotlinx.coroutines.coroutineScope {
            entrance.forEachIndexed { index, progress ->
                launch {
                    delay(index * 30L)
                    progress.animateTo(1f, spatial)
                }
            }
        }
    }
    fun settle(index: Int) = Modifier.graphicsLayer {
        val phase = if (still) 1f else entrance[index].value
        translationY = 8.dp.toPx() * (1f - phase)
        alpha = phase.coerceIn(0f, 1f)
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleAction(PlayerDockMark.LYRICS, "Lyrics", lyrics, ready, if (lyrics) "Shown" else "Hidden",
                modifier = settle(0), checked = inlineLyrics, onClick = onLyrics)
            ToggleAction(PlayerDockMark.SLEEP, "Sleep timer", timer, connected, if (timer) "On" else "Off", settle(1), onClick = onTimer)
            ToggleAction(PlayerDockMark.QUEUE, "Open queue", queue, ready, null, settle(2), highlight = false, onClick = onQueue)
            ToggleAction(output, "Change output", false, ready, null, settle(3), onClick = onOutput)
            ToggleAction(PlayerDockMark.FEEL, "Feel the music", feel, ready, if (feel) "On" else "Off",
                modifier = settle(4), checked = true, onClick = onFeel)
        }
    }
}

@Composable
internal fun ToggleAction(
    mark: PlayerDockMark, label: String, on: Boolean, enabled: Boolean, state: String?,
    modifier: Modifier, checked: Boolean = false, highlight: Boolean = true, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = playerControlSelection(on && enabled && highlight)
    val push = playerControlPress(pressed)
    var kick by remember { mutableIntStateOf(0) }
    val action = if (checked) Modifier.toggleable(on, interactionSource = interaction, indication = null, enabled = enabled,
        role = androidx.compose.ui.semantics.Role.Checkbox) { haptics.toggle(it); onClick() }
    else Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) {
        kick++
        haptics.tick()
        onClick()
    }
    Box(Modifier.size(48.dp).then(modifier).playerControlContainer(active, push, focused, colors.secondaryContainer, colors.primary)
        .then(action).semantics { contentDescription = label; if (state != null) stateDescription = state },
        contentAlignment = Alignment.Center) {
        PlayerDockSymbol(mark, on, push, active, kick,
            if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f), colors.onSecondaryContainer)
    }
}

/** The file's format and bitrate, quiet under the seek bar; a tap opens the full audio path. */
@Composable
private fun SignalPath(format: String?, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.clip(CircleShape).clickable(onClickLabel = "Open audio path", onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // A new song's format usually arrives at once: the line stays empty (its height kept) rather
        // than flashing "Reading", and the text fades from one to the next.
        val waiting = rememberPatient(format == null && loading, delayMs = 600)
        val text = format ?: when { waiting -> "Reading audio format…"; loading -> ""; else -> "Format unavailable" }
        androidx.compose.animation.Crossfade(text, Modifier.weight(1f, fill = false), MaterialTheme.motionScheme.fastEffectsSpec(), label = "format") { line ->
            Text(line, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (text.isNotEmpty()) Symbol(Glyph.CHEVRON, size = 16.dp, color = cs.onSurfaceVariant)
    }
}

/**
 * The seek bar's track as Android 16's media controls draw it: the played part a moving wave, the
 * rest a quiet straight line, a gap around Material's own handle. The wave travels only while the
 * music plays; on pause or while dragging it flattens on a spring and stops.
 */
@Composable
private fun WaveTrack(fraction: () -> Float, wave: Boolean) {
    val cs = MaterialTheme.colorScheme
    val amplitude = androidx.compose.animation.core.animateFloatAsState(if (wave) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "wave")
    // The wave travels only while it shows; once flat, nothing keeps running. Position, phase and
    // amplitude are read only while drawing: a frame redraws this line and nothing else.
    val phase = if (wave || amplitude.value > 0.01f) androidx.compose.animation.core.rememberInfiniteTransition(label = "wave-phase").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_200, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
        else null
    val path = remember { androidx.compose.ui.graphics.Path() }
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val mid = size.height / 2
        // As thin as the system media player in the shade: a 3 dp line, a low wave, a short handle.
        val gap = 5.dp.toPx()
        val end = (fraction().coerceIn(0f, 1f) * size.width)
        val stroke = 3.dp.toPx()
        // The rest of the song: straight, muted, ending in a dot like Android's.
        if (end + gap < size.width) drawLine(cs.secondaryContainer, androidx.compose.ui.geometry.Offset(end + gap, mid), androidx.compose.ui.geometry.Offset(size.width, mid), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        // What has played: a sine, one point every 2 dp (smooth to the eye, a third of the work of 2 px).
        val stop = (end - gap).coerceAtLeast(0f)
        if (stop > 0f) {
            val length = 22.dp.toPx(); val height = 2.5.dp.toPx() * amplitude.value
            val shift = phase?.value ?: 0f
            val step = 2.dp.toPx()
            path.reset()
            var x = 0f
            while (true) {
                val y = mid + height * kotlin.math.sin(((x / length) - shift) * 2 * Math.PI).toFloat()
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                if (x >= stop) break
                x = minOf(x + step, stop)
            }
            drawPath(path, cs.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

/** The seek bar, in the page's accent, with the time played and the length under it. */
@Composable
internal fun PlaybackProgress(state: PlayerState, position: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, restoring: Boolean = false) {
    // Keep the frequent position updates out of artwork, metadata and controls. Composition may
    // survive screen-off, so the poller also follows lifecycle rather than only visibility.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    // The slider and the spoken time use separate clocks so drawing the handle does not rewrite the label.
    val occurrence = state.queue.getOrNull(state.queueIndex)?.key
    var seconds by remember(state.mediaId, occurrence) { mutableLongStateOf(state.positionMs / 1_000) }
    val readPosition by rememberUpdatedState(position)
    var dragged by remember(state.mediaId, occurrence) { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val slider = remember(state.mediaId, occurrence) {
        androidx.compose.material3.SliderState((state.positionMs.toFloat() / duration).coerceIn(0f, 1f), 0, 0f..1f)
    }
    val canSeek = state.connected && state.seekable && state.durationMs > 0
    val trackPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toFloat()
    fun show(ms: Long) {
        if (dragged == null) slider.value = (ms.toFloat() / duration).coerceIn(0f, 1f)
        seconds = ms / 1_000
    }
    LaunchedEffect(lifecycle, state.connected, state.isPlaying, state.mediaId, occurrence, state.positionMs, state.durationMs, state.speed, canSeek) {
        if (!canSeek) dragged = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!state.connected || !state.isPlaying) {
                show(state.positionMs)
                return@repeatOnLifecycle
            }
            // One update each time the handle would move a pixel (about 6 a second for a 3-minute
            // song), not one per display frame: the screen stays idle between them.
            val step = (state.durationMs / (trackPx.coerceAtLeast(1f) * state.speed.coerceAtLeast(0.25f))).toLong().coerceIn(40L, 1_000L)
            while (true) {
                show(readPosition())
                kotlinx.coroutines.delay(step)
            }
        }
    }
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    // 60 frames a second while it plays (the line moves about a pixel every 130 ms; 60 looks the
    // same and lets the panel stop holding 120 Hz), 120 while the owner drags it.
    Column(modifier.fillMaxWidth().then(Modifier.preferredFrameRate(if (dragged != null) 120f else 60f))) {
        // Material's own slider: the expressive track with the handle in a gap, nothing imitated.
        val touch = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        androidx.compose.material3.Slider(
            state = slider, enabled = canSeek, interactionSource = touch,
            // A detent every 2.5% (firmer when moving fast), a soft thud at either end.
            onValueChange = { v: Float ->
                val before = dragged ?: slider.value
                val step = (v * 40).toInt()
                if (dragged == null) haptics.tick()
                else if (step != (before * 40).toInt()) {
                    if (v <= 0.001f || v >= 0.999f) haptics.edge() else haptics.scrub((kotlin.math.abs(v - before) * 40).coerceIn(0f, 1f))
                }
                dragged = v; slider.value = v
            },
            onValueChangeFinished = {
                dragged?.let {
                    val target = (it * duration).toLong()
                    dragged = null
                    if (canSeek) { onSeek(target); show(target); haptics.confirm() }
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Position"; stateDescription = "${clock(seconds * 1_000)} of ${clock(state.durationMs)}" },
            thumb = { _: androidx.compose.material3.SliderState ->
                androidx.compose.material3.SliderDefaults.Thumb(touch, thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 20.dp), enabled = canSeek)
            },
            track = { st: androidx.compose.material3.SliderState ->
                WaveTrack({ st.value }, wave = state.connected && state.isPlaying && dragged == null && lifecycleState.isAtLeast(Lifecycle.State.STARTED))
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (restoring) " " else clock(dragged?.let { (it * duration).toLong() } ?: (seconds * 1_000)), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(if (restoring) " " else clock(state.durationMs), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

internal fun OutputRoute?.glyph(): Glyph = when (this?.kind) {
    OutputRoute.Kind.BLUETOOTH, OutputRoute.Kind.LE_AUDIO -> Glyph.HEADPHONES
    OutputRoute.Kind.WIRED -> Glyph.HEADPHONES
    OutputRoute.Kind.USB -> Glyph.USB
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
                AppSwitch(ratingsToFiles, onRatingsToFiles)
            }
            // The everyday four as big tonal tiles on top (as YouTube Music and Google Photos lay out
            // their share sheets), everything else as one rounded group of rows below them.
            val quick = actions.filter { it.glyph in quickGlyphs }.sortedBy { quickGlyphs.indexOf(it.glyph) }
            val rest = actions - quick.toSet()
            if (quick.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { action ->
                    val interaction = remember(action.label) { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    androidx.compose.material3.Surface(onClick = { action.run(); onDismiss() }, interactionSource = interaction,
                        shape = RoundedCornerShape(20.dp), color = if (action.filled) cs.primaryContainer else cs.surfaceContainerHigh,
                        contentColor = if (action.filled) cs.onPrimaryContainer else cs.onSurface,
                        modifier = Modifier.weight(1f).heightIn(min = 76.dp).squish(0.05f).semantics { contentDescription = action.label }) {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                            Symbol(action.glyph, filled = action.filled, pressed = pressed)
                            Text(when (action.glyph) { Glyph.ADD_TO_QUEUE -> "Queue"; Glyph.FAVOURITE -> if (action.filled) "Liked" else "Like"
                                Glyph.PLAYLIST_ADD -> "Playlist"; else -> action.label },
                                style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(24.dp)),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rest.forEach { action ->
                    val interaction = remember(action.label) { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    ListItem(
                        headlineContent = { Text(action.label) },
                        leadingContent = { Symbol(action.glyph, filled = action.filled, color = if (action.glyph == Glyph.DELETE) cs.error else cs.onSurfaceVariant, pressed = pressed) },
                        colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer,
                            headlineColor = if (action.glyph == Glyph.DELETE) cs.error else cs.onSurface),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple()) {
                            action.run()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

private val quickGlyphs = listOf(Glyph.PLAY_NEXT, Glyph.ADD_TO_QUEUE, Glyph.FAVOURITE, Glyph.PLAYLIST_ADD)

/** The remaining minutes or the finishing-song state fits next to the audio format. */
@Composable
private fun SleepCountdown(deadline: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val waitingForEnd = deadline == ua.olex.music.playback.SleepTimerCommand.END_OF_TRACK_DEADLINE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember(deadline) { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(lifecycle, deadline) {
        if (waitingForEnd) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = android.os.SystemClock.elapsedRealtime()
            while (now < deadline) {
                delay((deadline - now - 1) % 60_000L + 1)
                now = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
    val text = if (waitingForEnd) "Finishing song" else "${((deadline - now).coerceAtLeast(0) + 59_999) / 60_000} min"
    Row(modifier.clip(CircleShape).clickable(onClickLabel = "Sleep timer", onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Symbol(Glyph.SLEEP, size = 16.dp, color = cs.tertiary, filled = true)
        TrackLabel(text, style = MaterialTheme.typography.labelLarge, color = cs.tertiary)
    }
}

/** The owner's choice: the player's top tinted by the song's cover (on by default). */
internal object CoverColours {
    const val PREFS = "look"; const val KEY = "cover_colours"
    fun on(context: android.content.Context) = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, true)
}

/**
 * The player's top colour: the surface blended toward the cover's average colour (light enough for
 * light theme, deep enough for dark), worked out off the main thread from a tiny copy of the cover
 * and eased between songs. The plain surface when the setting is off or the cover can't be read.
 */
@Composable
private fun rememberCoverTint(artwork: String?, surface: androidx.compose.ui.graphics.Color): androidx.compose.runtime.State<androidx.compose.ui.graphics.Color> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val target by androidx.compose.runtime.produceState(surface, artwork, surface, dark) {
        value = if (artwork == null || !CoverColours.on(context)) surface else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val thumbnail = ua.olex.music.library.ArtworkUris.variant(artwork, context.packageName, ArtworkPlan.SMALL)
                val request = coil3.request.ImageRequest.Builder(context).data(thumbnail).size(24).build()
                val image = (coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult)?.image ?: return@runCatching surface
                val bitmap = image.toBitmap(24, 24)
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                    val c = bitmap.getPixel(x, y); r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
                }
                val cover = androidx.compose.ui.graphics.Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
                // Keep it a surface: mostly the theme's own tone, a third of the cover's colour.
                androidx.compose.ui.graphics.lerp(surface, cover, if (dark) 0.28f else 0.22f)
            }.getOrDefault(surface)
        }
    }
    return androidx.compose.animation.animateColorAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "cover-tint")
}

/** The selection mark: an empty ring, or a filled circle with a tick that springs in. */
@Composable
internal fun SelectMark(checked: Boolean, onArtwork: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fill by animateFloatAsState(if (checked) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "select")
    Box(modifier.size(26.dp).clip(CircleShape)
        .background(if (onArtwork) cs.surface.copy(alpha = 0.55f * (1f - fill)) else androidx.compose.ui.graphics.Color.Transparent)
        .border(2.dp, if (onArtwork) cs.surface else cs.outline, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill.coerceIn(0f, 1f) }
            .clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
            Symbol(Glyph.CHECK, color = cs.onPrimary, size = 18.dp, bold = true)
        }
    }
}
