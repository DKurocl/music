@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.background
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
                Symbol(Glyph.WAVE, size = 16.dp, modifier = Modifier.padding(6.dp))
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
    onAlbum: () -> Unit, onPlayAlbum: () -> Unit, onShuffle: () -> Unit, onLiked: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val title = album?.name ?: firstPick?.title ?: return
    val artist = album?.subtitle ?: firstPick?.artist.orEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(248.dp + 116.dp * (fontScale - 1f)),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = onAlbum, shape = RoundedCornerShape(32.dp), color = cs.primaryContainer,
            modifier = Modifier.weight(1.5f).fillMaxHeight().squish(0.025f)) {
            Box {
                Artwork(album?.artworkUri ?: firstPick?.artworkUri, Modifier.fillMaxSize(), 0.dp, pixels = ArtworkPlan.LARGE)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.28f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.86f))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 15.dp, end = 60.dp, bottom = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(artist, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Surface(onClick = { haptics.confirm(); onPlayAlbum() }, shape = MaterialShapes.Cookie9Sided.toShape(),
                    color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(48.dp).semantics { contentDescription = "Play $title" }) {
                    Box(contentAlignment = Alignment.Center) { Symbol(Glyph.PLAY, filled = true) }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StartTile("Shuffle all", songCount(librarySongs.size), Glyph.SHUFFLE, MaterialShapes.Sunny.toShape(),
                cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary, Modifier.weight(1f), enabled = librarySongs.isNotEmpty()) {
                haptics.confirm(); onShuffle()
            }
            StartTile("Liked", songCount(favourites.size), Glyph.FAVOURITE, MaterialShapes.Cookie9Sided.toShape(),
                cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary, Modifier.weight(1f), enabled = favourites.isNotEmpty()) {
                haptics.confirm(); onLiked()
            }
        }
    }
}

/** A bright start tile: the icon in an M3E shape, title and a count. */
@Composable
private fun StartTile(title: String, subtitle: String, glyph: Glyph, shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color, onAccent: androidx.compose.ui.graphics.Color,
    modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp), color = container, contentColor = content, modifier = modifier.fillMaxWidth().squish()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(39.dp).clip(shape).background(accent), contentAlignment = Alignment.Center) { Symbol(glyph, color = onAccent, filled = true) }
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
internal fun QuickPicks(songs: List<Track>, currentId: String?, onPlay: (List<Track>, Int) -> Unit, onTrackActions: (Track) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val columns = remember(songs) { songs.chunked(3) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val width = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() } * 0.86f
    LazyRow(state = state, flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        items(columns.size) { c ->
            Column(Modifier.width(width)) {
                columns[c].forEachIndexed { r, track ->
                    val index = c * 3 + r
                    // Tap plays, a long press opens the song's actions (as YouTube Music's quick picks): no
                    // menu button on every row, so the shelf stays calm.
                    val haptics = rememberHaptics()
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onLongClickLabel = "Song actions", onLongClick = { haptics.longPress(); onTrackActions(track) }) { onPlay(songs, index) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Artwork(track.artworkUri, Modifier.size(48.dp), 12.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (track.id.value == currentId || track.matchesPlayback(LocalPlayingTrack.current)) cs.primary else cs.onSurface)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
