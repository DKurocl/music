@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Motion stays within the navigation item. Its label, indicator and touch target belong to Material. */
@Composable
internal fun DestinationIcon(place: Place, selected: Boolean, taps: Int, pressed: Boolean) {
    val still = LocalPowerSave.current
    val scheme = MaterialTheme.motionScheme
    val settle = scheme.fastSpatialSpec<Float>()
    val action = remember { Animatable(0f) }
    LaunchedEffect(taps, selected, still) {
        when {
            still -> action.snapTo(0f)
            !selected -> action.animateTo(0f, settle)
            taps > 0 -> {
                action.animateTo(1f, tween(125))
                action.animateTo(0f, settle)
            }
        }
    }
    val ink by animateFloatAsState(if (selected) 1f else 0f,
        if (still) snap() else scheme.fastEffectsSpec(), label = "selected destination")
    val press by animateFloatAsState(if (pressed && !still) 0.85f else 1f, settle, label = "destination press")
    val color = LocalContentColor.current
    val path = remember { Path() }
    Canvas(Modifier.size(24.dp).graphicsLayer { scaleX = press; scaleY = press }) {
        val unit = size.minDimension / 24f
        val t = action.value
        val weight = 1.8f + 0.55f * ink
        withTransform({ scale(unit, unit, Offset.Zero) }) {
            when (place) {
                Place.HOME -> {
                    // The roof rises while the walls stay planted on the baseline.
                    path.reset()
                    path.moveTo(3.8f, 10.5f - t)
                    path.lineTo(12f, 4.1f - 2.5f * t)
                    path.lineTo(20.2f, 10.5f - t)
                    path.lineTo(20.2f, 20f)
                    path.lineTo(14.4f, 20f)
                    path.lineTo(14.4f, 14f - t)
                    path.lineTo(9.6f, 14f - t)
                    path.lineTo(9.6f, 20f)
                    path.lineTo(3.8f, 20f)
                    path.close()
                    drawPath(path, color, alpha = ink)
                    drawPath(path, color, style = Stroke(weight, join = StrokeJoin.Round))
                }
                Place.LIBRARY -> {
                    // The three lines fan out around the music note.
                    for (line in 0..2) {
                        val y = 5.5f + line * 4.5f
                        val width = 14f - 2f * line + (line - 1) * 3f * t
                        drawLine(color, Offset(3f, y), Offset(width, y), weight, StrokeCap.Round)
                    }
                    val top = 9.3f - 1.8f * t
                    drawLine(color, Offset(17.2f, 18.2f), Offset(17.2f, top), weight, StrokeCap.Round)
                    drawLine(color, Offset(17.2f, top), Offset(20.7f, top - 0.4f), weight, StrokeCap.Round)
                    drawCircle(color, 2.75f, Offset(15.4f, 18.5f))
                }
                Place.SEARCH -> {
                    // The lens widens and its handle swings about the rim, like a small focus adjustment.
                    val centre = Offset(10.1f, 10f)
                    val radius = 6.2f + 0.95f * t
                    val joint = centre + Offset(radius * 0.7f, radius * 0.7f)
                    rotate(-23f * t, joint) {
                        drawLine(color, joint, joint + Offset(5.3f, 5.3f), weight + 0.4f, StrokeCap.Round)
                    }
                    drawCircle(color, radius, centre, style = Stroke(weight))
                }
            }
        }
    }
}
