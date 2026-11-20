package ua.olex.music.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private val svgCenter = Offset(480f, -480f)

private fun symbolPath(data: String): Path = PathParser().parsePathString(data).toPath()

@Composable
internal fun playerControlSelection(selected: Boolean): State<Float> {
    val target = if (selected) 1f else 0f
    return if (LocalPowerSave.current) rememberUpdatedState(target)
        else animateFloatAsState(target, MaterialTheme.motionScheme.fastSpatialSpec(), label = "Player control selection")
}

@Composable
internal fun playerControlPress(pressed: Boolean): State<Float> {
    val target = if (pressed) 1f else 0f
    return if (LocalPowerSave.current) rememberUpdatedState(target)
        else animateFloatAsState(target, MaterialTheme.motionScheme.fastSpatialSpec(), label = "Player control press")
}

private fun plate(center: Offset, full: Float, selected: Float, pressed: Float): RoundRect {
    val expansion = maxOf(selected, pressed * 0.72f).coerceIn(0f, 1f).takeIf { it >= 0.012f } ?: 0f
    val side = full * expansion * (1f - 0.1f * pressed)
    val half = side / 2f
    val corner = (0.5f - 0.18f * selected) * (1f - pressed * 0.45f)
    return RoundRect(Rect(center.x - half, center.y - half, center.x + half, center.y + half), CornerRadius(side * corner))
}

/** A single bounded tonal shape supplies visual press feedback inside the fixed hit target. */
internal fun Modifier.playerControlContainer(active: State<Float>, push: State<Float>, focused: Boolean, fill: Color, focus: Color): Modifier =
    drawBehind {
        val shape = plate(center, 44.dp.toPx(), active.value.coerceIn(0f, 1f), push.value.coerceIn(0f, 1f))
        if (shape.width > 0f) drawRoundRect(fill, topLeft = Offset(shape.left, shape.top),
            size = Size(shape.width, shape.height), cornerRadius = shape.topLeftCornerRadius)
        if (focused) {
            val inset = 1.5.dp.toPx()
            drawRoundRect(focus, topLeft = Offset(inset, inset), size = Size(size.width - 2f * inset, size.height - 2f * inset),
                cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
    }

/** Material Symbols Rounded SVGs use a 960-unit viewport with the baseline at zero. */
@Composable
internal fun PlayerModeSymbol(
    shuffle: Boolean, on: Boolean, one: Boolean, kick: Int,
    active: State<Float>, push: State<Float>, offInk: Color, onInk: Color,
) {
    val still = LocalPowerSave.current
    val motion = MaterialTheme.motionScheme
    val crossover by animateFloatAsState(if (shuffle && on) 1f else 0f,
        if (still) snap() else motion.fastSpatialSpec(), label = "Shuffle crossover")
    val turn = remember { Animatable(0f, visibilityThreshold = 0.003f) }
    LaunchedEffect(kick, still) {
        if (shuffle || still || kick == 0) turn.snapTo(0f)
        else turn.animateTo(kotlin.math.floor(turn.value + 0.07f) + 1f, motion.fastSpatialSpec())
    }
    val numeral by animateFloatAsState(if (one) 1f else 0f,
        if (still) snap() else motion.fastSpatialSpec(), label = "Repeat one numeral")
    val mask = remember { Path() }
    val segments = remember { Path() }
    val rail = remember {
        PathMeasure().apply {
            setPath(symbolPath("M686-720Q720-720 720-686V-274Q720-240 686-240H274Q240-240 240-274V-686Q240-720 274-720Z"), true)
        }
    }

    Canvas(Modifier.size(24.dp).graphicsLayer {
        scaleX = 1f - 0.12f * push.value.coerceIn(0f, 1f)
        scaleY = scaleX
    }) {
        val unit = size.minDimension / 960f
        val pressure = push.value.coerceIn(0f, 1f)
        val selected = active.value.coerceIn(0f, 1f)
        val shape = plate(center, 44.dp.toPx() / (1f - 0.12f * pressure), selected, pressure)
        val phase = if (still) 0f else turn.value % 1f
        val moving = phase > 0.003f && phase < 0.997f
        val headDistance = phase * rail.length / 2f
        if (!shuffle && moving) {
            segments.reset()
            repeat(2) { index ->
                val tip = headDistance + index * rail.length / 2f
                val tail = tip - 590f
                if (tail < 0f) {
                    rail.getSegment(rail.length + tail, rail.length, segments, startWithMoveTo = true)
                    rail.getSegment(0f, tip, segments, startWithMoveTo = false)
                } else rail.getSegment(tail, tip, segments, startWithMoveTo = true)
            }
        }
        fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSymbol(ink: Color) {
            scale(unit, unit, pivot = Offset.Zero) {
                translate(top = 960f) {
                    if (shuffle) {
                        val position = if (still) (if (on) 1f else 0f) else crossover.coerceIn(0f, 1f)
                        val weave = if (position <= 0.004f || position >= 0.996f) 0f else 4f * position * (1f - position)
                        rotate(10f * weave, svgCenter) {
                            translate(-18f * weave, -18f * weave) { drawPath(ModeOutlines.shuffleTail, ink) }
                            translate(18f * weave, 18f * weave) { drawPath(ModeOutlines.shuffleUnder, ink) }
                        }
                        rotate(-10f * weave, svgCenter) {
                            translate(14f * weave, -14f * weave) { drawPath(ModeOutlines.shuffleOver, ink) }
                        }
                    } else {
                        if (moving) {
                            drawPath(segments, ink, style = Stroke(80f, cap = StrokeCap.Round))
                            repeat(2) { index ->
                                val distance = headDistance + index * rail.length / 2f
                                val tip = rail.getPosition(distance)
                                val tangent = rail.getTangent(distance)
                                val angle = Math.toDegrees(kotlin.math.atan2(tangent.y, tangent.x).toDouble()).toFloat()
                                translate(tip.x, tip.y) { rotate(angle, Offset.Zero) { drawPath(ModeOutlines.repeatHead, ink) } }
                            }
                        } else drawPath(ModeOutlines.repeat, ink)
                        val digit = if (still) (if (one) 1f else 0f) else numeral.coerceIn(0f, 1.12f)
                        if (digit > 0.012f) {
                            val scale = 0.8f + 0.2f * digit
                            scale(scale, scale, svgCenter) { drawPath(ModeOutlines.one, ink) }
                        }
                    }
                }
            }
        }
        if (shape.width <= 0f) drawSymbol(offInk) else {
            mask.reset()
            mask.addRoundRect(shape)
            clipPath(mask, androidx.compose.ui.graphics.ClipOp.Difference) { drawSymbol(offInk) }
            clipPath(mask) { drawSymbol(onInk) }
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
    val repeatHead = symbolPath("M0 40l-34 34q-12 12-11.5 28t11.5 28q12 12 28.5 11.5t28.5-12.5l103-103q6-6 8.5-13t2.5-15q0-8-2.5-15t-8.5-13l-103-103q-12-12-28.5-11.5T-34-130q-12 12-11.5 28t11.5 28L0-40H-45v80Z")
    val one = symbolPath("M460-540h-30q-13 0-21.5-8.5T400-570q0-13 8.5-21.5T430-600h50q17 0 28.5 11.5T520-560v170q0 13-8.5 21.5T490-360q-13 0-21.5-8.5T460-390v-150Z")
}
