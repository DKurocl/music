@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.playback.PlayerState

/** The edge actions have fixed slots, including while the lyrics control exits. */
@Composable
internal fun PlayerHeader(
    state: PlayerState, from: String?, showLyricsTools: Boolean,
    onClose: () -> Unit, onOpenOrigin: (() -> Unit)?, onLyricsTools: (() -> Unit)?, onMore: (() -> Unit)?,
) {
    val side = if (onLyricsTools != null) 96.dp else 48.dp
    val lyricsVisible = showLyricsTools && onLyricsTools != null
    val motion = MaterialTheme.motionScheme
    val saving = LocalPowerSave.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(side)) {
            KickIconButton(onClose, Modifier.semantics { contentDescription = "Close player" }) { kick ->
                Symbol(Glyph.COLLAPSE, size = 28.dp, kick = kick)
            }
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .then(if (onOpenOrigin != null) Modifier.clickable(onClickLabel = if (from == null) "Open album" else "Open playing source", onClick = onOpenOrigin) else Modifier)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                from ?: state.album ?: if (state.mediaId?.startsWith("yt:") == true) "Your queue" else "Your library",
                style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLyricsTools != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                lyricsVisible,
                enter = if (saving) androidx.compose.animation.EnterTransition.None else fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.88f),
                exit = if (saving) androidx.compose.animation.ExitTransition.None else fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastEffectsSpec(), targetScale = 0.88f),
            ) {
                if (lyricsVisible) {
                    KickIconButton(onLyricsTools, Modifier.semantics { contentDescription = "Lyrics options" }) { kick ->
                        Symbol(Glyph.TUNE, kick = kick)
                    }
                } else {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Symbol(Glyph.TUNE) }
                }
            }
        }
        KickIconButton(onClick = { onMore?.invoke() }, enabled = onMore != null, modifier = Modifier.semantics { contentDescription = "Song actions" }) { kick ->
            Symbol(Glyph.MORE, kick = kick)
        }
    }
}
