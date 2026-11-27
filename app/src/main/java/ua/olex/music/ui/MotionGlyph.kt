package ua.olex.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Google's symbols with a gesture of their own, the way Pixel's own apps animate the icon of the tab
 * you choose (Clock's bells ring, Phone's star turns): each part of the symbol moves by itself, and
 * the outline and the filled form are the font's own two masters (FILL 0 and FILL 1 of
 * every contour). The filled form spreads from the centre over the outlined one, so no in-between
 * shape is ever drawn. The house hops and lands, the lens looks around, the queue's lines
 * slide in and its note jumps, sparkles turn and twinkle, the heart beats, rain falls from the cloud.
 */
internal enum class MotionGlyph(val contours: List<GlyphContour>, val durationMs: Int) {
    HOME(MotionGlyphData.HOME, 560), QUEUE(MotionGlyphData.QUEUE, 640), SEARCH(MotionGlyphData.SEARCH, 620),
    SMART(MotionGlyphData.SMART, 760), ENERGY(MotionGlyphData.ENERGY, 560), HAPPY(MotionGlyphData.HAPPY, 700),
    RELAX(MotionGlyphData.RELAX, 820), PARTY(MotionGlyphData.PARTY, 760), WORKOUT(MotionGlyphData.WORKOUT, 720),
    COMMUTE(MotionGlyphData.COMMUTE, 680), FAVOURITE(MotionGlyphData.FAVOURITE, 720), RAIN(MotionGlyphData.RAIN, 820),
    FOCUS(MotionGlyphData.FOCUS, 760), SLEEP(MotionGlyphData.SLEEP, 760), NOTE(MotionGlyphData.NOTE, 600);

    companion object {
        fun of(glyph: Glyph): MotionGlyph? = when (glyph) {
            Glyph.HOME -> HOME; Glyph.QUEUE -> QUEUE; Glyph.SEARCH -> SEARCH; Glyph.SMART -> SMART
            Glyph.ENERGY -> ENERGY; Glyph.HAPPY -> HAPPY; Glyph.RELAX -> RELAX; Glyph.PARTY -> PARTY
            Glyph.WORKOUT -> WORKOUT; Glyph.COMMUTE -> COMMUTE; Glyph.FAVOURITE -> FAVOURITE; Glyph.RAIN -> RAIN
            Glyph.FOCUS -> FOCUS; Glyph.SLEEP -> SLEEP; Glyph.NOTE -> NOTE
            else -> null
        }
    }
}

/** Where a part stands at one moment of its gesture, in font units around [px], [py]. */
internal class Pose {
    var px = 480f; var py = -480f
    var dx = 0f; var dy = 0f
    var rotation = 0f
    var sx = 1f; var sy = 1f
    fun reset(cx: Float, cy: Float) { px = cx; py = cy; dx = 0f; dy = 0f; rotation = 0f; sx = 1f; sy = 1f }
}

private fun seg(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
/** 0 at the ends, 1 in the middle: a there-and-back. */
private fun bump(t: Float, from: Float, to: Float) = sin(PI * seg(t, from, to)).toFloat()
/** 0 to 1 with one soft overshoot, like a medium-bouncy spring. */
private fun springIn(p: Float): Float = if (p <= 0f) 0f else if (p >= 1f) 1f else (1f - exp(-5.2f * p) * cos(2f * PI.toFloat() * 1.1f * p))
/** A swing that dies away: [cycles] there-and-backs, ending at rest. */
private fun wobble(p: Float, cycles: Float): Float = if (p <= 0f || p >= 1f) 0f else exp(-3.6f * p) * sin(2f * PI.toFloat() * cycles * p) * (1f - p)
private val anticipate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * The gesture of part [index] of [glyph] at time [t] (0 to 1). Parts are the font's contours, in
 * the order generated in MotionGlyphData; a hole and its outline share a pose so outlines stay whole.
 */
internal fun MotionGlyph.pose(index: Int, t: Float, pose: Pose) {
    when (this) {
        MotionGlyph.HOME -> {
            // Crouch, hop, land on the floor line and settle.
            pose.px = 480f; pose.py = -120f
            val crouch = anticipate.transform(seg(t, 0f, 0.16f)) * (1f - seg(t, 0.16f, 0.26f))
            val air = bump(t, 0.16f, 0.58f)
            val land = wobble(seg(t, 0.56f, 1f), 1.2f)
            pose.dy = -70f * air
            pose.sy = 1f - 0.14f * crouch + 0.08f * air - 0.1f * land
            pose.sx = 1f + 0.08f * crouch - 0.04f * air + 0.07f * land
        }
        MotionGlyph.SEARCH -> {
            // The lens looks to one side, then the other, then rests; it grows a little as it looks.
            pose.px = 380f; pose.py = -580f
            val p = seg(t, 0f, 1f)
            pose.rotation = -24f * wobble(p, 1.15f) * 1.5f
            val s = 1f + 0.12f * bump(t, 0f, 0.55f)
            pose.sx = s; pose.sy = s
        }
        MotionGlyph.QUEUE -> when (index) {
            0 -> { // The note jumps once the lines are in, tilting as it rises.
                pose.px = 700f; pose.py = -170f
                val air = bump(t, 0.28f, 0.7f)
                pose.dy = -80f * air
                pose.rotation = -14f * air + 8f * wobble(seg(t, 0.68f, 1f), 1f)
                val land = wobble(seg(t, 0.68f, 1f), 1f)
                pose.sy = 1f - 0.12f * land; pose.sx = 1f + 0.08f * land
            }
            else -> { // Lines slide in from the left, top first.
                val order = 3 - index // 3 top, 2 middle, 1 bottom
                val start = order * 0.08f
                val p = springIn(seg(t, start, start + 0.55f))
                pose.px = 120f; pose.py = when (index) { 1 -> -360f; 2 -> -520f; else -> -680f }
                pose.dx = -150f * (1f - p)
                pose.sx = 0.35f + 0.65f * p
            }
        }
        MotionGlyph.SMART -> when (index) {
            1, 2, 4 -> { // The big sparkle turns a quarter and swells.
                pose.px = 360f; pose.py = -480f
                pose.rotation = 90f * springIn(seg(t, 0f, 0.75f))
                val s = 0.82f + 0.18f * springIn(seg(t, 0f, 0.6f))
                pose.sx = s; pose.sy = s
            }
            0 -> { pose.px = 756f; pose.py = -756f; val s = springIn(seg(t, 0.18f, 0.7f)); pose.sx = s; pose.sy = s; pose.rotation = 45f * (1f - s) }
            else -> { pose.px = 760f; pose.py = -200f; val s = springIn(seg(t, 0.32f, 0.84f)); pose.sx = s; pose.sy = s; pose.rotation = -45f * (1f - s) }
        }
        MotionGlyph.ENERGY -> {
            // A jolt: snaps big, kicks sideways twice, settles.
            pose.px = 475f; pose.py = -478f
            val s = 1f + 0.22f * bump(t, 0f, 0.3f) - 0.06f * bump(t, 0.3f, 0.5f)
            pose.sx = s; pose.sy = s
            pose.rotation = 12f * wobble(seg(t, 0.05f, 0.9f), 2.2f) * 1.6f
        }
        MotionGlyph.HAPPY -> when (index) {
            1, 2 -> { // Eyes blink.
                pose.px = if (index == 1) 620f else 340f; pose.py = -580f
                pose.sy = 1f - 0.88f * bump(t, 0.08f, 0.32f)
                pose.dy = -10f * bump(t, 0.4f, 0.8f)
            }
            0 -> { // The smile widens.
                pose.px = 480f; pose.py = -330f
                val w = bump(t, 0.3f, 0.85f)
                pose.sx = 1f + 0.22f * w; pose.sy = 1f + 0.18f * w
            }
            else -> { pose.px = 480f; pose.py = -480f; pose.rotation = 12f * wobble(seg(t, 0.2f, 1f), 1.2f) * 1.6f }
        }
        MotionGlyph.RELAX -> {
            // The lotus breathes and sways from its base.
            pose.px = 480f; pose.py = -90f
            pose.rotation = 11f * wobble(seg(t, 0f, 1f), 1.4f) * 1.7f
            val s = 1f + 0.1f * bump(t, 0f, 0.6f)
            pose.sx = s; pose.sy = s
        }
        MotionGlyph.PARTY -> when (index) {
            0, 5, 6 -> { // The cone kicks back as it fires.
                pose.px = 120f; pose.py = -120f
                pose.rotation = -12f * bump(t, 0f, 0.25f) + 6f * wobble(seg(t, 0.25f, 1f), 1f)
            }
            else -> { // Confetti bursts out of the cone's mouth to its place.
                val start = 0.1f + (index - 1) * 0.06f
                val p = springIn(seg(t, start, start + 0.6f))
                val (cx, cy) = when (index) { 1 -> 740f to -610f; 2 -> 434f to -720f; 3 -> 575f to -720f; else -> 760f to -453f }
                pose.px = cx; pose.py = cy
                pose.dx = (380f - cx) * (1f - p); pose.dy = (-560f - cy) * (1f - p)
                pose.sx = p; pose.sy = p
                pose.rotation = 120f * (1f - p)
            }
        }
        MotionGlyph.WORKOUT -> {
            // Two lifts, the second smaller.
            pose.px = 480f; pose.py = -480f
            val lift = bump(t, 0f, 0.4f) + 0.55f * bump(t, 0.4f, 0.75f)
            pose.dy = -46f * lift
            pose.rotation = -18f * bump(t, 0f, 0.4f) + 10f * bump(t, 0.4f, 0.75f) + 4f * wobble(seg(t, 0.75f, 1f), 1f)
        }
        MotionGlyph.COMMUTE -> {
            // Pulls away: rolls back, surges forward, rocks on its springs.
            pose.px = 480f; pose.py = -160f
            pose.dx = -40f * bump(t, 0f, 0.22f) + 46f * (springIn(seg(t, 0.18f, 0.8f)) - seg(t, 0.18f, 0.8f)) * 2f
            pose.dy = -16f * kotlin.math.abs(sin(3f * PI.toFloat() * seg(t, 0.15f, 1f))) * (1f - seg(t, 0.15f, 1f))
            pose.rotation = -5f * bump(t, 0.18f, 0.45f)
        }
        MotionGlyph.FAVOURITE -> {
            // Two beats.
            pose.px = 480f; pose.py = -500f
            val s = 1f + 0.26f * bump(t, 0f, 0.26f) + 0.16f * bump(t, 0.32f, 0.56f) - 0.05f * bump(t, 0.56f, 0.8f)
            pose.sx = s; pose.sy = s
        }
        MotionGlyph.RAIN -> when (index) {
            0, 1, 2 -> { // Drops fall out of the cloud, one after another.
                val order = when (index) { 2 -> 0; 0 -> 1; else -> 2 }
                val start = 0.12f + order * 0.12f
                val p = springIn(seg(t, start, start + 0.5f))
                pose.px = when (index) { 0 -> 510f; 1 -> 750f; else -> 270f }; pose.py = -180f
                pose.dy = -150f * (1f - p)
                pose.sx = 0.4f + 0.6f * p; pose.sy = 0.2f + 0.8f * p
            }
            else -> { pose.px = 480f; pose.py = -600f; pose.dy = -18f * bump(t, 0f, 0.35f); val s = 1f + 0.06f * bump(t, 0f, 0.35f); pose.sx = s; pose.sy = s }
        }
        MotionGlyph.FOCUS -> {
            // Rings settle in from the centre out, like a lens finding focus.
            pose.px = 480f; pose.py = -480f
            val s = when (index) {
                4 -> 0.2f + 0.8f * springIn(seg(t, 0f, 0.5f))
                2, 3 -> 1.3f - 0.3f * springIn(seg(t, 0.1f, 0.65f))
                else -> 0.82f + 0.18f * springIn(seg(t, 0.2f, 0.85f))
            }
            pose.sx = s; pose.sy = s
        }
        MotionGlyph.SLEEP -> {
            // The moon rocks like a cradle.
            pose.px = 473f; pose.py = -473f
            pose.rotation = -30f * wobble(seg(t, 0f, 1f), 1.2f) * 1.8f
            val s = 0.9f + 0.1f * springIn(seg(t, 0f, 0.6f))
            pose.sx = s; pose.sy = s
        }
        MotionGlyph.NOTE -> {
            pose.px = 480f; pose.py = -120f
            val air = bump(t, 0.08f, 0.55f)
            pose.dy = -70f * air
            pose.rotation = -12f * air + 6f * wobble(seg(t, 0.55f, 1f), 1f)
        }
    }
}

/** Builds the symbol at [fill] with every part in its pose at [t] into [into] (font units, y down). */
internal fun MotionGlyph.buildPath(fill: Float, t: Float, into: Path, pose: Pose = Pose()) {
    into.reset()
    val moving = t > 0f && t < 1f
    contours.forEachIndexed { index, c ->
        var a = 1f; var b = 0f; var cc = 0f; var d = 1f; var e = 0f; var f = 0f
        if (moving) {
            pose.reset(480f, -480f)
            pose(index, t, pose)
            val r = pose.rotation * (PI.toFloat() / 180f)
            val cosR = cos(r); val sinR = sin(r)
            // x' = R·S·(x - p) + p + d
            a = cosR * pose.sx; b = sinR * pose.sx; cc = -sinR * pose.sy; d = cosR * pose.sy
            e = pose.px + pose.dx - (a * pose.px + cc * pose.py)
            f = pose.py + pose.dy - (b * pose.px + d * pose.py)
        }
        var k = 0
        fun x(i: Int): Float { val vx = c.empty[i] + (c.solid[i] - c.empty[i]) * fill; val vy = c.empty[i + 1] + (c.solid[i + 1] - c.empty[i + 1]) * fill; return a * vx + cc * vy + e }
        fun y(i: Int): Float { val vx = c.empty[i] + (c.solid[i] - c.empty[i]) * fill; val vy = c.empty[i + 1] + (c.solid[i + 1] - c.empty[i + 1]) * fill; return b * vx + d * vy + f }
        for (op in c.ops) {
            when (op) {
                'M' -> { into.moveTo(x(k), y(k)); k += 2 }
                'L' -> { into.lineTo(x(k), y(k)); k += 2 }
                'Q' -> { into.quadraticTo(x(k), y(k), x(k + 2), y(k + 2)); k += 4 }
                'C' -> { into.cubicTo(x(k), y(k), x(k + 2), y(k + 2), x(k + 4), y(k + 4)); k += 6 }
            }
        }
        into.close()
    }
}

/**
 * Draws [glyph] at gesture time [t] into a native canvas whose 0..960 square is the symbol: the
 * outlined form, with the filled form revealed inside a circle growing from the centre as [fill]
 * goes from 0 to 1.
 */
internal fun MotionGlyph.render(canvas: android.graphics.Canvas, fill: Float, t: Float, paint: android.graphics.Paint, empty: Path, solid: Path, pose: Pose) {
    canvas.save()
    canvas.translate(0f, 960f)
    when {
        fill <= 0.002f -> { buildPath(0f, t, empty, pose); canvas.drawPath(empty.asAndroidPath(), paint) }
        fill >= 0.998f -> { buildPath(1f, t, solid, pose); canvas.drawPath(solid.asAndroidPath(), paint) }
        else -> {
            buildPath(0f, t, empty, pose); buildPath(1f, t, solid, pose)
            val wipe = android.graphics.Path().apply { addCircle(480f, -480f, 700f * fill, android.graphics.Path.Direction.CW) }
            canvas.save(); canvas.clipOutPath(wipe); canvas.drawPath(empty.asAndroidPath(), paint); canvas.restore()
            canvas.save(); canvas.clipPath(wipe); canvas.drawPath(solid.asAndroidPath(), paint); canvas.restore()
        }
    }
    canvas.restore()
}

/**
 * A symbol that plays its own gesture each time it becomes [active] (a tab or a mood being chosen)
 * and fills as it does; it empties quietly when another is chosen. [pressed] presses it in a little.
 * In Battery Saver it only changes fill, at once.
 */
@Composable
internal fun MotionSymbol(
    glyph: MotionGlyph, active: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp,
    color: Color = LocalContentColor.current, pressed: Boolean = false, fillWhenActive: Boolean = true,
) {
    val still = LocalPowerSave.current
    val fill by animateFloatAsState(if (active && fillWhenActive) 1f else 0f,
        if (still) snap() else tween(if (active) 320 else 200, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)), label = "Symbol fill")
    val push by animateFloatAsState(if (pressed && !still) 1f else 0f,
        if (pressed) tween(90) else androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 700f), label = "Symbol press")
    val time = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(active) }
    LaunchedEffect(active, still) {
        if (active && !seen && !still) {
            time.snapTo(0f)
            time.animateTo(1f, tween(glyph.durationMs, easing = LinearEasing))
        } else if (still) time.snapTo(1f)
        seen = active
    }
    val empty = remember { Path() }
    val solid = remember { Path() }
    val pose = remember { Pose() }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 960f
        val press = 1f - 0.12f * push
        paint.color = color.toArgb()
        val native = drawContext.canvas.nativeCanvas
        native.save()
        native.scale(press, press, this.size.width / 2f, this.size.height / 2f)
        native.scale(unit, unit)
        glyph.render(native, fill, time.value, paint, empty, solid, pose)
        native.restore()
    }
}
