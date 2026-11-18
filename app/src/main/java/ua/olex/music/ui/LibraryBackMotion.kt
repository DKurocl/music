package ua.olex.music.ui

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
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                progress.floatValue = (if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1) * event.progress
            }
            action.value()
        } finally {
            progress.floatValue = 0f
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
