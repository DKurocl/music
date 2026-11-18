@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package ua.olex.music.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

internal val LocalPlayerCoverTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalPlayerCoverVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
internal fun Modifier.playerCoverTransition(mediaId: String?, visibility: AnimatedVisibilityScope? = LocalPlayerCoverVisibility.current): Modifier {
    val transition = LocalPlayerCoverTransition.current ?: return this
    if (mediaId == null || visibility == null) return this
    return with(transition) {
        this@playerCoverTransition.sharedElement(rememberSharedContentState(key = "player-cover:$mediaId"), visibility)
    }
}
