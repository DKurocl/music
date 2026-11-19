package ua.olex.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** A horizontal shared-axis transition between Home and its listening detail page. */
@Composable
internal fun HomeListeningTransition(listening: Boolean, gestureCommitted: Boolean, modifier: Modifier,
    content: @Composable (Boolean) -> Unit) {
    val still = LocalPowerSave.current
    val end = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val distance = with(LocalDensity.current) { 30.dp.roundToPx() } * end
    AnimatedContent(listening, modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surface), transitionSpec = {
        if (still || (!targetState && gestureCommitted)) EnterTransition.None togetherWith ExitTransition.None
        else {
            val shift = if (targetState) distance else -distance
            val move = tween<IntOffset>(260, easing = FastOutSlowInEasing)
            val enter = slideInHorizontally(move) { shift } + fadeIn(tween(175, delayMillis = 85))
            val exit = slideOutHorizontally(move) { -shift } + fadeOut(tween(85))
            enter togetherWith exit
        }
    }, label = "Home and listening") { shown ->
        val input = if (shown == listening) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
        Box(Modifier.fillMaxSize().then(input)) { content(shown) }
    }
}
