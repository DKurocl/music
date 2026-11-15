@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ua.olex.music.playback.AudioDiagnostics
import ua.olex.music.playback.OutputRoute

/** Separates observed app output from the system mixer and Bluetooth link, which are not measured here. */
@Composable
internal fun AudioPathSheet(format: String?, route: OutputRoute?, audio: AudioDiagnostics, onDismiss: () -> Unit, onOutput: () -> Unit, onEqualizer: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Audio path", style = MaterialTheme.typography.headlineSmall)
            PathDetail("File", format ?: "Format not reported")
            PathDetail("Decoder", audio.decoder ?: "No active decoder reported")
            PathDetail("App audio output", listOfNotNull(audio.encoding, audio.sampleRateHz?.let { "$it Hz" }, audio.channels?.let { "$it channels" }).joinToString(" · ").ifEmpty { "No active AudioTrack reported" })
            if (audio.offload) Text("Hardware audio offload is active.")
            PathDetail("Android output device", route?.name?.takeIf { it.isNotBlank() } ?: route?.kind?.name?.lowercase()?.replace('_', ' ') ?: "Not reported")
            Text("AudioTrack describes the app's output. Android may resample or process it before it reaches the device.", style = MaterialTheme.typography.bodyMedium)
            if (route?.kind in listOf(OutputRoute.Kind.BLUETOOTH, OutputRoute.Kind.LE_AUDIO)) {
                Text("Bluetooth codec and bitrate are not reported here. The system and connected device negotiate them.", style = MaterialTheme.typography.bodyMedium)
            }
            // USB DACs only: the mixer runs at the song's own rate, other sounds are not mixed in.
            val context = androidx.compose.ui.platform.LocalContext.current
            val deviceName = route?.name?.takeIf { it.isNotBlank() && route.kind != OutputRoute.Kind.SPEAKER }
            if (deviceName != null) {
                var resume by androidx.compose.runtime.remember(deviceName) { androidx.compose.runtime.mutableStateOf(deviceName in ua.olex.music.playback.HeadsetRules.resumeDevices(context)) }
                androidx.compose.material3.ListItem(
                    headlineContent = { Text("Play when $deviceName connects") },
                    supportingContent = { Text("Continues the queue as soon as these headphones connect. Disconnecting always pauses.") },
                    trailingContent = { androidx.compose.material3.Switch(resume, { resume = it; ua.olex.music.playback.HeadsetRules.setResume(context, deviceName, it) }) },
                )
            }
            var bitPerfect by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ua.olex.music.playback.UsbBitPerfect.get(context)) }
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Bit-perfect USB output") },
                supportingContent = { Text("For a USB DAC: the song's own sample rate, unmixed. Other sounds are muted on the DAC while it plays.") },
                trailingContent = { androidx.compose.material3.Switch(bitPerfect, { bitPerfect = it; ua.olex.music.playback.UsbBitPerfect.set(context, it) }) },
            )
            val prefs = androidx.compose.runtime.remember { context.getSharedPreferences(ua.olex.music.playback.UsbBitPerfect.PREFS, android.content.Context.MODE_PRIVATE) }
            var offload by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean(ua.olex.music.playback.PlaybackService.OFFLOAD_KEY, false)) }
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Battery saver") },
                supportingContent = { Text("The phone's audio chip decodes with the screen off. Used only while EQ, channel changes and ReplayGain are off, so the sound stays the same.") },
                trailingContent = { androidx.compose.material3.Switch(offload, { offload = it; prefs.edit().putBoolean(ua.olex.music.playback.PlaybackService.OFFLOAD_KEY, it).apply() }) },
            )
            onEqualizer?.let { TextButton(onClick = it) { Text("Equalizer") } }
            Button(onClick = onOutput) { Text("Change output") }
        }
    }
}

@Composable
private fun PathDetail(title: String, value: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
