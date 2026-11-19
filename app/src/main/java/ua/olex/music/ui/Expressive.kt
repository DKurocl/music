package ua.olex.music.ui

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.olex.music.model.Track

/**
 * An empty or waiting page that still feels designed: the subject's icon in a large M3E shape, a
 * short title, one sentence, and the action that fills the page (if there is one).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EmptyState(glyph: Glyph, title: String, body: String, modifier: Modifier = Modifier, shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
    loading: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) androidx.compose.material3.LoadingIndicator(Modifier.size(96.dp))
        else Box(Modifier.size(112.dp).clip(shape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Symbol(glyph, size = 48.dp, color = cs.onPrimaryContainer, filled = true)
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp), content = actions)
    }
}

/** A playlist's cover: the first four different covers as a 2×2 mosaic, or one, or the playlist icon. */
@Composable
internal fun PlaylistCover(tracks: List<Track>, size: Dp, corner: Dp = 16.dp) {
    val cs = MaterialTheme.colorScheme
    val arts = tracks.mapNotNull { it.artworkUri }.distinct().take(4)
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(corner)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
        when {
            arts.size >= 4 -> Column {
                Row { Artwork(arts[0], Modifier.size(size / 2), 0.dp); Artwork(arts[1], Modifier.size(size / 2), 0.dp) }
                Row { Artwork(arts[2], Modifier.size(size / 2), 0.dp); Artwork(arts[3], Modifier.size(size / 2), 0.dp) }
            }
            arts.isNotEmpty() -> Artwork(arts[0], Modifier.fillMaxSize(), 0.dp)
            else -> Symbol(Glyph.PLAYLIST, size = size * 0.4f, color = cs.onTertiaryContainer, filled = true)
        }
    }
}

/** Whether music is playing now: the playing row's bars move only then. Provided by the app's root. */
internal val LocalIsPlaying = androidx.compose.runtime.compositionLocalOf { false }

/** Actual playback identity and confirmed files; rows retain the original request for every action. */
internal val LocalPlayingTrack = androidx.compose.runtime.compositionLocalOf<ua.olex.music.model.Track?> { null }
internal val LocalOwnedSources = androidx.compose.runtime.compositionLocalOf<Map<String, ua.olex.music.model.Track>> { emptyMap() }

/** A status mark for the active recording, drawn from one phase without observing audio samples. */
@Composable
internal fun NowPlayingOverlay(modifier: Modifier = Modifier, alignment: Alignment = Alignment.BottomEnd) {
    val cs = MaterialTheme.colorScheme
    val playing = LocalIsPlaying.current
    val phase = if (playing && !LocalPowerSave.current) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "Playing track").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
            label = "Five bars")
    } else null
    val ink = if (playing) cs.primary else cs.onSurfaceVariant
    Box(modifier) {
        Box(Modifier.align(alignment).padding(2.dp).size(width = 23.dp, height = 18.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(cs.surfaceContainerHighest)
            .semantics { stateDescription = if (playing) "Playing" else "Paused" }, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(width = 16.dp, height = 11.dp)) {
                val step = size.width / 5
                val stroke = step * 0.64f
                val cycle = (phase?.value ?: 0f) * 2.0 * Math.PI
                for (i in 0 until 5) {
                    val crest = (1.0 + kotlin.math.sin(cycle * (1 + i % 2) + i * 1.9)) / 2
                    val envelope = 1f - kotlin.math.abs(i - 2) * 0.14f
                    val half = if (playing) ((size.height - stroke) * envelope * (0.18 + 0.82 * crest) / 2).toFloat() else stroke * 0.15f
                    val x = (i + 0.5f) * step
                    drawLine(ink, androidx.compose.ui.geometry.Offset(x, center.y - half),
                        androidx.compose.ui.geometry.Offset(x, center.y + half), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

/** Fades the content out over its last [width] at the end, for a row that scrolls past the screen. */
internal fun Modifier.fadeEnd(width: androidx.compose.ui.unit.Dp = 32.dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx()
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent),
                startX = size.width - w, endX = size.width), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
