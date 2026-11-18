package ua.olex.music.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ua.olex.music.model.Track
import ua.olex.music.playback.OutputRoute
import ua.olex.music.playback.PlayerState

/** Media3 owns the actual parameters. The sliders only hold the value while dragging. */
@Composable
internal fun TempoAndPitch(state: PlayerState, onChange: (Float, Float) -> Unit) {
    if (!state.canSetPlaybackParameters) return
    var linked by rememberSaveable { mutableStateOf(false) }
    var speed by remember(state.speed) { mutableFloatStateOf(state.speed) }
    var pitch by remember(state.pitch) { mutableFloatStateOf(state.pitch) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Speed and pitch", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onChange(1f, 1f) }) { Text("Reset") }
        }
        FilterChip(selected = linked, onClick = {
            linked = !linked
            if (linked) { pitch = speed; onChange(speed, speed) }
        }, label = { Text("Link pitch to speed") })
        Text("Speed · ${String.format(java.util.Locale.ROOT, "%.2f", speed)}×")
        Slider(speed, { speed = it; if (linked) pitch = it }, valueRange = 0.5f..2f, steps = 29,
            onValueChangeFinished = { onChange(speed, pitch) })
        Text("Pitch · ${String.format(java.util.Locale.ROOT, "%.2f", pitch)}×")
        Slider(pitch, { pitch = it; if (linked) speed = it }, valueRange = 0.5f..2f, steps = 29,
            onValueChangeFinished = { onChange(speed, pitch) })
    }
}

@Composable
internal fun PlaybackDevices(route: OutputRoute?, onSwitch: () -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Playback device", style = MaterialTheme.typography.headlineSmall)
            ListItem(headlineContent = { Text(route?.name?.takeIf(String::isNotBlank) ?: route?.label() ?: "Output unavailable") },
                supportingContent = { Text("Current audio output") }, leadingContent = { Symbol(Glyph.HEADPHONES) })
            Button(onClick = { unavailable = !onSwitch() }) { Text("Choose output") }
            if (unavailable) Text("Android could not open the output picker.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth settings") }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.Panel.ACTION_VOLUME)) }
                }) { Text("Volume controls") }
            }
        }
    }
}

@Composable
internal fun MusicVideoSheet(track: Track, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as ua.olex.music.MusicApp).graph
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val clips by produceState<List<Track>?>(null, track.id, retry) {
        value = null
        failed = false
        try { value = graph.youTube.musicVideos(track.artist, track.title) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { failed = true; value = emptyList() }
    }
    AppSheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(20.dp)) {
            item { Text("Music videos", style = MaterialTheme.typography.headlineSmall) }
            val results = clips
            when {
                results == null -> item { PlaceholderRow() }
                failed -> item { ErrorNotice("Couldn't get music videos from YouTube.") { retry++ } }
                results.isEmpty() -> item { Text("No music videos found", Modifier.padding(vertical = 16.dp)) }
                else -> items(results, key = { it.id.value }) { video ->
                    Surface(onClick = {
                        val link = Uri.Builder().scheme("https").authority("www.youtube.com").path("watch")
                            .appendQueryParameter("v", video.id.value.removePrefix("yt:")).build()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link)); onDismiss() }
                            .onFailure { failed = true }
                    }) {
                        ListItem(headlineContent = { Text(video.title) }, supportingContent = { Text(video.artist) },
                            leadingContent = { Artwork(video.artworkUri, Modifier.size(52.dp), 10.dp) })
                    }
                }
            }
        }
    }
}
