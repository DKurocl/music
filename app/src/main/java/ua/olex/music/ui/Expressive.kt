package ua.olex.music.ui

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

/**
 * The playing song's mark, over its cover: a soft scrim and three rounded bars that dance while the
 * music plays and rest, still, when it is paused.
 */
@Composable
internal fun NowPlayingOverlay(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val playing = LocalIsPlaying.current
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom, modifier = Modifier.size(width = 18.dp, height = 16.dp)) {
            val rest = listOf(0.45f, 0.85f, 0.6f)
            rest.forEachIndexed { i, still ->
                val level = if (playing) androidx.compose.animation.core.rememberInfiniteTransition(label = "bar$i").animateFloat(0.25f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(380 + i * 130, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        androidx.compose.animation.core.RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(i * 120)), label = "level$i").value else still
                Box(Modifier.width(4.dp).fillMaxHeight(level).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color.White))
            }
        }
    }
}
