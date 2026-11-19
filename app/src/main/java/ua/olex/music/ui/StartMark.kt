package ua.olex.music.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Quick-start feedback is drawn within the icon's fixed bounds. */
@Composable
internal fun StartMark(shuffle: Boolean, ink: Color, badge: Color, release: () -> Float, pressure: () -> Float) {
    if (shuffle) {
        ShuffleStartMark(ink, badge, release, pressure)
    } else {
        Symbol(Glyph.FAVOURITE, size = 24.dp, filled = true, color = ink,
            fillFraction = { 1f - release() },
            modifier = Modifier.graphicsLayer {
                val press = pressure()
                val open = release()
                scaleX = 1f - 0.08f * press + 0.22f * open
                scaleY = 1f - 0.13f * press + 0.25f * open
            },
        )
    }
}

@Composable
private fun ShuffleStartMark(ink: Color, badge: Color, release: () -> Float, pressure: () -> Float) {
    Spacer(Modifier.size(25.dp).drawWithCache {
        val pen = Stroke(2.15f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val clearance = Stroke(4.6f, cap = StrokeCap.Round)
        val route = Path()
        val arrow = Path()

        onDrawBehind {
            val open = release()
            val press = pressure()
            val left = 3.8f + 1.2f * press
            val spread = 5.3f - 0.8f * press
            // Offset the tips while they pass each other so the two arrowheads stay distinct.
            val stagger = 2.9f * sin(PI * open).toFloat()
            scale(size.minDimension / 24f, pivot = Offset.Zero) {
                for (lane in 0..1) {
                    val side = if (lane == 0) 1f else -1f
                    val entry = 12f - spread * side
                    val exit = 12f + spread * side * (1f - 2f * open)
                    val right = 20f + side * stagger - 0.8f * press
                    route.rewind()
                    route.moveTo(left, entry)
                    route.cubicTo(left + 6f, entry, right - 6f, exit, right, exit)
                    if (lane == 1) drawPath(route, badge, style = clearance)
                    drawPath(route, ink, style = pen)

                    val wing = 2.8f
                    arrow.rewind()
                    arrow.moveTo(right - wing, exit - wing)
                    arrow.lineTo(right, exit)
                    arrow.lineTo(right - wing, exit + wing)
                    drawPath(arrow, ink, style = pen)
                }
            }
        }
    })
}
