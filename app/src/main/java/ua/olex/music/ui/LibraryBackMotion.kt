package ua.olex.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** The content follows either edge and returns to its prior position if Back is cancelled. */
@Composable
internal fun rememberLibraryBack(enabled: Boolean, onCommit: () -> Unit): () -> Float {
    val progress = remember { mutableFloatStateOf(0f) }
    val action = rememberUpdatedState(onCommit)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var restore by remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
    PredictiveBackHandler(enabled) { events ->
        restore?.cancel()
        try {
            events.collect { event ->
                progress.floatValue = (if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1) * event.progress
            }
            progress.floatValue = 0f
            action.value()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            restore = scope.launch {
                androidx.compose.animation.core.animate(progress.floatValue, 0f,
                    animationSpec = androidx.compose.animation.core.tween(150)) { value, _ -> progress.floatValue = value }
            }
            throw cancelled
        }
    }
    return { progress.floatValue }
}

internal fun Modifier.libraryBackMotion(progress: () -> Float): Modifier = graphicsLayer {
    val signed = progress()
    val back = kotlin.math.abs(signed)
    translationX = 24.dp.toPx() * signed
    scaleX = 1f - 0.04f * back
    scaleY = scaleX
    clip = back > 0f
    if (clip) shape = RoundedCornerShape(24.dp * back)
}
