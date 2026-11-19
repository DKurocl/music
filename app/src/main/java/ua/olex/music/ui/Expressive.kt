package ua.olex.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/** Whether the current track is playing. Provided by the app's root. */
internal val LocalIsPlaying = androidx.compose.runtime.compositionLocalOf { false }

/** Actual playback identity and confirmed files; rows retain the original request for every action. */
internal val LocalPlayingTrack = androidx.compose.runtime.compositionLocalOf<ua.olex.music.model.Track?> { null }
internal val LocalOwnedSources = androidx.compose.runtime.compositionLocalOf<Map<String, ua.olex.music.model.Track>> { emptyMap() }

private val playbackBarHeights = floatArrayOf(3f, 7f, 11f, 14f, 8f)

/** A playback status mark that stays legible over artwork without looking like a transport control. */
@Composable
internal fun NowPlayingOverlay(current: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val playing = LocalIsPlaying.current
    val still = LocalPowerSave.current
    val enter = if (still) EnterTransition.None else fadeIn(tween(105)) + scaleIn(tween(160), initialScale = 0.86f)
    val exit = if (still) ExitTransition.None else fadeOut(tween(120))
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = current && playing, enter = enter, exit = exit) {
            val tint = if (cs.primary.luminance() > cs.primaryContainer.luminance()) cs.primary else cs.primaryContainer
            Canvas(Modifier.size(32.dp).semantics { stateDescription = "Playing" }) {
                drawCircle(Brush.radialGradient(
                    0f to cs.scrim.copy(alpha = 0.82f),
                    0.58f to cs.scrim.copy(alpha = 0.72f),
                    1f to cs.scrim.copy(alpha = 0f),
                    radius = size.minDimension / 2,
                ))
                playbackBarHeights.forEachIndexed { index, height ->
                    val x = center.x + ((index - 2) * 3.8f).dp.toPx()
                    val halfHeight = height.dp.toPx() / 2
                    drawLine(tint, Offset(x, center.y - halfHeight), Offset(x, center.y + halfHeight),
                        strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
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
