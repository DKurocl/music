package ua.olex.music.ui

import androidx.compose.animation.togetherWith
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import android.os.VibrationEffect.Composition

/**
 * One haptic vocabulary for the whole app (docs/DESIGN_SYSTEM.md). Simple feedback uses Android's
 * own constants; the player's actions are small compositions of the vibrator's primitives (the
 * way Pixel's ringtones and system gestures are made), each shaped like what it does, falling back
 * to the nearest constant on a phone without them. All of it honours the system's touch-feedback
 * switch.
 */
class Haptics(private val view: View) {
    private val vibrator = view.context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    private val touch = android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH)
    // Only Composition.PRIMITIVE_* constants reach these two, from the calls below.
    @android.annotation.SuppressLint("WrongConstant")
    private fun supported(vararg p: Int) = vibrator?.areAllPrimitivesSupported(*p) == true

    /** Plays a composition of (primitive, scale, delay ms) when the vibrator has them all; else [fallback]. */
    @android.annotation.SuppressLint("WrongConstant")
    private fun compose(fallback: Int, vararg parts: Triple<Int, Float, Int>) {
        val v = vibrator
        if (v == null || !supported(*parts.map { it.first }.toIntArray()) || !view.isHapticFeedbackEnabled) { perform(fallback); return }
        val c = android.os.VibrationEffect.startComposition()
        parts.forEach { (p, scale, delay) -> c.addPrimitive(p, scale, delay) }
        runCatching { v.vibrate(c.compose(), touch) }
    }

    /**
     * On the Pixel's actuator (Android 16+), the same cue as a shaped envelope of intensity and
     * sharpness over time, as Pixel's haptic ringtones and Earbuds and VPN are made: smoother and
     * more physical than stacked primitives. Points are (intensity, sharpness, ms).
     */
    // Asked once, on first use; runCatching also covers test runtimes older than the app's minimum.
    private val shaped by lazy { runCatching { vibrator?.areEnvelopeEffectsSupported() == true }.getOrDefault(false) }

    private fun envelope(start: Float, vararg points: Triple<Float, Float, Long>): Boolean {
        val v = vibrator
        if (!shaped || v == null || !view.isHapticFeedbackEnabled) return false
        val effect = android.os.VibrationEffect.BasicEnvelopeBuilder().setInitialSharpness(start)
            .apply { points.forEach { (i, sh, ms) -> addControlPoint(i, sh, ms) } }.build()
        return runCatching { v.vibrate(effect, touch) }.isSuccess
    }

    /** Play: a quick rise into a click, something starting. */
    fun play() { if (!envelope(0.3f, Triple(0.35f, 0.4f, 25L), Triple(0.75f, 0.9f, 12L), Triple(0f, 0.9f, 25L)))
        compose(HapticFeedbackConstants.TOGGLE_ON, Triple(Composition.PRIMITIVE_QUICK_RISE, 0.45f, 0), Triple(Composition.PRIMITIVE_CLICK, 0.7f, 0)) }
    /** Pause: a quick fall settling on a low tick. */
    fun pause() { if (!envelope(0.6f, Triple(0.55f, 0.55f, 12L), Triple(0.15f, 0.3f, 50L), Triple(0f, 0.2f, 30L)))
        compose(HapticFeedbackConstants.TOGGLE_OFF, Triple(Composition.PRIMITIVE_QUICK_FALL, 0.45f, 0), Triple(Composition.PRIMITIVE_LOW_TICK, 0.7f, 20)) }
    /** Skip: two taps leaning the way it goes, light then firm forward, firm then light back. */
    fun skip(forward: Boolean) = if (forward) compose(HapticFeedbackConstants.SEGMENT_TICK, Triple(Composition.PRIMITIVE_TICK, 0.5f, 0), Triple(Composition.PRIMITIVE_CLICK, 0.85f, 35))
        else compose(HapticFeedbackConstants.SEGMENT_TICK, Triple(Composition.PRIMITIVE_CLICK, 0.85f, 0), Triple(Composition.PRIMITIVE_TICK, 0.5f, 35))
    /** Like: a slow bloom into a bright click; unlike: a soft fall. */
    fun like(on: Boolean) {
        if (on) { if (!envelope(0.2f, Triple(0.3f, 0.3f, 80L), Triple(0.9f, 1f, 10L), Triple(0f, 1f, 30L)))
            compose(HapticFeedbackConstants.CONFIRM, Triple(Composition.PRIMITIVE_SLOW_RISE, 0.3f, 0), Triple(Composition.PRIMITIVE_CLICK, 1f, 0)) }
        else if (!envelope(0.5f, Triple(0.35f, 0.4f, 10L), Triple(0f, 0.2f, 70L))) compose(HapticFeedbackConstants.TOGGLE_OFF, Triple(Composition.PRIMITIVE_QUICK_FALL, 0.3f, 0))
    }
    /** Something new spinning up: a mix, a cover swiped to the next song. */
    fun spin() = compose(HapticFeedbackConstants.GESTURE_END, Triple(Composition.PRIMITIVE_SPIN, 0.5f, 0))
    /** A detent while scrubbing; firmer when moving fast. */
    fun scrub(speed: Float) = compose(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, Triple(Composition.PRIMITIVE_LOW_TICK, (0.3f + speed * 0.5f).coerceIn(0.3f, 0.8f), 0))
    /** Reaching an end (start or end of the song): a soft thud. */
    fun edge() { if (!envelope(0.15f, Triple(0.6f, 0.15f, 25L), Triple(0f, 0.1f, 90L))) compose(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, Triple(Composition.PRIMITIVE_THUD, 0.6f, 0)) }

    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
    fun threshold() = perform(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
    fun tick() = perform(HapticFeedbackConstants.SEGMENT_TICK)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    private fun perform(constant: Int) { view.performHapticFeedback(constant) }
}

/**
 * Whether Battery Saver is on, following it as it changes. Decoration that costs power (a moving
 * cover's video, blur on distant lyrics) stops while it is on: that is when the owner chose battery.
 */
/** Battery Saver, read once at the root (one receiver) and handed down. */
val LocalPowerSave = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun rememberPowerSave(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
    var on by remember { androidx.compose.runtime.mutableStateOf(power?.isPowerSaveMode == true) }
    androidx.compose.runtime.DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { on = power?.isPowerSaveMode == true }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}

@Composable
fun rememberHaptics(): Haptics { val view = LocalView.current; return remember(view) { Haptics(view) } }

/** Short messages after a gesture ("Playing next"); provided by the app's scaffold. */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/** Tests and the design gallery draw sheets inline (a real sheet opens its own window, which a capture misses). */
val LocalInlineSheets = staticCompositionLocalOf { false }

/** A modal bottom sheet, or its content on a plain surface when [LocalInlineSheets] is set. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AppSheet(onDismiss: () -> Unit, containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    gestures: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (LocalInlineSheets.current) {
        androidx.compose.material3.Surface(color = containerColor, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(top = 22.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                content()
            }
        }
    // Open all the way: a half-open sheet hides its own main button below the screen's edge.
    } else androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = containerColor, sheetGesturesEnabled = gestures,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
}

/** Room the floating mini player takes at the bottom of a page, so lists can scroll under it to their end. */
val LocalPlayerInset = staticCompositionLocalOf { 0.dp }

/**
 * A light squish while pressed (M3E's "press" feedback): things you tap give a little, then spring
 * back. It only watches the touch, so clicks and scrolling are unaffected.
 */
fun Modifier.squish(amount: Float = 0.04f): Modifier = composed {
    if (LocalPowerSave.current) return@composed this
    var pressed by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f - amount else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "squish")
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            try { waitForUpOrCancellation() } finally { pressed = false }
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Resistance for a drag that has nowhere to go: the element follows a little, never far. */
fun rubber(offset: Float, limit: Float): Float = limit * (1f - 1f / (kotlin.math.abs(offset) / limit + 1f)) * kotlin.math.sign(offset)

/** An alert dialog, or its card drawn inline when [LocalInlineSheets] is set (gallery and tests). */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
) {
    if (!LocalInlineSheets.current) {
        androidx.compose.material3.AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
            dismissButton = dismissButton, icon = icon, title = title, text = text, shape = RoundedCornerShape(32.dp))
        return
    }
    androidx.compose.material3.Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.padding(24.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
            icon?.let { Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) { it() } }
            title?.let { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.headlineSmall) { Box(Modifier.padding(bottom = 16.dp)) { it() } } }
            text?.let { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium) { Box(Modifier.padding(bottom = 24.dp)) { it() } } }
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.End), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke(); confirmButton()
            }
        }
    }
}

/**
 * Patient loading, as Google's apps show it: nothing for a wait under [delayMs] (most waits), so a
 * fast answer never flashes a spinner; once shown, it stays at least [holdMs] so it never blinks.
 */
@Composable
internal fun rememberPatient(loading: Boolean, delayMs: Long = 250, holdMs: Long = 400): Boolean {
    var shown by remember { androidx.compose.runtime.mutableStateOf(false) }
    var since by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(loading) {
        if (loading) {
            if (!shown) { kotlinx.coroutines.delay(delayMs); shown = true; since = android.os.SystemClock.uptimeMillis() }
        } else if (shown) {
            val left = holdMs - (android.os.SystemClock.uptimeMillis() - since)
            if (left > 0) kotlinx.coroutines.delay(left)
            shown = false
        }
    }
    return shown
}

/**
 * Play/Pause that can wait: M3E's loading shape while [waiting], then the icon. The two trade
 * places with a short spring of scale and fade (the motion scheme's), never a cut.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayOrWait(waiting: Boolean, playing: Boolean, iconSize: androidx.compose.ui.unit.Dp, indicatorSize: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color, pressed: Boolean = false) {
    val still = LocalPowerSave.current
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    androidx.compose.animation.AnimatedContent(waiting, transitionSpec = {
        if (still) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
        else (androidx.compose.animation.fadeIn(effects) + androidx.compose.animation.scaleIn(spatial, 0.6f)) togetherWith
            (androidx.compose.animation.fadeOut(effects) + androidx.compose.animation.scaleOut(spatial, 0.6f))
    }, contentAlignment = Alignment.Center, label = "Play or wait") { wait ->
        if (wait) androidx.compose.material3.LoadingIndicator(Modifier.size(indicatorSize), color = color)
        else PlayPauseSymbol(playing, iconSize, pressed)
    }
}
