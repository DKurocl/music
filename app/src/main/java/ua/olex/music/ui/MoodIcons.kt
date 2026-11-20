package ua.olex.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** One finite tap gesture per mood. A phase of either zero or one draws the resting symbol. */
internal fun moodDuration(glyph: Glyph): Int = when (glyph) {
    Glyph.SLEEP, Glyph.RELAX -> 500
    Glyph.PARTY, Glyph.FAVOURITE -> 460
    Glyph.HAPPY, Glyph.RAIN -> 500
    Glyph.ENERGY, Glyph.FOCUS -> 320
    else -> 380
}

/** A mood's mark: Google's symbol in the chip's ink. */
@Composable
internal fun MoodMark(glyph: Glyph, ink: Color, modifier: Modifier, phase: () -> Float) {
    // Google's own symbol for each mood (Material Symbols); a chosen mood gives one small pop.
    androidx.compose.foundation.layout.Box(modifier.then(androidx.compose.ui.Modifier.graphicsLayer {
        val t = phase().coerceIn(0f, 1f)
        val pop = kotlin.math.sin(t * Math.PI).toFloat()
        scaleX = 1f + 0.14f * pop; scaleY = scaleX
    }), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Symbol(glyph, size = 21.dp, color = ink)
    }
}
