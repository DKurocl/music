package ua.olex.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin

/** One finite tap gesture per mood. A phase of either zero or one draws the resting symbol. */
internal fun moodDuration(glyph: Glyph): Int = when (glyph) {
    Glyph.SLEEP, Glyph.RELAX -> 500
    Glyph.PARTY, Glyph.FAVOURITE -> 460
    Glyph.HAPPY, Glyph.RAIN -> 500
    Glyph.ENERGY, Glyph.FOCUS -> 320
    else -> 380
}

private fun pulse(time: Float, start: Float = 0f, end: Float = 1f): Float {
    if (time <= start || time >= end) return 0f
    val t = (time - start) / (end - start)
    val s = sin(t * PI).toFloat()
    return s * s
}

private fun DrawScope.line(ink: Color, x: Float, y: Float, endX: Float, endY: Float, width: Float = 1.8f) =
    drawLine(ink, Offset(x, y), Offset(endX, endY), strokeWidth = width, cap = StrokeCap.Round)

private fun Path.spark(x: Float, y: Float, radius: Float) {
    moveTo(x, y - radius)
    quadraticTo(x + radius * 0.12f, y - radius * 0.12f, x + radius, y)
    quadraticTo(x + radius * 0.12f, y + radius * 0.12f, x, y + radius)
    quadraticTo(x - radius * 0.12f, y + radius * 0.12f, x - radius, y)
    quadraticTo(x - radius * 0.12f, y - radius * 0.12f, x, y - radius)
    close()
}

/** All marks share a 24-unit grid, round stroke ends and the chip's wallpaper-derived ink. */
@Composable
internal fun MoodMark(glyph: Glyph, ink: Color, modifier: Modifier, phase: () -> Float) {
    val path = remember(glyph) { Path() }
    val pen = remember { Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    Canvas(modifier) {
        val t = phase().coerceIn(0f, 1f)
        val swing = pulse(t)
        path.reset()
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            when (glyph) {
                Glyph.ENERGY -> {
                    translate(top = -1.6f * pulse(t, 0f, 0.68f)) {
                        path.moveTo(13.8f, 2f); path.lineTo(4.8f, 13.4f); path.lineTo(10.8f, 13.4f)
                        path.lineTo(9.2f, 22f); path.lineTo(19.5f, 10.2f); path.lineTo(13.2f, 10.2f); path.close()
                        drawPath(path, ink)
                    }
                    val flash = pulse(t, 0.13f, 0.85f)
                    if (flash > 0f) line(ink.copy(alpha = flash), 17.2f, 5.4f, 18.6f + flash, 4f - flash, 1.6f)
                }
                Glyph.HAPPY -> {
                    rotate(-7f * swing, Offset(12f, 12f)) {
                        drawCircle(ink, 8.5f, Offset(12f, 12f), style = pen)
                        drawCircle(ink, 1.1f, Offset(8.8f, 10.1f))
                        val wink = pulse(t, 0.12f, 0.72f)
                        drawCircle(ink, 1.1f * (1f - wink), Offset(15.2f, 10.1f))
                        if (wink > 0f) line(ink.copy(alpha = wink), 14.1f, 10.1f, 16.3f, 10.1f, 2f)
                        path.moveTo(8.1f, 14f - swing * 0.4f)
                        path.quadraticTo(12f, 19.2f + swing, 16f, 14f - swing * 0.4f)
                        drawPath(path, ink, style = pen)
                    }
                }
                Glyph.RELAX -> {
                    val root = Offset(12f, 19.5f)
                    for (angle in listOf(-44f - swing * 17f, 44f + swing * 17f, 0f)) {
                        rotate(angle, root) {
                            path.reset(); path.moveTo(root.x, root.y)
                            path.cubicTo(6.5f, 15.5f, 8.3f, 9.8f, 12f, 5.1f)
                            path.cubicTo(16.1f, 10.6f, 17.1f, 15.4f, root.x, root.y)
                            drawPath(path, ink, style = pen)
                        }
                    }
                }
                Glyph.PARTY -> {
                    rotate(-5f * pulse(t, 0f, 0.43f), Offset(4f, 20f)) {
                        path.moveTo(3.5f, 20.8f); path.lineTo(7.5f, 9.3f); path.lineTo(15.3f, 16.8f); path.close()
                        drawPath(path, ink, style = pen)
                        line(ink, 6f, 14.1f, 10.1f, 18.1f, 1.5f)
                    }
                    val burst = pulse(t, 0.12f, 1f)
                    line(ink, 11.9f, 8.6f - burst, 13f, 5.2f - burst * 2)
                    drawCircle(ink, 1.25f, Offset(17.6f + burst * 2, 7.1f - burst * 2))
                    line(ink, 15.7f + burst, 12f, 18.8f + burst * 2, 10.7f)
                }
                Glyph.WORKOUT -> rotate(-18f - swing * 10f, Offset(12f, 12f)) {
                    translate(top = -2.3f * swing) {
                        line(ink, 4f, 12f, 20f, 12f, 2.2f)
                        for (x in listOf(5.5f, 16f)) drawRoundRect(ink, Offset(x, 6.8f), Size(2.7f, 10.4f), CornerRadius(1.3f))
                        for (x in listOf(2.5f, 19f)) drawRoundRect(ink, Offset(x, 9f), Size(2.4f, 6f), CornerRadius(1.2f))
                    }
                }
                Glyph.COMMUTE -> translate(left = 1.6f * swing) {
                    drawRoundRect(ink, Offset(5.1f, 3.2f), Size(13.5f, 14.1f), CornerRadius(3.4f), style = pen)
                    line(ink, 5.7f, 11.1f, 18f, 11.1f)
                    drawCircle(ink, 1f, Offset(8.9f, 14.3f)); drawCircle(ink, 1f, Offset(15.1f, 14.3f))
                    line(ink, 8.4f, 17.7f, 5.6f, 21f); line(ink, 15.6f, 17.7f, 18.4f, 21f)
                    line(ink, 7.3f, 19.1f, 16.8f, 19.1f, 1.4f)
                }
                Glyph.FAVOURITE -> {
                    val beat = pulse(t, 0f, 0.52f) * 0.16f + pulse(t, 0.48f, 1f) * 0.09f
                    scale(1f + beat, Offset(12f, 12f)) {
                        path.moveTo(12f, 20f); path.cubicTo(10f, 18.6f, 3.5f, 14f, 3.5f, 9f)
                        path.cubicTo(3.5f, 3.3f, 9.2f, 2.7f, 12f, 7f)
                        path.cubicTo(14.6f, 2.8f, 20.5f, 3.4f, 20.5f, 9f)
                        path.cubicTo(20.5f, 13.7f, 14.4f, 18.7f, 12f, 20f); path.close()
                        drawPath(path, ink)
                    }
                }
                Glyph.RAIN -> {
                    path.moveTo(7f, 14f); path.cubicTo(1.9f, 14f, 2.5f, 7.7f, 7f, 7.4f)
                    path.cubicTo(8f, 1.9f, 16.1f, 1.9f, 17.7f, 7.5f)
                    path.cubicTo(22.4f, 7.4f, 22.5f, 14f, 17.3f, 14f); path.close()
                    drawPath(path, ink, style = pen)
                    val left = 2f * pulse(t, 0f, 0.7f)
                    val right = 2f * pulse(t, 0.2f, 1f)
                    line(ink, 9.5f, 16.8f + left, 8.1f, 19f + left)
                    line(ink, 15.6f, 16.8f + right, 14.2f, 19f + right)
                }
                Glyph.FOCUS -> {
                    val inset = 2.1f * swing
                    repeat(4) { i -> rotate(i * 90f, Offset(12f, 12f)) {
                        path.reset(); path.moveTo(4f + inset, 9f + inset); path.lineTo(4f + inset, 5.4f + inset)
                        path.quadraticTo(4f + inset, 4f + inset, 5.4f + inset, 4f + inset); path.lineTo(9f + inset, 4f + inset)
                        drawPath(path, ink, style = pen)
                    } }
                    drawCircle(ink, 2.2f - swing * 0.5f, Offset(12f, 12f))
                }
                Glyph.SLEEP -> {
                    rotate(-12f * swing, Offset(12f, 12f)) {
                        path.moveTo(13f, 3.3f); path.cubicTo(6.3f, 4f, 3.4f, 11.4f, 6.1f, 16.8f)
                        path.cubicTo(9.1f, 22.9f, 17.4f, 22.5f, 20.6f, 16.3f)
                        path.cubicTo(12.4f, 18.2f, 7.4f, 9.5f, 13f, 3.3f); path.close(); drawPath(path, ink)
                    }
                    path.reset(); path.spark(17.6f, 7.6f, 2.7f + pulse(t, 0.18f, 1f)); drawPath(path, ink)
                }
                Glyph.MIX -> {
                    rotate(swing * 18f, Offset(10.2f, 13.3f)) {
                        path.spark(10.2f, 13.3f, 8.2f); drawPath(path, ink)
                    }
                    path.reset(); path.spark(18f, 5.7f, 3.5f + swing * 0.6f); drawPath(path, ink)
                }
                else -> {
                    // Provider-defined genre filters use a single musical note.
                    translate(top = -swing * 1.5f) {
                        drawOval(ink, Offset(5f, 14.2f), Size(8f, 5.8f))
                        line(ink, 12.1f, 17f, 12.1f, 4.3f); line(ink, 12.1f, 4.3f, 17.6f, 3.6f, 2.4f)
                    }
                }
            }
        }
    }
}
