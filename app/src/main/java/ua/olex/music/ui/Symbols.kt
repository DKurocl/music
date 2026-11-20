package ua.olex.music.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.olex.music.R

/**
 * Every icon in the app, as a Material Symbols Rounded codepoint. res/font/material_symbols.ttf is
 * the official variable font subset to exactly these (docs/design/symbols.py reads this list), with
 * its FILL and wght axes, so icons fill in and thicken smoothly as Google's own apps do.
 */
enum class Glyph(val codepoint: Int) {
    PLAY(0xE037), SHUFFLE(0xE043),
    QUEUE(0xE03D), MORE(0xE5D4), SEARCH(0xEF7A), CLOSE(0xE5CD), COLLAPSE(0xE5CF), BACK(0xE5C4), NOTE(0xE405),
    ALBUM(0xE019), PERSON(0xF0D3), FOLDER(0xE2C7), FAVOURITE(0xE87E), PLAYLIST_ADD(0xE03B), PLAYLIST(0xE05F),
    SLEEP(0xF159), SPEAKER(0xE32D), HEADPHONES(0xF01F), BLUETOOTH(0xE1A7), WAVE(0xE1B8),
    HISTORY(0xE8B3), LIBRARY(0xE030), REFRESH(0xE5D5), CHECK(0xE668), ADD(0xE145), DELETE(0xE92E), EDIT(0xF097),
    DRAG(0xE25D), TUNE(0xE429), INFO(0xE88E), PLAY_NEXT(0xE066), ADD_TO_QUEUE(0xE05C), DOWN(0xE5DB),
    REMOVE(0xF08F), GENRE(0xE01D), STAR(0xF09A), SMART(0xE65F), STATS(0xF092), DUPLICATES(0xEB7D),
    SETTINGS(0xE8B8), MIX(0xE51E), ENERGY(0xEA0B), HAPPY(0xE813), RELAX(0xEB4C), PARTY(0xEA65), WORKOUT(0xEB43), COMMUTE(0xE940), RAIN(0xF176), FOCUS(0xE719), MOTION(0xF49A), DOWNLOAD(0xF090), CHEVRON(0xE5CC), SORT(0xE164), BATTERY(0xEFDE), USB(0xE1E0), PHOTO(0xE3F4), LOCK(0xE899), TIMER(0xE425), HIDDEN(0xE8F5),
    CALENDAR(0xEBCC), HI_RES(0xE024), MIC(0xE31D), UNPLAYED(0xE440),
    // Where a song plays from (docs/specs/M10_ONLINE.md, D2), and an artist's radio.
    CLOUD(0xE2BD), CLOUD_DONE(0xE2BF), CLOUD_OFF(0xE2C1), DOWNLOADING(0xF001), RADIO(0xE03E),
    SHARE(0xE80D), HOME(0xE88A), VIBRATION(0xE62D), TRENDING(0xE8E5),
    WIFI(0xE63E), VIDEO(0xE063), FOLDER_OFF(0xEB83), UNDO(0xE166), MINUS(0xE15B);

    val text: String = String(Character.toChars(codepoint))

    /** The small gesture this glyph plays when its button is tapped. */
    val motion: GlyphMotion get() = when (this) {
        SETTINGS, TUNE, REFRESH, SHUFFLE, MIX, RADIO, SLEEP -> GlyphMotion.TILT
        PLAY_NEXT, CHEVRON -> GlyphMotion.NUDGE_RIGHT
        BACK -> GlyphMotion.NUDGE_LEFT
        DOWNLOAD, COLLAPSE, DOWN, MORE -> GlyphMotion.DROP
        ADD_TO_QUEUE, PLAYLIST_ADD, PLAYLIST, QUEUE -> GlyphMotion.RISE
        FAVOURITE, CHECK, STAR, ADD -> GlyphMotion.CONFIRM
        else -> GlyphMotion.POP
    }
}

/** Tap gestures for icons; each glyph picks the one that reads as its own (see [Glyph.motion]). */
enum class GlyphMotion { POP, TILT, NUDGE_RIGHT, NUDGE_LEFT, DROP, RISE, CONFIRM }

private object SymbolFont {
    @Volatile private var typeface: Typeface? = null
    fun get(ctx: android.content.Context): Typeface =
        typeface ?: ctx.resources.getFont(R.font.material_symbols).also { typeface = it }
}

/** Reuse the native font settings when a scroll or ripple redraws an unchanged symbol. */
private class SymbolPaint(context: android.content.Context) : Paint(Paint.ANTI_ALIAS_FLAG) {
    private var lastFill = -1
    private var lastWeight = -1

    init {
        typeface = SymbolFont.get(context)
        textAlign = Paint.Align.CENTER
    }

    fun setAxes(fill: Float, weight: Float) {
        val f = (fill.coerceIn(0f, 1f) * 32f).toInt()
        val w = weight.toInt()
        if (f == lastFill && w == lastWeight) return
        fontVariationSettings = "'FILL' ${f / 32f}, 'wght' $w"
        lastFill = f
        lastWeight = w
    }
}

/**
 * A Material Symbol with independent fill, weight and short press feedback. The animation stays
 * inside the control so the visual motion cannot move its layout or touch target.
 */
@Composable
fun Symbol(
    glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = LocalContentColor.current,
    filled: Boolean = false, bold: Boolean = false,
    /** Bumped by the icon's button on each tap: the glyph plays its own small gesture (see [GlyphMotion]). */
    kick: Int = 0, pressed: Boolean = LocalIconPressed.current,
    fillFraction: (() -> Float)? = null,
) {
    val ctx = LocalContext.current
    val motion = MaterialTheme.motionScheme
    val still = LocalPowerSave.current
    val fill by animateFloatAsState(if (filled) 1f else 0f, if (still) androidx.compose.animation.core.snap() else motion.defaultEffectsSpec(), label = "fill")
    val weight by animateFloatAsState(if (bold) 600f else 400f, if (still) androidx.compose.animation.core.snap() else motion.fastSpatialSpec(), label = "weight")
    val pressScale by animateFloatAsState(if (pressed && !still) 0.94f else 1f, if (pressed) tween(75) else motion.fastSpatialSpec(), label = "press")
    val tap = if (kick > 0 && !still) remember { Animatable(0f) }.also { pulse ->
        LaunchedEffect(kick) {
            pulse.animateTo(1f, tween(90, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            pulse.animateTo(0f, tween(155, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    } else null
    val gesture = glyph.motion
    val paint = remember(ctx) { SymbolPaint(ctx) }
    Canvas(modifier.size(size).graphicsLayer {
        val pulse = tap?.value ?: 0f
        val growth = when (gesture) {
            GlyphMotion.CONFIRM -> 0.18f
            GlyphMotion.POP -> 0.07f
            else -> 0.03f
        }
        val scale = pressScale * (1f + pulse * growth)
        scaleX = scale
        scaleY = scale
        when (gesture) {
            GlyphMotion.TILT -> rotationZ = pulse * (when (glyph) { Glyph.SLEEP, Glyph.SHUFFLE -> -14f; else -> 16f })
            GlyphMotion.NUDGE_RIGHT -> translationX = pulse * 2.5.dp.toPx()
            GlyphMotion.NUDGE_LEFT -> translationX = pulse * -2.5.dp.toPx()
            GlyphMotion.DROP -> translationY = pulse * 2.5.dp.toPx()
            GlyphMotion.RISE -> translationY = pulse * -2.5.dp.toPx()
            GlyphMotion.POP -> translationY = -pulse * 1.dp.toPx()
            GlyphMotion.CONFIRM -> translationY = 0f
        }
    }) {
        // 960 units per em with the icon standing on the baseline: an em-sized glyph drawn with
        // its baseline at the bottom fills the box exactly.
        val em = this.size.minDimension
        paint.textSize = em
        paint.color = color.toArgb()
        paint.setAxes(fillFraction?.invoke() ?: fill, weight)
        drawContext.canvas.nativeCanvas.drawText(glyph.text, this.size.width / 2, (this.size.height + em) / 2, paint)
    }
}

internal val LocalIconPressed = androidx.compose.runtime.compositionLocalOf { false }

/** The same interaction drives the button's ripple and the glyph under the finger. */
@Composable
internal fun KickIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable (Int) -> Unit) {
    var kick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    androidx.compose.material3.IconButton(onClick = { kick++; onClick() }, modifier = modifier, enabled = enabled, interactionSource = interaction) {
        androidx.compose.runtime.CompositionLocalProvider(LocalIconPressed provides pressed) { content(kick) }
    }
}

/** The tonal variant of [KickIconButton]. */
@Composable
internal fun KickFilledTonalIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable (Int) -> Unit) {
    var kick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    androidx.compose.material3.FilledTonalIconButton(onClick = { kick++; onClick() }, modifier = modifier, enabled = enabled, interactionSource = interaction) {
        androidx.compose.runtime.CompositionLocalProvider(LocalIconPressed provides pressed) { content(kick) }
    }
}
