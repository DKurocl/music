package ua.olex.music.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Motion stays inside the mark. Only the draw pass observes the press and release clocks. */
@Composable
internal fun StartMark(shuffle: Boolean, ink: Color, badge: Color, phase: () -> Float, pressure: () -> Float) {
    Spacer(Modifier.size(29.dp).drawWithCache {
        val pen = Stroke(1.9f, cap = StrokeCap.Round)
        val crossing = Stroke(4.7f, cap = StrokeCap.Round)
        fun lane(start: Float, end: Float) = Path().apply {
            moveTo(3f, start)
            cubicTo(9.5f, start, 13.5f, end, 20f, end)
        }
        val routes = listOf(lane(7f, 17f), lane(17f, 7f))
        val measures = routes.map { PathMeasure().apply { setPath(it, false) } }
        val segment = Path()
        val heart = Path().apply {
            moveTo(12f, 19.8f)
            cubicTo(10.9f, 19.1f, 4f, 14.3f, 4f, 9.5f)
            cubicTo(4f, 5.2f, 9.5f, 3.8f, 12f, 7.7f)
            cubicTo(14.5f, 3.8f, 20f, 5.2f, 20f, 9.5f)
            cubicTo(20f, 14.3f, 13.1f, 19.1f, 12f, 19.8f)
            close()
        }
        onDrawBehind {
            val t = phase()
            val lift = sin(PI * t).toFloat()
            scale(size.minDimension / 24f, pivot = Offset.Zero) {
                if (shuffle) {
                    // A narrow gap travels down each route in turn, like two passing tape splices.
                    routes.forEachIndexed { i, path ->
                        if (i == 1) drawPath(path, badge, style = crossing)
                        drawPath(path, ink, style = pen)
                        val progress = ((t - i * 0.10f) / 0.88f).coerceIn(0f, 1f)
                        val measure = measures[i]
                        val head = progress * (measure.length + 5f)
                        segment.rewind()
                        if (measure.getSegment((head - 5f).coerceAtLeast(0f), head.coerceAtMost(measure.length), segment))
                            drawPath(segment, badge, style = pen)
                        val y = if (i == 0) 17f else 7f
                        val reach = 20f + sin(PI * progress).toFloat() * 1.4f - pressure() * 0.6f
                        drawLine(ink, Offset(reach - 2.8f, y - 2.8f), Offset(reach, y), pen.width, StrokeCap.Round)
                        drawLine(ink, Offset(reach, y), Offset(reach - 2.8f, y + 2.8f), pen.width, StrokeCap.Round)
                    }
                } else {
                    // Press seats the heart. Release lifts it far enough to reveal a second impression.
                    val dy = pressure() * 1.5f - lift * 2.7f
                    if (lift > 0f) translate(top = lift * 1.8f) {
                        drawPath(heart, ink, alpha = lift * 0.45f, style = Stroke(1.15f))
                    }
                    translate(top = dy) {
                        drawPath(heart, badge, style = crossing)
                        drawPath(heart, ink)
                    }
                }
            }
        }
    })
}
