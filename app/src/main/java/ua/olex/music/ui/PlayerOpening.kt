package ua.olex.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.music.model.ArtworkPlan
import ua.olex.music.playback.LastPlayedPreview

/** First frame for system entry points while the service restores the real queue. */
@Composable
internal fun PlayerOpening(preview: LastPlayedPreview?, empty: Boolean, error: String?,
    onClose: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.surface).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Final)
        }
    }.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
            KickIconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close player" }) { kick ->
                Symbol(Glyph.BACK, kick = kick)
            }
        }
        if (empty || error != null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(Glyph.NOTE, if (error == null) "Nothing queued" else "Couldn't open the player",
                    error ?: "Choose a song to start listening.") {
                    Button(onClick = if (error == null) onClose else onRetry) {
                        Text(if (error == null) "Browse music" else "Retry")
                    }
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val side = minOf(maxWidth, maxHeight, 380.dp)
                if (preview?.artworkUri != null) {
                    PlayerArtwork(preview.artworkUri, Modifier.size(side), 32.dp, ArtworkPlan.LARGE)
                } else {
                    Box(Modifier.size(side).clip(RoundedCornerShape(32.dp)).background(cs.surfaceContainerHigh))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp)) {
                preview?.let {
                    Text(it.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artist, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                Text("Opening player…", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                if (!LocalPowerSave.current) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.weight(0.7f))
        }
    }
}
