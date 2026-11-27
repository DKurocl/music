package ua.olex.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A navigation bar icon as Google's own apps animate them: Google's symbol, outlined at rest; when
 * its place is chosen the symbol fills in and lands with one springy pop while Material's pill grows
 * out from behind it. Pressing presses the glyph in a little. Nothing moves on a tap that changes
 * nothing, and nothing moves at all in Battery Saver.
 */
@Composable
internal fun DestinationIcon(place: Place, selected: Boolean, pressed: Boolean) {
    val glyph = when (place) { Place.HOME -> Glyph.HOME; Place.LIBRARY -> Glyph.QUEUE; Place.SEARCH -> Glyph.SEARCH }
    val still = LocalPowerSave.current
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, still) {
        if (selected && !seen && !still) {
            pop.snapTo(0.7f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 480f))
        }
        seen = selected
    }
    Symbol(glyph, size = 24.dp, filled = selected, pressed = pressed,
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
}
