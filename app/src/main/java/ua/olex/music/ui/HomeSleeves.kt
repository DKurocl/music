@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.ArtworkPlan
import ua.olex.music.model.Track

/** The first three positions share one cover composition. Every sleeve opens its own queue position. */
@Composable
internal fun CoverSpread(songs: List<Track>, currentId: String?, onPlay: (Int) -> Unit, onActions: (Track) -> Unit) {
    val gap = 4.dp
    val outside = 28.dp
    val inside = 8.dp
    val solo = songs.size == 1
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val width = maxWidth.coerceAtMost(460.dp)
        val height = width * (if (solo) 0.70f else 0.83f) + 56.dp * (fontScale - 1f)
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
            val firstShape = if (solo) RoundedCornerShape(outside) else RoundedCornerShape(outside, inside, inside, outside)
            CoverPick(songs[0], currentId, firstShape, true, Modifier.weight(if (solo) 1f else 1.8f).fillMaxHeight(),
                onPlay = { onPlay(0) }, onActions = { onActions(songs[0]) })
            if (!solo) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (index in 1 until minOf(songs.size, 3)) {
                    val track = songs[index]
                    val shape = RoundedCornerShape(inside, if (index == 1) outside else inside,
                        if (index == 2 || songs.size == 2) outside else inside, inside)
                    CoverPick(track, currentId, shape, false, Modifier.weight(1f).fillMaxWidth(),
                        onPlay = { onPlay(index) }, onActions = { onActions(track) })
                }
            }
        }
    }
}

@Composable
private fun CoverPick(track: Track, currentId: String?, shape: Shape, lead: Boolean, modifier: Modifier,
    onPlay: () -> Unit, onActions: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val current = track.id.value == currentId || track.matchesPlayback(LocalPlayingTrack.current)
    Column(modifier.squish(0.025f).clip(shape).background(if (lead) cs.primaryContainer else cs.surfaceContainerHigh)
        .combinedClickable(role = Role.Button, onClickLabel = "Play ${track.title} by ${track.artist}",
            onLongClickLabel = "Song actions", onLongClick = { haptics.longPress(); onActions() },
            onClick = { haptics.confirm(); onPlay() })
        .semantics { if (current) stateDescription = "Current track" }) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Artwork(track.artworkUri, Modifier.fillMaxSize(), 0.dp, pixels = if (lead) ArtworkPlan.LARGE else ArtworkPlan.MEDIUM)
            if (current) Surface(shape = CircleShape, color = cs.primary, contentColor = cs.onPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Symbol(if (LocalIsPlaying.current) Glyph.PLAY else Glyph.PAUSE, size = 16.dp, filled = true, modifier = Modifier.padding(6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = if (lead) 14.dp else 10.dp, vertical = if (lead) 12.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = if (lead) MaterialTheme.typography.headlineSmallEmphasized else MaterialTheme.typography.titleSmallEmphasized,
                    color = if (lead) cs.onPrimaryContainer else cs.onSurface, maxLines = if (lead) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (lead) Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (lead) Box(Modifier.padding(start = 6.dp).size(38.dp).clip(CircleShape).background(cs.onPrimaryContainer), contentAlignment = Alignment.Center) {
                Symbol(Glyph.PLAY, color = cs.primaryContainer, size = 22.dp, filled = true)
            }
        }
    }
}

/** The original Home anchor: one album and two independent starts from the music library. */
@Composable
internal fun HomeStarts(album: ua.olex.music.model.MusicCollection?, firstPick: Track?, librarySongs: List<Track>, favourites: List<Track>,
    currentId: String?, transportPlaying: Boolean, onToggle: () -> Unit,
    onAlbum: () -> Unit, onPlayAlbum: () -> Unit, onShuffle: () -> Unit, onLiked: () -> Unit, onChooseLiked: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val title = album?.name ?: firstPick?.title ?: return
    val artist = album?.subtitle ?: firstPick?.artist.orEmpty()
    val playing = LocalPlayingTrack.current
    val active = remember(album, firstPick?.id, playing, currentId) {
        fun matches(t: Track) = t.id.value == currentId || t.matchesPlayback(playing)
        album?.tracks?.any(::matches) ?: (firstPick?.let(::matches) == true)
    }
    val likedArt = remember(favourites) { favourites.asSequence().take(20).mapNotNull { it.artworkUri }.distinct().take(3).toList() }
    val transportLabel = when {
        active && transportPlaying -> "Pause $title"
        active -> "Resume $title"
        else -> "Play $title"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(248.dp + 116.dp * (fontScale - 1f)),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val coverInput = remember { MutableInteractionSource() }
        val coverPressed by coverInput.collectIsPressedAsState()
        val still = LocalPowerSave.current
        val zoom by animateFloatAsState(if (coverPressed && !still) 1.045f else 1f,
            if (still) snap() else MaterialTheme.motionScheme.fastSpatialSpec(), label = "Sleeve press")
        Box(Modifier.weight(1.5f).fillMaxHeight().clip(RoundedCornerShape(32.dp)).background(cs.primaryContainer)) {
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, interactionSource = coverInput,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClickLabel = if (album != null) "Open $title" else "Song actions for $title",
                onClick = { haptics.tick(); onAlbum() })) {
                Artwork(if (album != null) album.artworkUri else firstPick?.artworkUri,
                    Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, 0.dp, pixels = ArtworkPlan.LARGE)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.28f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.86f))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 15.dp, end = 60.dp, bottom = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(artist, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (active && playing != null) Surface(color = cs.surfaceContainerHigh, shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Symbol(if (transportPlaying) Glyph.WAVE else Glyph.PAUSE, size = 14.dp, color = cs.primary)
                        Text(playing.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val transportInput = remember { MutableInteractionSource() }
            val transportPressed by transportInput.collectIsPressedAsState()
            Surface(onClick = { haptics.confirm(); if (active) onToggle() else onPlayAlbum() },
                interactionSource = transportInput, shape = MaterialShapes.Cookie9Sided.toShape(),
                color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(48.dp).semantics { contentDescription = transportLabel }) {
                Box(contentAlignment = Alignment.Center) {
                    PlayPauseSymbol(active && transportPlaying, size = 24.dp, pressed = transportPressed)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            StartTile("Shuffle all", songCount(librarySongs.size), Glyph.SHUFFLE, MaterialShapes.Sunny.toShape(),
                cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary, Modifier.weight(1f), enabled = librarySongs.isNotEmpty()) {
                haptics.confirm(); onShuffle()
            }
            StartTile("Liked", songCount(favourites.size), Glyph.FAVOURITE, MaterialShapes.Cookie9Sided.toShape(),
                cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary, Modifier.weight(1f), enabled = favourites.isNotEmpty(), previews = likedArt, onLongClick = onChooseLiked) {
                haptics.confirm(); onLiked()
            }
        }
    }
}

/** A bright start tile: the icon in an M3E shape, title and a count. */
@Composable
private fun StartTile(title: String, subtitle: String, glyph: Glyph, shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color, onAccent: androidx.compose.ui.graphics.Color,
    modifier: Modifier, enabled: Boolean, previews: List<String> = emptyList(), onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    val still = LocalPowerSave.current
    val motion = remember { androidx.compose.animation.core.Animatable(0f) }
    val input = remember { MutableInteractionSource() }
    val pressed by input.collectIsPressedAsState()
    val pressure by animateFloatAsState(if (pressed && !still) 1f else 0f,
        if (still) snap() else MaterialTheme.motionScheme.fastSpatialSpec(), label = "Start tile pressure")
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val first = remember { androidx.compose.ui.graphics.Path() }
    val second = remember { androidx.compose.ui.graphics.Path() }
    LaunchedEffect(taps, still) {
        if (taps > 0 && !still) {
            motion.snapTo(0f)
            motion.animateTo(1f, androidx.compose.animation.core.tween(110))
            motion.animateTo(0f, spring)
        } else motion.snapTo(0f)
    }
    val outer = 28.dp
    val join = 10.dp
    val tileShape = if (glyph == Glyph.SHUFFLE) RoundedCornerShape(outer, outer, join, join) else RoundedCornerShape(join, join, outer, outer)
    val cs = MaterialTheme.colorScheme
    Surface(shape = tileShape, color = if (enabled) container else cs.surfaceContainerHigh,
        contentColor = if (enabled) content else cs.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().graphicsLayer {
            scaleX = 1f - pressure * 0.025f
            scaleY = 1f - pressure * 0.035f
        }.clip(tileShape).combinedClickable(interactionSource = input, indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled, role = Role.Button, onClickLabel = if (glyph == Glyph.SHUFFLE) "Shuffle library" else "Play liked songs",
            onLongClickLabel = if (onLongClick != null) "Choose a liked song" else null, onLongClick = onLongClick,
            onClick = { taps++; onClick() })) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height(39.dp)) {
                Box(Modifier.size(39.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().graphicsLayer {
                        val wave = motion.value
                        rotationZ = if (glyph == Glyph.SHUFFLE) wave * 25f else wave * 12f
                        scaleX = 1f + wave * 0.08f
                        scaleY = scaleX
                    }.clip(shape).background(if (enabled) accent else cs.surfaceContainerHighest))
                    val ink = if (enabled) onAccent else cs.onSurfaceVariant
                    if (glyph == Glyph.SHUFFLE) {
                        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                            val unit = size.width / 24f
                            val bend = motion.value * 5f - pressure * 2f
                            val pen = 2.1f * unit
                            first.apply {
                                rewind()
                                moveTo(3f * unit, 6f * unit)
                                cubicTo(9f * unit, (6f - bend) * unit, 13f * unit, (18f + bend) * unit, 20f * unit, 18f * unit)
                            }
                            second.apply {
                                rewind()
                                moveTo(3f * unit, 18f * unit)
                                cubicTo(9f * unit, (18f + bend) * unit, 13f * unit, (6f - bend) * unit, 20f * unit, 6f * unit)
                            }
                            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(pen, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawPath(first, ink, style = stroke)
                            drawPath(second, ink, style = stroke)
                            drawLine(ink, androidx.compose.ui.geometry.Offset(17f * unit, 3f * unit), androidx.compose.ui.geometry.Offset(20f * unit, 6f * unit), pen, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(ink, androidx.compose.ui.geometry.Offset(20f * unit, 6f * unit), androidx.compose.ui.geometry.Offset(17f * unit, 9f * unit), pen, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(ink, androidx.compose.ui.geometry.Offset(17f * unit, 15f * unit), androidx.compose.ui.geometry.Offset(20f * unit, 18f * unit), pen, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(ink, androidx.compose.ui.geometry.Offset(20f * unit, 18f * unit), androidx.compose.ui.geometry.Offset(17f * unit, 21f * unit), pen, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    } else {
                        Symbol(glyph, color = ink, filled = true, modifier = Modifier.graphicsLayer {
                            scaleX = 1f - pressure * 0.12f + motion.value * 0.20f
                            scaleY = scaleX
                        })
                    }
                }
                if (previews.isNotEmpty()) Box(Modifier.align(Alignment.CenterEnd).width(46.dp).height(36.dp)) {
                    previews.forEachIndexed { i, uri ->
                        Artwork(uri, Modifier.align(Alignment.CenterEnd).offset(x = ((i - previews.lastIndex) * 7).dp)
                            .size(27.dp).graphicsLayer {
                                val depth = i - previews.lastIndex
                                val direction = if (rtl) -1f else 1f
                                rotationZ = direction * (depth * 8f + motion.value * (depth * 4f + 5f))
                                translationX = direction * depth * 2.dp.toPx() * motion.value
                                translationY = -motion.value * (i + 1).dp.toPx()
                            }, 5.dp)
                    }
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Songs in columns of three, swiped sideways a column at a time, the next column peeking in:
 * many songs in little height, the way YouTube Music opens. A tap plays the shelf from that song.
 */
@Composable
internal fun QuickPicks(title: String, songs: List<Track>, currentId: String?, onPlay: (List<Track>, Int) -> Unit, onTrackActions: (Track) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val columns = remember(songs) { songs.chunked(3) }
    val pager = rememberPagerState { columns.size }
    val scope = rememberCoroutineScope()
    val still = LocalPowerSave.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLargeEmphasized)
        if (columns.size > 1) {
            val atEnd = pager.currentPage >= columns.lastIndex
            KickIconButton(onClick = {
                val target = if (atEnd) 0 else (pager.currentPage + 1).coerceAtMost(columns.lastIndex)
                scope.launch { if (still) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
            }, modifier = Modifier.semantics { contentDescription = if (atEnd) "First picks" else "Next picks" }) { k ->
                Symbol(if (atEnd) Glyph.BACK else Glyph.CHEVRON, size = 20.dp, color = cs.primary, kick = k)
            }
        }
    }
    HorizontalPager(state = pager, pageSpacing = 8.dp,
        contentPadding = PaddingValues(start = 16.dp, end = if (columns.size > 1) 44.dp else 16.dp),
        verticalAlignment = Alignment.Top) { c ->
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerLow)) {
            columns[c].forEachIndexed { row, track ->
                val marked = track.id.value == currentId || track.matchesPlayback(LocalPlayingTrack.current)
                val haptics = rememberHaptics()
                Row(Modifier.fillMaxWidth().combinedClickable(onClickLabel = "Play ${track.title}", onLongClickLabel = "Song actions",
                    onLongClick = { haptics.longPress(); onTrackActions(track) }) { onPlay(songs, c * 3 + row) }
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))) {
                        Artwork(track.artworkUri, Modifier.fillMaxSize(), 0.dp)
                        if (marked) NowPlayingOverlay(Modifier.matchParentSize())
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (marked) cs.primary else cs.onSurface)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
