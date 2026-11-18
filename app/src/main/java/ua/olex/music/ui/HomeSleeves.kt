@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.ArtworkPlan
import ua.olex.music.model.Track
import kotlin.math.PI
import kotlin.math.sin

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

/** The remainder of the same queue, in swipeable square sleeves with captions outside the image. */
@Composable
internal fun SleeveRail(songs: List<Track>, currentId: String?, onPlay: (Int) -> Unit, onActions: (Track) -> Unit, start: Int = 3) {
    val width = if (start == 0) 124.dp else 146.dp
    val state = rememberLazyListState()
    val cs = MaterialTheme.colorScheme
    LazyRow(Modifier.testTag("home-sleeves"), state = state, flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(songs.size - start, key = { songs[it + start].id.value }) { offset ->
            val i = offset + start
            val track = songs[i]
            val current = track.id.value == currentId || track.matchesPlayback(LocalPlayingTrack.current)
            val haptics = rememberHaptics()
            Column(Modifier.width(width).squish(0.025f).clip(RoundedCornerShape(18.dp)).combinedClickable(role = Role.Button,
                onClickLabel = "Play ${track.title}", onLongClickLabel = "Song actions",
                onLongClick = { haptics.longPress(); onActions(track) }, onClick = { onPlay(i) })
                .semantics { if (current) stateDescription = "Current track" }) {
                Box {
                    Artwork(track.artworkUri, Modifier.size(width), 18.dp, pixels = ArtworkPlan.MEDIUM)
                    if (current) Surface(color = cs.primary, shape = CircleShape, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        Symbol(Glyph.WAVE, size = 16.dp, modifier = Modifier.padding(6.dp), color = cs.onPrimary)
                    }
                }
                Text(track.title, Modifier.padding(start = 3.dp, top = 8.dp), style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, Modifier.padding(start = 3.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Printed shapes echo each mood: smooth contours, sharp pulses or concentric records. */
@Composable
internal fun MoodTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val glyph = moodGlyph(label)
    val calm = glyph in listOf(Glyph.RELAX, Glyph.SLEEP, Glyph.RAIN)
    val lively = glyph in listOf(Glyph.WORKOUT, Glyph.ENERGY, Glyph.PARTY)
    val paper = if (calm) cs.secondaryContainer else if (lively) cs.tertiaryContainer else cs.primaryContainer
    val ink = if (calm) cs.onSecondaryContainer else if (lively) cs.onTertiaryContainer else cs.onPrimaryContainer
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = paper, contentColor = ink, modifier = modifier.squish(0.035f)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(0.dp))) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val tone = ink.copy(alpha = 0.28f)
                    if (calm || lively) {
                        repeat(5) { line ->
                            val path = Path()
                            for (step in 0..48) {
                                val x = step / 48f
                                val phase = x * (if (calm) 1.3 else 2.7) + (label.length % 5) / 5.0
                                val wave = if (calm) sin(phase * PI * 2).toFloat() else (kotlin.math.abs((phase % 1.0) - 0.5) * 4 - 1).toFloat()
                                val y = h * (0.12f + line * 0.18f + wave * 0.28f)
                                if (step == 0) path.moveTo(0f, y) else path.lineTo(x * w, y)
                            }
                            drawPath(path, tone, style = Stroke(3.dp.toPx()))
                        }
                    } else repeat(6) { i ->
                        drawCircle(tone, w * (0.08f + i * 0.085f), center = Offset(w * 0.80f, h * 0.5f), style = Stroke(4.dp.toPx()))
                    }
                }
                Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp).size(42.dp).clip(CircleShape).background(ink),
                    contentAlignment = Alignment.Center) { Symbol(glyph, size = 23.dp, filled = true, color = paper) }
            }
            Text(label, Modifier.padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), style = MaterialTheme.typography.titleMediumEmphasized)
        }
    }
}
