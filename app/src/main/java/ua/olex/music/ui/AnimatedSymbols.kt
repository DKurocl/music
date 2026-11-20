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
import kotlinx.coroutines.launch

private val svgCenter = Offset(480f, -480f)

private fun symbolPath(data: String): Path = PathParser().parsePathString(data).toPath()

@Composable
internal fun playerControlSelection(selected: Boolean): State<Float> {
    val target = if (selected) 1f else 0f
    return if (LocalPowerSave.current) rememberUpdatedState(target)
        else animateFloatAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "Player control selection")
}

@Composable
internal fun playerControlPress(pressed: Boolean): State<Float> {
    val target = if (pressed) 1f else 0f
    return if (LocalPowerSave.current) rememberUpdatedState(target)
        else animateFloatAsState(target, MaterialTheme.motionScheme.fastSpatialSpec(), label = "Player control press")
}

@Composable
internal fun playerPressRelease(pressed: Boolean): State<Float> {
    val target = if (pressed) 1f else 0f
    val motion = MaterialTheme.motionScheme
    return if (LocalPowerSave.current) rememberUpdatedState(target) else animateFloatAsState(
        target, if (pressed) motion.fastSpatialSpec() else androidx.compose.animation.core.spring(stiffness = 600f, dampingRatio = 0.5f),
        label = "Transport release",
    )
}

// Transport plates square up when on; [round] ones (the dock, a pill itself) stay circles.
private fun plate(center: Offset, full: Float, selected: Float, pressed: Float, round: Boolean = false): RoundRect {
    val expansion = maxOf(selected, pressed * 0.72f).coerceIn(0f, 1f).takeIf { it >= 0.012f } ?: 0f
    val side = full * expansion * (1f - 0.1f * pressed)
    val half = side / 2f
    val corner = if (round) 0.5f else (0.5f - 0.18f * selected) * (1f - pressed * 0.45f)
    return RoundRect(Rect(center.x - half, center.y - half, center.x + half, center.y + half), CornerRadius(side * corner))
}

/** A single bounded tonal shape supplies visual press feedback inside the fixed hit target. */
internal fun Modifier.playerControlContainer(active: State<Float>, push: State<Float>, focused: Boolean, fill: Color, focus: Color,
    round: Boolean = false): Modifier =
    drawBehind {
        val shape = plate(center, 44.dp.toPx(), active.value.coerceIn(0f, 1f), push.value.coerceIn(0f, 1f), round)
        if (shape.width > 0f) drawRoundRect(fill, topLeft = Offset(shape.left, shape.top),
            size = Size(shape.width, shape.height), cornerRadius = shape.topLeftCornerRadius)
        if (focused) {
            val inset = 1.5.dp.toPx()
            drawRoundRect(focus, topLeft = Offset(inset, inset), size = Size(size.width - 2f * inset, size.height - 2f * inset),
                cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.controlInk(
    mask: Path, selection: Float, press: Float, glyphScale: Float, off: Color, on: Color,
    round: Boolean = false,
    drawIcon: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val shape = plate(center, 44.dp.toPx() / glyphScale, selection.coerceIn(0f, 1f), press.coerceIn(0f, 1f), round)
    if (shape.width <= 0f) drawIcon(off) else {
        mask.reset()
        mask.addRoundRect(shape)
        clipPath(mask, androidx.compose.ui.graphics.ClipOp.Difference) { drawIcon(off) }
        clipPath(mask) { drawIcon(on) }
    }
}

/** Material Symbols Rounded SVGs use a 960-unit viewport with the baseline at zero. */
@Composable
internal fun PlayerModeSymbol(
    shuffle: Boolean, on: Boolean, one: Boolean,
    active: State<Float>, push: State<Float>, offInk: Color, onInk: Color,
) {
    val still = LocalPowerSave.current
    val motion = MaterialTheme.motionScheme
    val crossover by animateFloatAsState(if (shuffle && on) 1f else 0f,
        if (still) snap() else motion.defaultSpatialSpec(), label = "Shuffle crossover")
    val turn = remember { Animatable(0f, visibilityThreshold = 0.003f) }
    val nudge = remember { Animatable(1f).apply { updateBounds(0f, 1f) } }
    val previousMode = remember { booleanArrayOf(on, one) }
    LaunchedEffect(on, one, still) {
        val changed = previousMode[0] != on || previousMode[1] != one
        // Turning on and changing mode roll the arrows once round the loop; turning off is quiet:
        // the loop settles where it is while the plate and the "1" go.
        val turningOff = previousMode[0] && !on
        previousMode[0] = on
        previousMode[1] = one
        if (shuffle || still) {
            turn.snapTo(0f)
            nudge.snapTo(1f)
        } else if (changed) kotlinx.coroutines.coroutineScope {
            launch { nudge.animateTo(1f, motion.fastSpatialSpec()) }
            if (!turningOff) turn.animateTo(kotlin.math.floor(turn.value + 0.07f) + 1f, motion.defaultSpatialSpec())
            else if (turn.value % 1f != 0f) turn.animateTo(kotlin.math.ceil(turn.value), motion.fastSpatialSpec())
        }
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
                            translate(-86f * weave, -86f * weave) { drawPath(ModeOutlines.shuffleTail, ink) }
                            translate(86f * weave, 86f * weave) { drawPath(ModeOutlines.shuffleUnder, ink) }
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
                        } else if (!still && nudge.value in 0.004f..0.995f) {
                            val bend = if (nudge.value < 0.78f) kotlin.math.sin(nudge.value / 0.78f * kotlin.math.PI)
                                else -0.18 * kotlin.math.sin((nudge.value - 0.78f) / 0.22f * kotlin.math.PI)
                            val slide = -120f * bend.toFloat()
                            repeat(2) { index ->
                                rotate(index * 180f, svgCenter) {
                                    drawPath(ModeOutlines.repeatNudgeRail, ink, style = Stroke(80f, cap = StrokeCap.Round))
                                    drawLine(ink, Offset(280f, -720f), Offset(686f + slide, -720f), 80f, StrokeCap.Round)
                                    translate(686f + slide, -720f) { drawPath(ModeOutlines.repeatHead, ink) }
                                }
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
        controlInk(mask, selected, pressure, 1f - 0.12f * pressure, offInk, onInk) { drawSymbol(it) }
    }
}

// Google Material Symbols Rounded, shuffle/repeat/repeat_one, default/24px (Apache-2.0).
// https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/
private object ModeOutlines {
    val shuffleUnder = symbolPath("M600-160q-17 0-28.5-11.5T560-200q0-17 11.5-28.5T600-240h64l-99-99q-12-12-11.5-28.5T566-396q12-12 28.5-12t28.5 12l97 98v-62q0-17 11.5-28.5T760-400q17 0 28.5 11.5T800-360v160q0 17-11.5 28.5T760-160H600Z")
    val shuffleOver = symbolPath("M172-172q-11-11-11-28t11-28l492-492h-64q-17 0-28.5-11.5T560-760q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760v160q0 17-11.5 28.5T760-560q-17 0-28.5-11.5T720-600v-64L228-172q-11 11-28 11t-28-11Z")
    val shuffleTail = symbolPath("M171-732q-11-11-11-28t11-28q11-11 27.5-11t28.5 11l168 167q11 11 11.5 27.5T395-565q-11 11-28 11t-28-11L171-732Z")
    val repeat = symbolPath("m274-200 34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z")
    val repeatNudgeRail = symbolPath("M240-560V-680Q240-720 280-720")
    val repeatHead = symbolPath("M0 40l-34 34q-12 12-11.5 28t11.5 28q12 12 28.5 11.5t28.5-12.5l103-103q6-6 8.5-13t2.5-15q0-8-2.5-15t-8.5-13l-103-103q-12-12-28.5-11.5T-34-130q-12 12-11.5 28t11.5 28L0-40H-45v80Z")
    val one = symbolPath("M460-540h-30q-13 0-21.5-8.5T400-570q0-13 8.5-21.5T430-600h50q17 0 28.5 11.5T520-560v170q0 13-8.5 21.5T490-360q-13 0-21.5-8.5T460-390v-150Z")
}
/** The SVG used by each dock action, including all of the supported output routes. */
internal enum class PlayerDockMark { LYRICS, SLEEP, QUEUE, SPEAKER, HEADPHONES, USB, FEEL }

@Composable
internal fun PlayerDockSymbol(
    mark: PlayerDockMark, active: Boolean, pushState: State<Float>, selection: State<Float>, kick: Int, offInk: Color, onInk: Color,
) {
    val still = LocalPowerSave.current
    val motion = MaterialTheme.motionScheme
    val phase by animateFloatAsState(if (active) 1f else 0f, if (still) snap() else motion.defaultSpatialSpec(),
        label = "Dock gesture position")
    val impulse = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(kick, still) {
        if (still || coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor == 0f) {
            impulse.snapTo(0f)
        } else if (kick != 0) {
            impulse.animateTo(1f, motion.fastSpatialSpec())
            impulse.animateTo(0f, motion.fastSpatialSpec())
        }
    }
    val wave = remember { Animatable(1f) }
    LaunchedEffect(kick, still, mark) {
        if (still || mark != PlayerDockMark.SPEAKER) wave.snapTo(1f)
        else if (kick != 0) {
            if (wave.value >= 0.999f) wave.snapTo(0f)
            wave.animateTo(1f, androidx.compose.animation.core.tween(350, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    val mask = remember { Path() }
    val rows = remember { List(3) { Animatable(if (active) 1f else 0f) } }
    LaunchedEffect(active, still, mark) {
        if (mark == PlayerDockMark.LYRICS || mark == PlayerDockMark.QUEUE) kotlinx.coroutines.coroutineScope {
            rows.forEachIndexed { index, anim ->
                launch {
                    if (still || coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor == 0f) anim.snapTo(if (active) 1f else 0f)
                    else {
                        kotlinx.coroutines.delay((if (mark == PlayerDockMark.QUEUE) 50L else 40L) * if (active) index else 2 - index)
                        anim.animateTo(if (active) 1f else 0f, motion.defaultSpatialSpec())
                    }
                }
            }
        }
    }
    val movingDocument = androidx.compose.runtime.remember(mark) { if (mark == PlayerDockMark.LYRICS) Path() else null }

    Canvas(Modifier.size(24.dp).graphicsLayer {
        scaleX = 1f - 0.12f * pushState.value.coerceIn(0f, 1f)
        scaleY = scaleX
    }) {
        val unit = size.minDimension / 960f
        val selected = if (still) (if (active) 1f else 0f) else phase.coerceIn(0f, 1f)
        val push = if (still) 0f else impulse.value.coerceIn(0f, 1f)
        // The peaks are different poses along one spatial spring. Reversing the state unwinds
        // the parts in reverse order from their current positions.
        fun pulse(start: Float, end: Float): Float {
            val time = ((selected - start) / (end - start)).coerceIn(0f, 1f)
            return 4f * time * (1f - time)
        }
        fun rowTravel(index: Int): Float {
            val t = if (still) 0f else rows[index].value.coerceIn(0f, 1f)
            return 4f * t * (1f - t)
        }
        val lead = pulse(0f, 0.82f)
        val follow = pulse(0.17f, 1f)
        val pressure = pushState.value.coerceIn(0f, 1f)
        controlInk(mask, selection.value, pressure, 1f - pressure * 0.12f, offInk, onInk, round = true) { ink ->
            scale(unit, unit, pivot = Offset.Zero) {
                translate(top = 960f) {
                    when (mark) {
                        PlayerDockMark.LYRICS -> {
                            val document = checkNotNull(movingDocument)
                            // The page stays an outline in both states: the tonal plate says "on",
                            // and the lines and note do the moving; a fill swap read as a flat blink.
                            document.reset()
                            document.addPath(DockOutlines.lyricsPage)
                            DockOutlines.lyricsLines.forEachIndexed { index, path ->
                                document.addPath(path, Offset(124f * rowTravel(index), 0f))
                            }
                            drawPath(document, ink)
                            translate(-18f * follow, -48f * follow) {
                                rotate(-12f * follow, Offset(790f, -675f)) { drawPath(DockOutlines.lyricsNote, ink) }
                            }
                        }
                        PlayerDockMark.SLEEP -> {
                            val sway = push + (if (active) 1f else -1f) * 0.65f * follow
                            rotate(-24f * sway.coerceIn(-1f, 1f), svgCenter) { drawPath(DockOutlines.moon, ink) }
                        }
                        PlayerDockMark.QUEUE -> {
                            DockOutlines.queueLines.forEachIndexed { index, path ->
                                // The lowest line leads the opening; the top line leads the return.
                                val step = rowTravel(index)
                                translate(left = -160f * step) { drawPath(path, ink) }
                            }
                            translate(top = -120f * follow) { drawPath(DockOutlines.queueNote, ink) }
                        }
                        PlayerDockMark.SPEAKER -> {
                            val ripple = if (still) 1f else wave.value
                            if (ripple < 1f) drawCircle(ink, radius = 280f * (1f + 0.6f * ripple), center = svgCenter,
                                alpha = 0.5f * (1f - ripple), style = Stroke(36f))
                            rotate(-12f * push, svgCenter) {
                                drawPath(DockOutlines.speakerBody, ink)
                                scale(1f + 0.17f * push, 1f + 0.17f * push, Offset(480f, -360f)) {
                                    drawPath(DockOutlines.speakerDriver, ink)
                                }
                            }
                        }
                        PlayerDockMark.HEADPHONES -> scale(1f + 0.14f * push, 1f - 0.055f * push, svgCenter) {
                            drawPath(DockOutlines.headphones, ink)
                        }
                        PlayerDockMark.USB -> translate(top = -69f * push) { drawPath(DockOutlines.usb, ink) }
                        PlayerDockMark.FEEL -> {
                            translate(left = 34f * (lead - 1.35f * follow)) {
                                drawPath(DockOutlines.phone, ink)
                            }
                            DockOutlines.phoneWaves.forEachIndexed { index, path ->
                                val side = if (index < 2) -1f else 1f
                                val wave = if (index == 0 || index == 3) follow else lead
                                translate(left = side * 53f * wave) { drawPath(path, ink) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Google Material Symbols Rounded default/fill1, optical size 24, wght 400 (Apache-2.0).
// https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/
private object DockOutlines {
    val lyricsPage = symbolPath("M600.0 -240.0H240.0L148.0 -148.0Q142.0 -142.0 135.0 -139.0Q128.0 -136.0 120.0 -136.0Q104.0 -136.0 92.0 -147.5Q80.0 -159.0 80.0 -177.0V-800.0Q80.0 -833.0 103.5 -856.5Q127.0 -880.0 160.0 -880.0H600.0Q633.0 -880.0 656.5 -856.5Q680.0 -833.0 680.0 -800.0V-783.0Q656.0 -772.0 636.0 -756.0Q616.0 -740.0 600.0 -720.0V-800.0H160.0V-273.0L207.0 -320.0H600.0V-480.0Q616.0 -460.0 636.0 -444.0Q656.0 -428.0 680.0 -417.0V-320.0Q680.0 -287.0 656.5 -263.5Q633.0 -240.0 600.0 -240.0Z")
    val lyricsLines = listOf(
        symbolPath("M280.0 -640.0H480.0Q497.0 -640.0 508.5 -651.5Q520.0 -663.0 520.0 -680.0Q520.0 -697.0 508.5 -708.5Q497.0 -720.0 480.0 -720.0H280.0Q263.0 -720.0 251.5 -708.5Q240.0 -697.0 240.0 -680.0Q240.0 -663.0 251.5 -651.5Q263.0 -640.0 280.0 -640.0Z"),
        symbolPath("M280.0 -520.0H480.0Q497.0 -520.0 508.5 -531.5Q520.0 -543.0 520.0 -560.0Q520.0 -577.0 508.5 -588.5Q497.0 -600.0 480.0 -600.0H280.0Q263.0 -600.0 251.5 -588.5Q240.0 -577.0 240.0 -560.0Q240.0 -543.0 251.5 -531.5Q263.0 -520.0 280.0 -520.0Z"),
        symbolPath("M280.0 -400.0H360.0Q377.0 -400.0 388.5 -411.5Q400.0 -423.0 400.0 -440.0Q400.0 -457.0 388.5 -468.5Q377.0 -480.0 360.0 -480.0H280.0Q263.0 -480.0 251.5 -468.5Q240.0 -457.0 240.0 -440.0Q240.0 -423.0 251.5 -411.5Q263.0 -400.0 280.0 -400.0Z"),
    )
    val lyricsNote = symbolPath("M760.0 -480.0Q710.0 -480.0 675.0 -515.0Q640.0 -550.0 640.0 -600.0Q640.0 -650.0 675.0 -685.0Q710.0 -720.0 760.0 -720.0Q771.0 -720.0 781.0 -718.0Q791.0 -716.0 800.0 -713.0V-880.0Q800.0 -897.0 811.5 -908.5Q823.0 -920.0 840.0 -920.0H920.0Q937.0 -920.0 948.5 -908.5Q960.0 -897.0 960.0 -880.0Q960.0 -863.0 948.5 -851.5Q937.0 -840.0 920.0 -840.0H880.0V-600.0Q880.0 -550.0 845.0 -515.0Q810.0 -480.0 760.0 -480.0Z")
    val moon = symbolPath("M484-80q-84 0-157.5-32t-128-86.5Q144-253 112-326.5T80-484q0-128 72-232t193-146q22-8 41 5.5t18 36.5q-3 85 27 162t90 137q60 60 137 90t162 27q26-1 38.5 17.5T863-345q-44 120-147.5 192.5T484-80Zm0-80q88 0 163-44t118-121q-86-8-163-43.5T464-465q-61-61-97-138t-43-163q-77 43-120.5 118.5T160-484q0 135 94.5 229.5T484-160Zm-20-305Z")
    val queueNote = symbolPath("M640.0 -160.0Q590.0 -160.0 555.0 -195.0Q520.0 -230.0 520.0 -280.0Q520.0 -330.0 555.0 -365.0Q590.0 -400.0 640.0 -400.0Q651.0 -400.0 661.0 -398.5Q671.0 -397.0 680.0 -392.0V-680.0Q680.0 -697.0 691.5 -708.5Q703.0 -720.0 720.0 -720.0H840.0Q857.0 -720.0 868.5 -708.5Q880.0 -697.0 880.0 -680.0Q880.0 -663.0 868.5 -651.5Q857.0 -640.0 840.0 -640.0H760.0V-280.0Q760.0 -230.0 725.0 -195.0Q690.0 -160.0 640.0 -160.0Z")
    val queueLines = listOf(
        symbolPath("M160.0 -320.0Q143.0 -320.0 131.5 -331.5Q120.0 -343.0 120.0 -360.0Q120.0 -377.0 131.5 -388.5Q143.0 -400.0 160.0 -400.0H400.0Q417.0 -400.0 428.5 -388.5Q440.0 -377.0 440.0 -360.0Q440.0 -343.0 428.5 -331.5Q417.0 -320.0 400.0 -320.0H160.0Z"),
        symbolPath("M160.0 -480.0Q143.0 -480.0 131.5 -491.5Q120.0 -503.0 120.0 -520.0Q120.0 -537.0 131.5 -548.5Q143.0 -560.0 160.0 -560.0H560.0Q577.0 -560.0 588.5 -548.5Q600.0 -537.0 600.0 -520.0Q600.0 -503.0 588.5 -491.5Q577.0 -480.0 560.0 -480.0H160.0Z"),
        symbolPath("M160.0 -640.0Q143.0 -640.0 131.5 -651.5Q120.0 -663.0 120.0 -680.0Q120.0 -697.0 131.5 -708.5Q143.0 -720.0 160.0 -720.0H560.0Q577.0 -720.0 588.5 -708.5Q600.0 -697.0 600.0 -680.0Q600.0 -663.0 588.5 -651.5Q577.0 -640.0 560.0 -640.0H160.0Z"),
    )
    val speakerBody = symbolPath("M680.0 -80.0H280.0Q247.0 -80.0 223.5 -103.5Q200.0 -127.0 200.0 -160.0V-800.0Q200.0 -833.0 223.5 -856.5Q247.0 -880.0 280.0 -880.0H680.0Q713.0 -880.0 736.5 -856.5Q760.0 -833.0 760.0 -800.0V-160.0Q760.0 -127.0 736.5 -103.5Q713.0 -80.0 680.0 -80.0ZM680.0 -160.0V-800.0H280.0V-160.0H680.0ZM536.5 -623.5Q560.0 -647.0 560.0 -680.0Q560.0 -713.0 536.5 -736.5Q513.0 -760.0 480.0 -760.0Q447.0 -760.0 423.5 -736.5Q400.0 -713.0 400.0 -680.0Q400.0 -647.0 423.5 -623.5Q447.0 -600.0 480.0 -600.0Q513.0 -600.0 536.5 -623.5Z")
    val speakerDriver = symbolPath("M593.0 -247.0Q640.0 -294.0 640.0 -360.0Q640.0 -426.0 593.0 -473.0Q546.0 -520.0 480.0 -520.0Q414.0 -520.0 367.0 -473.0Q320.0 -426.0 320.0 -360.0Q320.0 -294.0 367.0 -247.0Q414.0 -200.0 480.0 -200.0Q546.0 -200.0 593.0 -247.0ZM423.5 -303.5Q400.0 -327.0 400.0 -360.0Q400.0 -393.0 423.5 -416.5Q447.0 -440.0 480.0 -440.0Q513.0 -440.0 536.5 -416.5Q560.0 -393.0 560.0 -360.0Q560.0 -327.0 536.5 -303.5Q513.0 -280.0 480.0 -280.0Q447.0 -280.0 423.5 -303.5Z")
    val headphones = symbolPath("M280-120h-80q-33 0-56.5-23.5T120-200v-280q0-75 28.5-140.5t77-114q48.5-48.5 114-77T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480v280q0 33-23.5 56.5T760-120h-80q-33 0-56.5-23.5T600-200v-160q0-33 23.5-56.5T680-440h80v-40q0-117-81.5-198.5T480-760q-117 0-198.5 81.5T200-480v40h80q33 0 56.5 23.5T360-360v160q0 33-23.5 56.5T280-120Zm0-240h-80v160h80v-160Zm400 0v160h80v-160h-80Zm-400 0h-80 80Zm400 0h80-80Z")
    val usb = symbolPath("M423.5-103.5Q400-127 400-160q0-21 11-39t29-29v-92H320q-33 0-56.5-23.5T240-400v-92q-18-9-29-27t-11-41q0-33 23.5-56.5T280-640q33 0 56.5 23.5T360-560q0 23-11 40t-29 28v92h120v-320h-40q-12 0-18-11t2-21l80-107q6-8 16-8t16 8l80 107q8 10 2 21t-18 11h-40v320h120v-80h-20q-8 0-14-6t-6-14v-120q0-8 6-14t14-6h120q8 0 14 6t6 14v120q0 8-6 14t-14 6h-20v80q0 33-23.5 56.5T640-320H520v92q19 10 29.5 28t10.5 40q0 33-23.5 56.5T480-80q-33 0-56.5-23.5Z")
    val phone = symbolPath("M320.0 -120.0Q287.0 -120.0 263.5 -143.5Q240.0 -167.0 240.0 -200.0V-760.0Q240.0 -793.0 263.5 -816.5Q287.0 -840.0 320.0 -840.0H640.0Q673.0 -840.0 696.5 -816.5Q720.0 -793.0 720.0 -760.0V-200.0Q720.0 -167.0 696.5 -143.5Q673.0 -120.0 640.0 -120.0H320.0ZM640.0 -200.0V-760.0H320.0V-200.0H640.0ZM508.5 -651.5Q520.0 -663.0 520.0 -680.0Q520.0 -697.0 508.5 -708.5Q497.0 -720.0 480.0 -720.0Q463.0 -720.0 451.5 -708.5Q440.0 -697.0 440.0 -680.0Q440.0 -663.0 451.5 -651.5Q463.0 -640.0 480.0 -640.0Q497.0 -640.0 508.5 -651.5Z")
    val phoneWaves = listOf(
        symbolPath("M0.0 -400.0V-560.0Q0.0 -577.0 11.5 -588.5Q23.0 -600.0 40.0 -600.0Q57.0 -600.0 68.5 -588.5Q80.0 -577.0 80.0 -560.0V-400.0Q80.0 -383.0 68.5 -371.5Q57.0 -360.0 40.0 -360.0Q23.0 -360.0 11.5 -371.5Q0.0 -383.0 0.0 -400.0Z"),
        symbolPath("M120.0 -320.0V-640.0Q120.0 -657.0 131.5 -668.5Q143.0 -680.0 160.0 -680.0Q177.0 -680.0 188.5 -668.5Q200.0 -657.0 200.0 -640.0V-320.0Q200.0 -303.0 188.5 -291.5Q177.0 -280.0 160.0 -280.0Q143.0 -280.0 131.5 -291.5Q120.0 -303.0 120.0 -320.0Z"),
        symbolPath("M760.0 -320.0V-640.0Q760.0 -657.0 771.5 -668.5Q783.0 -680.0 800.0 -680.0Q817.0 -680.0 828.5 -668.5Q840.0 -657.0 840.0 -640.0V-320.0Q840.0 -303.0 828.5 -291.5Q817.0 -280.0 800.0 -280.0Q783.0 -280.0 771.5 -291.5Q760.0 -303.0 760.0 -320.0Z"),
        symbolPath("M880.0 -400.0V-560.0Q880.0 -577.0 891.5 -588.5Q903.0 -600.0 920.0 -600.0Q937.0 -600.0 948.5 -588.5Q960.0 -577.0 960.0 -560.0V-400.0Q960.0 -383.0 948.5 -371.5Q937.0 -360.0 920.0 -360.0Q903.0 -360.0 891.5 -371.5Q880.0 -383.0 880.0 -400.0Z"),
    )
}

/** One triangle opens into two continuous stems in the same viewport. */
@Composable
internal fun PlayPauseSymbol(
    playing: Boolean, size: androidx.compose.ui.unit.Dp, pressed: Boolean = LocalIconPressed.current,
    color: Color? = null, selected: State<Float>? = null, selectedInk: Color? = null,
) {
    val still = LocalPowerSave.current
    val motion = MaterialTheme.motionScheme
    val morph by animateFloatAsState(if (playing) 1f else 0f, if (still) snap() else motion.fastSpatialSpec(), label = "Play pause morph")
    val pressure = playerControlPress(pressed)
    val ink = androidx.compose.material3.LocalContentColor.current
    val path = remember { Path() }
    val mask = remember { Path() }
    Canvas(Modifier.size(size).graphicsLayer { scaleX = 1f - pressure.value * 0.12f; scaleY = scaleX }) {
        val progress = if (still) (if (playing) 1f else 0f) else morph.coerceIn(0f, 1f)
        val unit = this.size.minDimension / 960f
        fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSymbol(paint: Color) {
        scale(unit, unit, pivot = Offset.Zero) {
            translate(top = 960f) {
                when (progress) {
                    0f -> drawPath(TransportOutlines.play, paint)
                    1f -> drawPath(TransportOutlines.pause, paint)
                    else -> {
                        fun mix(start: Float, end: Float) = start + (end - start) * progress
                        fun stem(left: Float, right: Float, leftTop: Float, rightTop: Float) {
                            path.reset()
                            path.moveTo(left, leftTop)
                            path.lineTo(right, rightTop)
                            path.lineTo(right, -960f - rightTop)
                            path.lineTo(left, -960f - leftTop)
                            path.close()
                            drawPath(path, paint)
                            drawPath(path, paint, style = Stroke(76f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        }
                        stem(mix(357f, 279f), mix(524f, 361f), mix(-687f, -721f), mix(-584f, -721f))
                        stem(mix(524f, 599f), mix(686f, 681f), mix(-584f, -721f), mix(-480f, -721f))
                    }
                }
            }
        }
        }
        val press = pressure.value.coerceIn(0f, 1f)
        if (selected != null && selectedInk != null) {
            controlInk(mask, selected.value, press, 1f - 0.12f * press, color ?: ink, selectedInk) { drawSymbol(it) }
        } else drawSymbol(color ?: ink)
    }
}

@Composable
internal fun SkipSymbol(
    forward: Boolean, size: androidx.compose.ui.unit.Dp, kick: Int,
    pressed: Boolean = LocalIconPressed.current, color: Color = androidx.compose.material3.LocalContentColor.current,
    pressedInk: Color? = null,
) {
    val still = LocalPowerSave.current
    val motion = MaterialTheme.motionScheme
    val pressure by playerPressRelease(pressed)
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(kick, still) {
        if (still) pulse.snapTo(0f)
        else if (kick != 0) {
            pulse.animateTo(0.74f, motion.fastSpatialSpec())
            pulse.animateTo(0f, motion.fastSpatialSpec())
        }
    }
    val mask = remember { Path() }
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / 960f
        val force = if (still) (if (pressed) 1f else 0f) else (if (kick == 0) pressure else maxOf(pressure, pulse.value)).coerceIn(-0.215f, 1f)
        fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSymbol(ink: Color) {
            scale(unit, unit, pivot = Offset.Zero) {
                translate(top = 960f) {
                    scale(if (forward) 1f else -1f, 1f, svgCenter) {
                        scale(1f - 0.28f * force, 1f, Offset(470f, -480f)) { drawPath(TransportOutlines.skipArrow, ink) }
                        translate(left = 1.5.dp.toPx() / unit * force) { drawPath(TransportOutlines.skipStop, ink) }
                    }
                }
            }
        }
        if (pressedInk == null) drawSymbol(color)
        else controlInk(mask, 0f, force.coerceIn(0f, 1f), 1f, color, pressedInk) { drawSymbol(it) }
    }
}

private object TransportOutlines {
    val play = symbolPath("M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z")
    val pause = symbolPath("M640-200q-33 0-56.5-23.5T560-280v-400q0-33 23.5-56.5T640-760q33 0 56.5 23.5T720-680v400q0 33-23.5 56.5T640-200Zm-320 0q-33 0-56.5-23.5T240-280v-400q0-33 23.5-56.5T320-760q33 0 56.5 23.5T400-680v400q0 33-23.5 56.5T320-200Z")
    val skipStop = symbolPath("M660-280v-400q0-17 11.5-28.5T700-720q17 0 28.5 11.5T740-680v400q0 17-11.5 28.5T700-240q-17 0-28.5-11.5T660-280Z")
    val skipArrow = symbolPath("M220-315v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T548-480q0 10-4.5 18.5T530-447L282-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Z")
}
