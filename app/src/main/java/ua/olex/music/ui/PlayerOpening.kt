package ua.olex.music.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ua.olex.music.playback.LastPlayedPreview
import ua.olex.music.playback.PlayerState

/** The player's last "Playing from" line, kept for the opening screen. */
internal object LastOpening {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("player_opening", android.content.Context.MODE_PRIVATE)
    fun from(c: android.content.Context): String? = prefs(c).getString("from", null)
    fun remember(c: android.content.Context, from: String?) {
        if (from != null && from != from(c)) prefs(c).edit().putString("from", from).apply()
    }
}

/** Use the real player geometry during restore so artwork and controls stay in place. */
@Composable
internal fun PlayerOpening(preview: LastPlayedPreview?, empty: Boolean, error: String?,
    onClose: () -> Unit, onRetry: () -> Unit, onPlay: () -> Unit = onRetry, onNext: () -> Unit = {}, onPrevious: () -> Unit = {}) {
    if (!empty && error == null) {
        // Play is live here: it reconnects and starts the restored queue.
        // Every control is live: a tap waits for the service and then does what it says.
        val state = PlayerState(connected = true, connecting = true, hasNext = true, hasPrevious = true, title = preview?.title.orEmpty(), artist = preview?.artist.orEmpty(), artworkUri = preview?.artworkUri)
        CompositionLocalProvider(LocalPlayerTravel provides null) {
            PlayerPage(state, format = null, route = null, favourite = false, position = { 0L },
                onClose = onClose, onToggle = onPlay, onNext = onNext, onPrevious = onPrevious, onSeek = {},
                onShuffle = {}, onRepeat = {}, onFavourite = null, onMore = null,
                onOutput = {}, onQueue = {}, onLyrics = {}, onAudioPath = {}, onTimer = {},
                // The last "Playing from" line the player showed, so nothing changes once it loads.
                from = LastOpening.from(androidx.compose.ui.platform.LocalContext.current) ?: " ", restoring = true)
        }
    } else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            EmptyState(Glyph.NOTE, if (error == null) "Nothing queued" else "Couldn't open the player",
                error ?: "Choose a song to start listening.", modifier = Modifier.align(Alignment.Center)) {
                Button(onClick = if (error == null) onClose else onRetry) {
                    Text(if (error == null) "Browse music" else "Retry")
                }
            }
            KickIconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                .semantics { contentDescription = "Close player" }) { kick -> Symbol(Glyph.COLLAPSE, kick = kick) }
        }
    }
}
