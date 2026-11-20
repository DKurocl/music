@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import ua.olex.music.playback.AudioDiagnostics
import ua.olex.music.playback.OutputRoute

/**
 * The path a song takes, as a chain (Poweramp's audio info, in M3E): File, Decoder, Processing,
 * App output, Device. Each step has its icon in a shape and what is known about it; a line joins
 * them. The summary covers the app's output and processing only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AudioPathSheet(format: String?, route: OutputRoute?, audio: AudioDiagnostics, onDismiss: () -> Unit, onOutput: () -> Unit,
    onEqualizer: (() -> Unit)? = null, fileRateHz: Int? = null, processing: List<String> = emptyList(), sourceLabel: String = "File input", controls: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val outputRateHz = audio.sampleRateHz
    val rateChanged = fileRateHz != null && outputRateHz != null && fileRateHz != outputRateHz
    val outputReported = audio.encoding != null || outputRateHz != null || audio.offload
    val summary = when {
        !outputReported -> "Output unavailable"
        audio.offload -> "Offloaded"
        processing.isNotEmpty() -> "App DSP on"
        rateChanged -> "Rate changed"
        else -> "No app DSP"
    }
    AppSheet(onDismiss, cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audio path", style = MaterialTheme.typography.titleLargeEmphasized)
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(summary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            controls()
            Column {
                Step(Glyph.NOTE, sourceLabel, listOf(format ?: "Format not reported"), MaterialShapes.Cookie6Sided, cs.primaryContainer, cs.onPrimaryContainer)
                Step(Glyph.WAVE, "Decoder", listOf(audio.decoder?.let(::decoderName) ?: "Not active"), MaterialShapes.Circle, cs.secondaryContainer, cs.onSecondaryContainer)
                Step(Glyph.TUNE, "Processing", processing.ifEmpty { listOf("Equalizer, channel processing and ReplayGain are off") }, MaterialShapes.Clover4Leaf,
                    if (processing.isEmpty()) cs.surfaceContainerHighest else cs.tertiaryContainer, if (processing.isEmpty()) cs.onSurfaceVariant else cs.onTertiaryContainer)
                Step(Glyph.SPEAKER, "App output", listOfNotNull(
                    listOfNotNull(audio.encoding, outputRateHz?.let(::formatKhz), audio.channels?.let { "$it channels" }).joinToString(" · ").ifEmpty { "Output format not reported" },
                    if (fileRateHz != null && outputRateHz != null && rateChanged) "${formatKhz(fileRateHz)} → ${formatKhz(outputRateHz)}" else null,
                    if (audio.offload) "Decoded by the phone's audio chip (battery saver)" else null,
                ), MaterialShapes.Pill, cs.secondaryContainer, cs.onSecondaryContainer)
                Step(route.glyphForPath(), "Device", listOfNotNull(
                    route?.name?.takeIf { it.isNotBlank() } ?: route?.kind?.name?.lowercase()?.replace('_', ' ') ?: "Not reported",
                    if (route?.kind in listOf(OutputRoute.Kind.BLUETOOTH, OutputRoute.Kind.LE_AUDIO)) "Bluetooth codec is chosen by Android and the earbuds" else null,
                    "Android's mixer may still convert the stream before the device",
                ), MaterialShapes.Sunny, cs.primaryContainer, cs.onPrimaryContainer, last = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Verdict("Sample rates", if (outputRateHz == null || fileRateHz == null) "Unknown" else if (rateChanged) "Different" else "Match", Modifier.weight(1f))
                Verdict("Processing", if (processing.isEmpty()) "Off" else "${processing.size} on", Modifier.weight(1f))
            }
            // Options in one rounded group: rows touch, the group breathes.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val deviceName = route?.name?.takeIf { it.isNotBlank() && route.kind != OutputRoute.Kind.SPEAKER }
            if (deviceName != null) {
                var resume by androidx.compose.runtime.remember(deviceName) { androidx.compose.runtime.mutableStateOf(deviceName in ua.olex.music.playback.HeadsetRules.resumeDevices(context)) }
                OptionRow("Play when $deviceName connects", "Disconnecting always pauses", resume, first = true) { resume = it; ua.olex.music.playback.HeadsetRules.setResume(context, deviceName, it) }
            }
            var bitPerfect by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ua.olex.music.playback.UsbBitPerfect.get(context)) }
            OptionRow("Bit-perfect USB output", "Each song at its own rate to a USB DAC", bitPerfect, first = deviceName == null) { bitPerfect = it; ua.olex.music.playback.UsbBitPerfect.set(context, it) }
            val prefs = androidx.compose.runtime.remember { context.getSharedPreferences(ua.olex.music.playback.UsbBitPerfect.PREFS, android.content.Context.MODE_PRIVATE) }
            var offload by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean(ua.olex.music.playback.PlaybackService.OFFLOAD_KEY, false)) }
            OptionRow("Battery saver", "Audio chip plays while the screen is off", offload, last = true) {
                offload = it; prefs.edit().putBoolean(ua.olex.music.playback.PlaybackService.OFFLOAD_KEY, it).apply() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onOutput, modifier = Modifier.height(48.dp)) { Text("Change output") }
                onEqualizer?.let { FilledTonalButton(onClick = it, modifier = Modifier.height(48.dp)) { Text("Equalizer") } }
            }
        }
    }
}

internal fun formatKhz(hz: Int): String =
    if (hz % 1_000 == 0) "${hz / 1_000} kHz" else "${"%.1f".format(java.util.Locale.ROOT, hz / 1_000.0)} kHz"

/** "c2.android.flac.decoder" → "Android FLAC decoder"; vendor codecs keep their name. */
private fun decoderName(raw: String): String {
    val m = Regex("""c2\.android\.(\w+)\.decoder""").find(raw) ?: return raw
    return "Android ${m.groupValues[1].uppercase()} decoder"
}

private fun OutputRoute?.glyphForPath(): Glyph = when (this?.kind) {
    OutputRoute.Kind.BLUETOOTH, OutputRoute.Kind.LE_AUDIO -> Glyph.BLUETOOTH
    OutputRoute.Kind.USB -> Glyph.USB
    OutputRoute.Kind.WIRED -> Glyph.HEADPHONES
    else -> Glyph.SPEAKER
}

/** One step of the chain: icon in its shape, a line down to the next step, title and facts. */
@Composable
private fun Step(glyph: Glyph, title: String, lines: List<String>, shape: androidx.graphics.shapes.RoundedPolygon,
    container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, last: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.size(44.dp).clip(shape.toShape()).background(container), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Symbol(glyph, size = 22.dp, color = content, filled = true)
            }
            if (!last) androidx.compose.foundation.layout.Box(Modifier.width(2.dp).weight(1f).padding(vertical = 4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.outlineVariant))
        }
        Column(Modifier.padding(start = 16.dp, bottom = if (last) 0.dp else 18.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(top = 10.dp))
            lines.forEachIndexed { i, line ->
                Text(line, style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = if (i == 0) cs.onSurface else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Verdict(label: String, value: String, modifier: Modifier) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMediumEmphasized)
        }
    }
}

/** A switch row in a rounded group, as in Settings. */
@Composable
private fun OptionRow(title: String, subtitle: String, on: Boolean, first: Boolean = false, last: Boolean = false, onChange: (Boolean) -> Unit) {
    val big = 24.dp; val small = 4.dp
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
            trailingContent = { AppSwitch(on, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}
