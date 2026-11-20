@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
        AppChip(selected = linked, onClick = {
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

/** Shows the current route. Android owns switching and media volume for the selected device. */
@Composable
internal fun PlaybackDevices(route: OutputRoute?, onSwitch: () -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val name = route?.label()
    var message by remember(route) { mutableStateOf<String?>(null) }
    fun openSettings(action: String, failure: String) {
        runCatching { context.startActivity(Intent(action)) }.onFailure { message = failure }
    }
    AppSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Playback device", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineSmallEmphasized)
            Surface(shape = RoundedCornerShape(28.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Symbol(route.glyph(), size = 26.dp, filled = true) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name ?: "Output unavailable", style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        val connection = when (route?.kind) {
                            OutputRoute.Kind.BLUETOOTH -> "Bluetooth"
                            OutputRoute.Kind.LE_AUDIO -> "Bluetooth LE Audio"
                            OutputRoute.Kind.USB -> "USB audio"
                            OutputRoute.Kind.WIRED -> "Wired audio"
                            OutputRoute.Kind.REMOTE -> "Remote output"
                            else -> null
                        }
                        if (connection != null && connection != name) Text(connection, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (route != null) Symbol(Glyph.CHECK, size = 22.dp)
                }
            }
            Button(onClick = {
                haptics.tick()
                if (runCatching(onSwitch).getOrDefault(false)) onDismiss()
                else message = "The output picker is unavailable. You can change Bluetooth devices below."
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Symbol(Glyph.SPEAKER, size = 21.dp)
                Spacer(Modifier.width(8.dp))
                Text("Switch device")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings are unavailable.") },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Symbol(Glyph.BLUETOOTH, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Bluetooth")
                }
                FilledTonalButton(onClick = { openSettings(Settings.Panel.ACTION_VOLUME, "Volume controls are unavailable.") },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Symbol(Glyph.SPEAKER, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Volume")
                }
            }
            message?.let { Text(it, Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
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
