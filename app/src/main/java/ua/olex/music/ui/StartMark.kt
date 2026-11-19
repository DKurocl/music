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

/** Quick-start feedback is drawn within the icon's fixed bounds. */
@Composable
internal fun StartMark(shuffle: Boolean, ink: Color, badge: Color, release: () -> Float, pressure: () -> Float) {
    if (shuffle) {
        ShuffleStartMark(ink, badge, release, pressure)
    } else {
        Symbol(Glyph.FAVOURITE, size = 24.dp, filled = true, color = ink, modifier = Modifier.graphicsLayer {
            val press = pressure()
            val settle = release()
            scaleX = 1f - 0.045f * press + 0.09f * settle
            scaleY = 1f - 0.12f * press + 0.13f * settle
        })
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
            val settle = release()
            val press = pressure()
            val expansion = 1.05f * settle - 0.85f * press
            val left = 4f - expansion
            val right = 20f + expansion
            val spread = 5.3f + 0.6f * settle - 0.65f * press
            val crossing = 12f + 2.1f * settle
            scale(size.minDimension / 24f, pivot = Offset.Zero) {
                for (lane in 0..1) {
                    val direction = if (lane == 0) 1f else -1f
                    val entry = 12f - spread * direction
                    val exit = 12f + spread * direction
                    route.rewind()
                    route.moveTo(left, entry)
                    route.cubicTo(crossing - 2.6f, entry, crossing - 1.8f, exit, right, exit)
                    if (lane == 1) drawPath(route, badge, style = clearance)
                    drawPath(route, ink, style = pen)

                    val wing = 2.8f + 0.2f * settle
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
