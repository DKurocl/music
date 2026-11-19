@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Keep both pages on screen for the handoff, anchored directly below the toolbar control. */
@Composable
internal fun HomeListeningTransition(listening: Boolean, anchor: Float, gestureCommitted: Boolean, modifier: Modifier,
    content: @Composable (Boolean) -> Unit) {
    val still = LocalPowerSave.current
    val move = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val pivot = TransformOrigin(anchor, 0f)
    AnimatedContent(listening, modifier.fillMaxSize().clipToBounds(), transitionSpec = {
        when {
            still || (!targetState && gestureCommitted) -> EnterTransition.None togetherWith ExitTransition.None
            targetState -> (scaleIn(move, initialScale = 0.93f, transformOrigin = pivot) + fadeIn(fade, 0.12f)) togetherWith
                scaleOut(move, targetScale = 0.985f, transformOrigin = pivot)
            else -> scaleIn(move, initialScale = 0.985f, transformOrigin = pivot) togetherWith
                (scaleOut(move, targetScale = 0.93f, transformOrigin = pivot) + fadeOut(fade))
        }.apply { targetContentZIndex = if (targetState) 1f else -1f }
    }, label = "Home and listening") { shown ->
        // The departing page can still be above Home while it fades. Its controls are inactive.
        val input = if (shown == listening) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).then(input)) { content(shown) }
    }
}
