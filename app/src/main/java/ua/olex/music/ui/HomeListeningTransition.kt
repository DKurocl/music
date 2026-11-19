@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection

/** The opaque destination moves over Home, which is also the predictive Back preview. */
@Composable
internal fun HomeListeningTransition(listening: Boolean, gestureCommitted: Boolean, modifier: Modifier,
    backProgress: () -> Float = { 0f }, content: @Composable (Boolean) -> Unit) {
    val still = LocalPowerSave.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val move = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val background = MaterialTheme.colorScheme.surface
    Box(modifier.fillMaxSize().clipToBounds().background(background)) {
        Box(Modifier.fillMaxSize().then(if (listening) inactivePane else Modifier)) { content(false) }
        AnimatedVisibility(listening,
            enter = if (still) EnterTransition.None else slideInHorizontally(move) { direction * it },
            exit = if (still || gestureCommitted) ExitTransition.None else slideOutHorizontally(move) { direction * it },
            label = "Listening page") {
            Box(Modifier.fillMaxSize().libraryBackMotion(backProgress).background(background)
                .then(if (listening) Modifier else inactivePane)) { content(true) }
        }
    }
}

private val inactivePane = Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}
