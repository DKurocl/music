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

/**
 * The seek bar: Android 16's media wave while the song plays, flat while paused or dragged. Drag
 * or tap anywhere on it to seek; spoken as a slider with the position.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WavySeekBar(progress: () -> Float, playing: Boolean, enabled: Boolean, description: String,
    onDrag: (Float?) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val amplitude by animateFloatAsState(if (playing && dragging == null) 1f else 0f, MaterialTheme.motionScheme.slowEffectsSpec(), label = "wave")
    val thumb by animateFloatAsState(if (dragging != null) 1f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "thumb")
    val shown = { dragging ?: progress() }
    Box(
        modifier.fillMaxWidth().height(40.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .semantics {
                contentDescription = "Position"; stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(shown(), 0f..1f)
                if (enabled) setProgress { onSeek(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSeek((it.x / width).coerceIn(0f, 1f)); haptics.tick() }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / width).coerceIn(0f, 1f); onDrag(dragging); haptics.tick() },
                    onDragEnd = { dragging?.let(onSeek); dragging = null; onDrag(null); haptics.tick() },
                    onDragCancel = { dragging = null; onDrag(null) },
                ) { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f); onDrag(dragging) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearWavyProgressIndicator(
            progress = shown, modifier = Modifier.fillMaxWidth().height(14.dp),
            color = cs.primary, trackColor = cs.surfaceContainerHighest,
            amplitude = { amplitude },
        )
        // The thumb: a tall pill like M3E sliders, grows while dragging.
        val density = LocalDensity.current
        val x = with(density) { (shown() * width).toDp() } - 3.dp
        Box(Modifier.offset(x = x).size(width = 6.dp, height = (20 + 12 * thumb).dp).clip(RoundedCornerShape(3.dp)).background(cs.primary))
    }
}

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
