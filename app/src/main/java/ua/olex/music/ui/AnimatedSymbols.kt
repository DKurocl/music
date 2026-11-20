package ua.olex.music.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private val svgCenter = Offset(480f, -480f)

private fun symbolPath(data: String): Path = PathParser().parsePathString(data).toPath()

/** Shape, press compression and selected color are drawn inside a stationary touch target. */
@Composable
internal fun Modifier.playerControlContainer(selected: Boolean, pressed: Boolean, selectedCorner: Float): Modifier {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val still = LocalPowerSave.current
    val active = animateFloatAsState(if (selected) 1f else 0f,
        if (still) snap() else motion.fastSpatialSpec(), label = "Player control selection")
    val push = animateFloatAsState(if (pressed) 1f else 0f,
        if (still) snap() else motion.fastSpatialSpec(), label = "Player control press")
    return drawBehind {
        val strength = active.value.coerceIn(0f, 1f)
        val pressure = push.value.coerceIn(0f, 1f)
        val visibility = maxOf(strength, pressure * 0.72f)
        if (visibility > 0f) {
            val radius = (0.5f + (selectedCorner - 0.5f) * strength) * (1f - pressure * 0.47f)
            val inset = 2.dp.toPx() + size.minDimension * (0.27f * (1f - visibility) + 0.055f * pressure)
            val width = size.width - 2f * inset
            val height = size.height - 2f * inset
            drawRoundRect(
                lerp(colors.surfaceContainerHighest, colors.secondaryContainer, strength).copy(alpha = visibility),
                topLeft = Offset(inset, inset), size = Size(width, height),
                cornerRadius = CornerRadius(minOf(width, height) * radius),
            )
        }
    }
}

/** Material Symbols Rounded SVGs use a 960-unit viewport with the baseline at zero. */
@Composable
internal fun PlayerModeSymbol(shuffle: Boolean, on: Boolean, one: Boolean, kick: Int, pressed: Boolean, color: () -> Color) {
    val still = LocalPowerSave.current
    val motion = MaterialTheme.motionScheme
    val pressure by animateFloatAsState(if (pressed && !still) 0.88f else 1f,
        if (still) snap() else motion.fastSpatialSpec(), label = "Mode icon pressure")
    // A toggle in flight retargets the same crossover. The two ends share exactly the official outline.
    val crossover by animateFloatAsState(if (shuffle && on) 1f else 0f,
        if (still) snap() else motion.defaultSpatialSpec(), label = "Shuffle crossover")
    // The arrows are half-turn symmetric. A second tap adds travel without replacing or resetting either arrow.
    val turn by animateFloatAsState(if (shuffle) 0f else kick * 180f,
        if (still) snap() else motion.defaultSpatialSpec(), label = "Repeat arrows")
    val numeral by animateFloatAsState(if (one) 1f else 0f,
        if (still) snap() else motion.fastSpatialSpec(), label = "Repeat one numeral")

    Canvas(Modifier.size(24.dp).graphicsLayer { scaleX = pressure; scaleY = pressure }) {
        val unit = size.minDimension / 960f
        val ink = color()
        scale(unit, unit, pivot = Offset.Zero) {
            translate(top = 960f) {
                if (shuffle) {
                    val phase = crossover.coerceIn(0f, 1f)
                    val weave = 4f * phase * (1f - phase)
                    rotate(12f * weave, svgCenter) {
                        translate(-28f * weave, -28f * weave) { drawPath(ModeOutlines.shuffleTail, ink) }
                        translate(28f * weave, 28f * weave) { drawPath(ModeOutlines.shuffleUnder, ink) }
                    }
                    rotate(-11f * weave, svgCenter) {
                        translate(16f * weave, -16f * weave) { drawPath(ModeOutlines.shuffleOver, ink) }
                    }
                } else {
                    rotate(turn % 180f, svgCenter) { drawPath(ModeOutlines.repeat, ink) }
                    val digit = numeral.coerceIn(0f, 1.12f)
                    if (digit > 0f) scale(digit, digit, svgCenter) {
                        drawPath(ModeOutlines.one, ink, alpha = digit.coerceAtMost(1f))
                    }
                }
            }
        }
    }
}

// Google Material Symbols Rounded, shuffle/repeat/repeat_one, default/24px (Apache-2.0).
// https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/
private object ModeOutlines {
    val shuffleUnder = symbolPath("M600-160q-17 0-28.5-11.5T560-200q0-17 11.5-28.5T600-240h64l-99-99q-12-12-11.5-28.5T566-396q12-12 28.5-12t28.5 12l97 98v-62q0-17 11.5-28.5T760-400q17 0 28.5 11.5T800-360v160q0 17-11.5 28.5T760-160H600Z")
    val shuffleOver = symbolPath("M172-172q-11-11-11-28t11-28l492-492h-64q-17 0-28.5-11.5T560-760q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760v160q0 17-11.5 28.5T760-560q-17 0-28.5-11.5T720-600v-64L228-172q-11 11-28 11t-28-11Z")
    val shuffleTail = symbolPath("M171-732q-11-11-11-28t11-28q11-11 27.5-11t28.5 11l168 167q11 11 11.5 27.5T395-565q-11 11-28 11t-28-11L171-732Z")
    val repeat = symbolPath("m274-200 34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z")
    val one = symbolPath("M460-540h-30q-13 0-21.5-8.5T400-570q0-13 8.5-21.5T430-600h50q17 0 28.5 11.5T520-560v170q0 13-8.5 21.5T490-360q-13 0-21.5-8.5T460-390v-150Z")
}
