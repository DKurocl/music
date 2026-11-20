package ua.olex.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * A navigation bar icon: Google's own symbol (Material Symbols), outlined at rest and filled when
 * chosen, as Android's navigation bars draw them; a tap plays the glyph's small gesture. Label,
 * indicator and touch target belong to Material.
 */
@Composable
internal fun DestinationIcon(place: Place, selected: Boolean, taps: Int, pressed: Boolean) {
    val glyph = when (place) { Place.HOME -> Glyph.HOME; Place.LIBRARY -> Glyph.QUEUE; Place.SEARCH -> Glyph.SEARCH }
    Symbol(glyph, size = 24.dp, filled = selected, kick = taps, pressed = pressed)
}
