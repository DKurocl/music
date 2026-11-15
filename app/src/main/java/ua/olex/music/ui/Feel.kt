package ua.olex.music.ui

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

/** One haptic vocabulary for the whole app (docs/DESIGN_SYSTEM.md). */
class Haptics(private val view: View) {
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
    fun threshold() = perform(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
    fun tick() = perform(HapticFeedbackConstants.SEGMENT_TICK)
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
    fun reject() = perform(HapticFeedbackConstants.REJECT)
    private fun perform(constant: Int) { view.performHapticFeedback(constant) }
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
    } else androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = containerColor, sheetGesturesEnabled = gestures, content = content)
}

/** Room the floating mini player takes at the bottom of a page, so lists can scroll under it to their end. */
val LocalPlayerInset = staticCompositionLocalOf { 0.dp }

/**
 * A light squish while pressed (M3E's "press" feedback): things you tap give a little, then spring
 * back. It only watches the touch, so clicks and scrolling are unaffected.
 */
fun Modifier.squish(amount: Float = 0.04f): Modifier = composed {
    var pressed by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f - amount else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "squish")
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * The icon that was just switched on gives a little hop (as Gboard animates only the tab you chose):
 * one icon at a time, only at the moment it becomes active.
 */
fun Modifier.hopWhen(active: Boolean): Modifier = composed {
    val hop = remember { androidx.compose.animation.core.Animatable(1f) }
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(active) {
        if (first) { first = false; return@LaunchedEffect }
        if (active) { hop.snapTo(0.8f); hop.animateTo(1f, androidx.compose.animation.core.spring(0.4f, 600f)) }
    }
    graphicsLayer { scaleX = hop.value; scaleY = hop.value }
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
