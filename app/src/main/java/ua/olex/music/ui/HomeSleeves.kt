@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.testTag
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
            NowPlayingOverlay(current, Modifier.matchParentSize())
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

/** A shelf entry opens a local collection or the original YouTube Music album. */
internal data class HomeAlbumPick(val id: String, val title: String, val artist: String?, val cover: String?,
    val local: ua.olex.music.model.MusicCollection? = null)

@Composable
internal fun HomeStarts(albums: List<HomeAlbumPick>, shuffleCount: Int, favourites: List<Track>,
    onOpenAlbum: (ua.olex.music.model.MusicCollection) -> Unit, onShuffle: () -> Unit, onLiked: () -> Unit, onChooseLiked: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val likedArt = remember(favourites) { favourites.asSequence().take(20).mapNotNull { it.artworkUri }.distinct().take(3).toList() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .height((if (albums.isEmpty()) 112.dp else 224.dp) + 116.dp * (fontScale - 1f)),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (albums.isNotEmpty()) AlbumPocket(albums, Modifier.weight(1.35f).fillMaxHeight(), onOpenAlbum)
        val actions: @Composable (Modifier) -> Unit = { tile ->
            StartTile("Shuffle all", songCount(shuffleCount), Glyph.SHUFFLE, MaterialShapes.Sunny.toShape(),
                cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary, tile, enabled = shuffleCount > 0) {
                haptics.confirm(); onShuffle()
            }
            StartTile("Liked", songCount(favourites.size), Glyph.FAVOURITE, MaterialShapes.Cookie9Sided.toShape(),
                cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary, tile, enabled = favourites.isNotEmpty(), previews = likedArt, onLongClick = onChooseLiked) {
                haptics.confirm(); onLiked()
            }
        }
        if (albums.isEmpty()) actions(Modifier.weight(1f).fillMaxHeight())
        else Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            actions(Modifier.weight(1f))
        }
    }
}

/** Art stays put during transport changes. The only movement follows a tap or a horizontal swipe. */
@Composable
private fun AlbumPocket(albums: List<HomeAlbumPick>, modifier: Modifier,
    onOpenAlbum: (ua.olex.music.model.MusicCollection) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pager = rememberPagerState { albums.size }
    val nav = LocalOnlineNav.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val still = LocalPowerSave.current
    Column(modifier.clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 3.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Album picks", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            if (albums.size > 1) KickIconButton(onClick = {
                val next = (pager.targetPage + 1) % albums.size
                haptics.tick()
                scope.launch { if (still) pager.scrollToPage(next) else pager.animateScrollToPage(next) }
            }, modifier = Modifier.semantics { contentDescription = "Next album pick" }) { kick ->
                Symbol(Glyph.CHEVRON, size = 18.dp, kick = kick)
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize().testTag("album-picks"), key = { i -> (if (albums[i].local == null) "yt:" else "local:") + albums[i].id }) { i ->
            val pick = albums[i]
            val input = remember { MutableInteractionSource() }
            val pressed by input.collectIsPressedAsState()
            val pressure by animateFloatAsState(if (pressed && !still) 1f else 0f,
                if (still) snap() else MaterialTheme.motionScheme.fastSpatialSpec(), label = "Album sleeve")
            Column(Modifier.fillMaxSize().clickable(role = Role.Button, interactionSource = input,
                indication = androidx.compose.foundation.LocalIndication.current, onClickLabel = "Open ${pick.title}",
                onClick = {
                    haptics.tick()
                    if (pick.local != null) onOpenAlbum(pick.local)
                    else nav.open(OnlineTarget.Album(pick.id, pick.title, cover = pick.cover, subtitle = pick.artist))
                }).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxHeight().aspectRatio(1f).padding(4.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.matchParentSize().graphicsLayer {
                            translationX = (12f + 4f * pressure).dp.toPx()
                        }) {
                            val radius = size.minDimension / 2
                            drawCircle(cs.secondary, radius)
                            for (ring in 3..5) drawCircle(cs.onSecondary.copy(alpha = 0.42f), radius * ring / 6f,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        }
                        Artwork(pick.cover, Modifier.fillMaxSize().graphicsLayer {
                            translationX = -6.dp.toPx()
                            rotationZ = -4f + pressure * 4f
                            scaleX = 1f - pressure * 0.035f
                            scaleY = scaleX
                        }, 9.dp, pixels = ArtworkPlan.MEDIUM)
                    }
                }
                Text(pick.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                pick.artist?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    val motion = remember { Animatable(0f) }
    val input = remember { MutableInteractionSource() }
    val pressed by input.collectIsPressedAsState()
    val pressure = animateFloatAsState(if (pressed && !still) 1f else 0f,
        if (still) snap() else tween(if (pressed) 75 else 120), label = "Start tile pressure")
    LaunchedEffect(taps, still) {
        if (still) motion.snapTo(0f)
        else if (taps > 0) {
            motion.animateTo(1f, tween(100, easing = LinearOutSlowInEasing))
            motion.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 510f))
        }
    }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val outer = 28.dp
    val join = 10.dp
    val tileShape = if (glyph == Glyph.SHUFFLE) RoundedCornerShape(outer, outer, join, join) else RoundedCornerShape(join, join, outer, outer)
    val cs = MaterialTheme.colorScheme
    Surface(shape = tileShape, color = if (enabled) container else cs.surfaceContainerHigh,
        contentColor = if (enabled) content else cs.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().clip(tileShape).combinedClickable(interactionSource = input, indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled, role = Role.Button, onClickLabel = if (glyph == Glyph.SHUFFLE) "Shuffle library" else "Play liked songs",
            onLongClickLabel = if (onLongClick != null) "Choose a liked song" else null, onLongClick = onLongClick,
            onClick = { taps++; onClick() })) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height(39.dp)) {
                val badge = if (enabled) accent else cs.surfaceContainerHighest
                val ink = if (enabled) onAccent else cs.onSurfaceVariant
                Box(Modifier.size(39.dp).clip(shape).background(badge), contentAlignment = Alignment.Center) {
                    StartMark(glyph == Glyph.SHUFFLE, ink, badge, { motion.value }, { pressure.value })
                }
                if (previews.isNotEmpty()) Box(Modifier.align(Alignment.CenterEnd).width(46.dp).height(36.dp)) {
                    previews.forEachIndexed { i, uri ->
                        val depth = i - previews.lastIndex
                        Artwork(uri, Modifier.align(Alignment.CenterEnd).offset(x = (depth * 7).dp)
                            .size(27.dp).graphicsLayer {
                                rotationZ = depth * 6f * if (rtl) -1f else 1f
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
                        NowPlayingOverlay(marked, Modifier.matchParentSize())
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
