@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Keep both pages on screen for the handoff, anchored directly below the toolbar control. */
@Composable
internal fun HomeListeningTransition(listening: Boolean, anchor: Float, gestureCommitted: Boolean, modifier: Modifier,
    content: @Composable (Boolean) -> Unit) {
    val still = LocalPowerSave.current
    val move = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val pivot = TransformOrigin(anchor, 0f)
    AnimatedContent(listening, modifier.fillMaxSize().clipToBounds(), transitionSpec = {
        when {
            still || (!targetState && gestureCommitted) -> EnterTransition.None togetherWith ExitTransition.None
            targetState -> scaleIn(move, initialScale = 0.93f, transformOrigin = pivot) togetherWith
                (scaleOut(move, targetScale = 0.985f, transformOrigin = pivot) + ExitTransition.KeepUntilTransitionsFinished)
            else -> scaleIn(move, initialScale = 0.985f, transformOrigin = pivot) togetherWith
                scaleOut(move, targetScale = 0.93f, transformOrigin = pivot)
        }.apply { targetContentZIndex = if (targetState) 1f else -1f }
    }, label = "Home and listening") { shown ->
        // The departing page can still be above Home while it fades. Its controls are inactive.
        val input = if (shown == listening) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        val mask = if (shown) {
            val reveal = transition.animateFloat(transitionSpec = {
                if (still || (!listening && gestureCommitted)) snap()
                else tween(if (targetState == EnterExitState.Visible) 310 else 225)
            }, label = "Listening surface") { if (it == EnterExitState.Visible) 1f else 0f }
            Modifier.graphicsLayer {
                val fraction = reveal.value
                clip = fraction < 1f
                if (clip) shape = ListeningWindow(anchor, fraction)
            }
        } else Modifier
        Box(Modifier.fillMaxSize().then(mask).background(MaterialTheme.colorScheme.surface).then(input)) { content(shown) }
    }
}

// Keep both sets of text opaque. Only the growing edge separates the two pages.
private class ListeningWindow(val anchor: Float, val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val collapsed = with(density) { 44.dp.toPx() }.coerceAtMost(size.minDimension)
        val remaining = 1f - progress
        val left = ((size.width * anchor - collapsed / 2).coerceIn(0f, size.width - collapsed)) * remaining
        val right = left + size.width * progress + collapsed * remaining
        val bottom = size.height * progress + collapsed * remaining
        return Outline.Rounded(RoundRect(Rect(left, 0f, right, bottom), CornerRadius(collapsed / 2 * remaining)))
    }
}
