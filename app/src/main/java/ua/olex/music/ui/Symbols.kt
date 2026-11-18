package ua.olex.music.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import ua.olex.music.R

/**
 * Every icon in the app, as a Material Symbols Rounded codepoint. res/font/material_symbols.ttf is
 * the official variable font subset to exactly these (docs/design/symbols.py reads this list), with
 * its FILL and wght axes, so icons fill in and thicken smoothly as Google's own apps do.
 */
enum class Glyph(val codepoint: Int) {
    PLAY(0xE037), PAUSE(0xE034), NEXT(0xE044), PREVIOUS(0xE045), SHUFFLE(0xE043), REPEAT(0xE040), REPEAT_ONE(0xE041),
    QUEUE(0xE03D), MORE(0xE5D4), SEARCH(0xEF7A), CLOSE(0xE5CD), COLLAPSE(0xE5CF), BACK(0xE5C4), NOTE(0xE405),
    ALBUM(0xE019), PERSON(0xF0D3), FOLDER(0xE2C7), FAVOURITE(0xE87E), PLAYLIST_ADD(0xE03B), PLAYLIST(0xE05F),
    LYRICS(0xEC0B), SLEEP(0xF159), SPEAKER(0xE32D), HEADPHONES(0xF01F), BLUETOOTH(0xE1A7), WAVE(0xE1B8),
    HISTORY(0xE8B3), LIBRARY(0xE030), REFRESH(0xE5D5), CHECK(0xE668), ADD(0xE145), DELETE(0xE92E), EDIT(0xF097),
    DRAG(0xE25D), TUNE(0xE429), INFO(0xE88E), PLAY_NEXT(0xE066), ADD_TO_QUEUE(0xE05C), UP(0xE5D8), DOWN(0xE5DB),
    REMOVE(0xF08F), GENRE(0xE01D), STAR(0xF09A), SMART(0xE65F), HOME(0xE9B2), STATS(0xF092), DUPLICATES(0xEB7D),
    SETTINGS(0xE8B8), MIX(0xE51E), ENERGY(0xEA0B), HAPPY(0xE813), RELAX(0xEB4C), PARTY(0xEA65), WORKOUT(0xEB43), COMMUTE(0xE940), RAIN(0xF176), FOCUS(0xE719), MOTION(0xF49A), DOWNLOAD(0xF090), CHEVRON(0xE5CC), SORT(0xE164), BATTERY(0xEFDE), USB(0xE1E0), PHOTO(0xE3F4), LOCK(0xE899), TIMER(0xE425), HIDDEN(0xE8F5),
    CALENDAR(0xEBCC), PIANO(0xE521), HI_RES(0xE024), MIC(0xE31D), UNPLAYED(0xE440),
    // Where a song plays from (docs/specs/M10_ONLINE.md, D2), and an artist's radio.
    CLOUD(0xE2BD), CLOUD_DONE(0xE2BF), CLOUD_OFF(0xE2C1), DOWNLOADING(0xF001), RADIO(0xE03E);

    val text: String = String(Character.toChars(codepoint))

    /** The small gesture this glyph plays when its button is tapped. */
    val motion: GlyphMotion get() = when (this) {
        SEARCH -> GlyphMotion.SWING
        SETTINGS, ADD, CLOSE, TUNE -> GlyphMotion.TURN
        REFRESH, REPEAT, REPEAT_ONE, MIX, RADIO -> GlyphMotion.SPIN
        NEXT, PLAY_NEXT, CHEVRON -> GlyphMotion.NUDGE_RIGHT
        PREVIOUS, BACK -> GlyphMotion.NUDGE_LEFT
        DOWNLOAD, COLLAPSE, DOWN -> GlyphMotion.DROP
        UP, ADD_TO_QUEUE, PLAYLIST_ADD -> GlyphMotion.RISE
        SLEEP, DELETE, TIMER, HISTORY -> GlyphMotion.WOBBLE
        SHUFFLE -> GlyphMotion.FLIP
        else -> GlyphMotion.POP
    }
}

/** Tap gestures for icons; each glyph picks the one that reads as its own (see [Glyph.motion]). */
enum class GlyphMotion { POP, SWING, TURN, SPIN, NUDGE_RIGHT, NUDGE_LEFT, DROP, RISE, WOBBLE, FLIP }

private object SymbolFont {
    @Volatile private var typeface: Typeface? = null
    fun get(ctx: android.content.Context): Typeface =
        typeface ?: ctx.resources.getFont(R.font.material_symbols).also { typeface = it }
}

/**
 * A Material Symbol that moves like the ones in Google's apps: [filled] fills it in, [bold]
 * thickens the stroke (a chosen or pressed control), and becoming filled or bold gives it a small
 * spring "pop". It animates only when one of those changes; nothing runs while it sits still.
 */
@Composable
fun Symbol(
    glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = LocalContentColor.current,
    filled: Boolean = false, bold: Boolean = false,
    /** Bumped by the icon's button on each tap: the glyph plays its own small gesture (see [GlyphMotion]). */
    kick: Int = 0,
) {
    val ctx = LocalContext.current
    val motion = MaterialTheme.motionScheme
    val fill by animateFloatAsState(if (filled) 1f else 0f, motion.defaultEffectsSpec(), label = "fill")
    val weight by animateFloatAsState(if (bold) 600f else 400f, motion.fastSpatialSpec(), label = "weight")
    val pop = remember { Animatable(1f) }
    val emphasis = filled || bold
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(emphasis) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (emphasis) { pop.snapTo(0.82f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    }
    // A tap's gesture, as Google's apps give their icons: the lens swings, the gear turns, an arrow
    // nudges the way it points. One spring, so it settles the same way everywhere; none in Battery Saver.
    val tap = remember { Animatable(1f) }
    val still = LocalPowerSave.current
    LaunchedEffect(kick) { if (kick > 0 && !still) { tap.snapTo(0f); tap.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f)) } }
    val gesture = glyph.motion
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = SymbolFont.get(ctx); textAlign = Paint.Align.CENTER } }
    Canvas(modifier.size(size).graphicsLayer {
        val t = tap.value; val left = 1f - t
        val press = if (gesture == GlyphMotion.POP) 0.8f + 0.2f * t else 1f
        scaleX = pop.value * press; scaleY = pop.value * press
        when (gesture) {
            GlyphMotion.SWING -> rotationZ = left * -30f
            GlyphMotion.TURN -> rotationZ = left * -90f
            GlyphMotion.SPIN -> rotationZ = left * -360f
            GlyphMotion.NUDGE_RIGHT -> translationX = left * 5.dp.toPx()
            GlyphMotion.NUDGE_LEFT -> translationX = left * -5.dp.toPx()
            GlyphMotion.DROP -> translationY = left * -6.dp.toPx()
            GlyphMotion.RISE -> translationY = left * 5.dp.toPx()
            GlyphMotion.WOBBLE -> rotationZ = kotlin.math.sin(t * Math.PI.toFloat() * 3f) * left * 18f
            GlyphMotion.FLIP -> scaleX = pop.value * (1f - 2f * left).coerceIn(-1f, 1f).let { if (it == 0f) 0.01f else it }
            GlyphMotion.POP -> Unit
        }
    }) {
        // 960 units per em with the icon standing on the baseline: an em-sized glyph drawn with
        // its baseline at the bottom fills the box exactly.
        val em = this.size.minDimension
        paint.textSize = em
        paint.color = color.toArgb()
        paint.fontVariationSettings = String.format(Locale.ROOT, "'FILL' %.3f, 'wght' %.1f", fill, weight)
        drawContext.canvas.nativeCanvas.drawText(glyph.text, this.size.width / 2, (this.size.height + em) / 2, paint)
    }
}

/** An icon button whose glyph plays its tap gesture: content gets the tap count for [Symbol]'s kick. */
@Composable
internal fun KickIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable (Int) -> Unit) {
    var kick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.material3.IconButton(onClick = { kick++; onClick() }, modifier = modifier, enabled = enabled) { content(kick) }
}

/** The tonal variant of [KickIconButton]. */
@Composable
internal fun KickFilledTonalIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable (Int) -> Unit) {
    var kick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.material3.FilledTonalIconButton(onClick = { kick++; onClick() }, modifier = modifier, enabled = enabled) { content(kick) }
}
