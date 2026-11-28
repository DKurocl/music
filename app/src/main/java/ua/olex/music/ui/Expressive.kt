package ua.olex.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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

private data class PlaybackBar(val durationMillis: Int, val heights: List<Float>)

private val playbackBars = listOf(
    PlaybackBar(1920, listOf(12f, 20f, 8f, 16f, 12f)),
    PlaybackBar(1680, listOf(24f, 10f, 32f, 16f, 24f)),
    PlaybackBar(2160, listOf(40f, 22f, 34f, 14f, 40f)),
    PlaybackBar(1840, listOf(22f, 34f, 12f, 28f, 22f)),
    PlaybackBar(1760, listOf(10f, 16f, 8f, 20f, 10f)),
)
private const val playbackBarScale = 0.35f

/** A playback status mark that stays legible over artwork without looking like a transport control. */
@Composable
internal fun NowPlayingOverlay(current: Boolean, modifier: Modifier = Modifier, onArtwork: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val playing = LocalIsPlaying.current
    val still = LocalPowerSave.current
    val enter = when {
        still -> EnterTransition.None
        onArtwork -> fadeIn(tween(105))
        else -> fadeIn(tween(105)) + scaleIn(tween(160), initialScale = 0.86f)
    }
    val exit = if (still) ExitTransition.None else fadeOut(tween(120))
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = current && playing, enter = enter, exit = exit) {
            val tint = cs.primary
            val heights = if (still) emptyList() else {
                val transition = rememberInfiniteTransition(label = "Playback mark")
                playbackBars.mapIndexed { index, bar ->
                    val motion = remember(bar) { infiniteRepeatable(keyframes {
                        durationMillis = bar.durationMillis
                        bar.heights.forEachIndexed { frame, height ->
                            height at frame * bar.durationMillis / bar.heights.lastIndex
                        }
                    }) }
                    transition.animateFloat(bar.heights.first(), bar.heights.last(), motion, label = "Playback bar ${index + 1}")
                }
            }
            val bounds = if (onArtwork) Modifier.fillMaxSize() else Modifier.size(28.dp, 23.dp)
            Box(bounds.semantics { stateDescription = "Playing" }.drawWithCache {
                val unit = playbackBarScale.dp.toPx()
                val spacing = 12f * unit
                val width = 6f * unit
                val matte = cs.surfaceContainerHighest.copy(alpha = 0.78f)
                onDrawBehind {
                    if (onArtwork) drawRect(matte)
                    playbackBars.forEachIndexed { index, bar ->
                        val x = center.x + (index - 2) * spacing
                        val height = heights.getOrNull(index)?.value ?: bar.heights.first()
                        val halfHeight = (height * unit - width) / 2
                        val start = Offset(x, center.y - halfHeight)
                        val end = Offset(x, center.y + halfHeight)
                        drawLine(tint, start, end, strokeWidth = width, cap = StrokeCap.Round)
                    }
                }
            })
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

/**
 * Fades what scrolls under a bar into the page over [height]: a gradient of the page colour drawn
 * over the content's top edge (no offscreen layer, so scrolling costs nothing extra).
 */
internal fun Modifier.fadeTop(color: androidx.compose.ui.graphics.Color, height: androidx.compose.ui.unit.Dp = 14.dp): Modifier =
    drawWithContent {
        drawContent()
        val h = height.toPx()
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = 0f, endY = h),
            size = androidx.compose.ui.geometry.Size(size.width, h))
    }
