package ua.olex.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * A navigation bar icon as Pixel's own apps animate their tabs: when its place is chosen the symbol
 * plays its own gesture (the house hops and lands, the queue's lines slide in and its note jumps, the
 * lens looks around) and fills in on the font's FILL axis, while Material's pill grows behind it.
 * Pressing presses it in; nothing moves in Battery Saver.
 */
@Composable
internal fun DestinationIcon(place: Place, selected: Boolean, pressed: Boolean) {
    val glyph = when (place) { Place.HOME -> MotionGlyph.HOME; Place.LIBRARY -> MotionGlyph.QUEUE; Place.SEARCH -> MotionGlyph.SEARCH }
    MotionSymbol(glyph, selected, size = 24.dp, pressed = pressed)
}
