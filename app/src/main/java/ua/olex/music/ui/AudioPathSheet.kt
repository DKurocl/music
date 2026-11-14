@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
