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
import androidx.compose.ui.graphics.drawscope.clipRect
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
    // Google's shuffle symbol, pressed and released like the heart beside it.
    Symbol(Glyph.SHUFFLE, size = 24.dp, color = ink, modifier = Modifier.graphicsLayer {
        val press = pressure()
        val open = release()
        scaleX = 1f - 0.08f * press + 0.22f * open
        scaleY = 1f - 0.13f * press + 0.25f * open
        rotationZ = 180f * open
    })
}
