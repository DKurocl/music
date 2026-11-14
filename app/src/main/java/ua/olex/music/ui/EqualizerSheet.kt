@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ua.olex.music.dsp.EqualizerSettings
import ua.olex.music.dsp.SonyCorrection
import ua.olex.music.playback.EqualizerState
import java.util.Locale

/** One active output at a time; updates name their route so a Bluetooth handover cannot alter another profile. */
@Composable
internal fun EqualizerSheet(state: EqualizerState, connected: Boolean, error: String?,
    onApply: (String, EqualizerSettings) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(state.routeKey, state.settings) { mutableStateOf(state.settings) }
    val editable = connected && state.routeKey != "none"
    fun apply(settings: EqualizerSettings) { draft = settings; onApply(state.routeKey, settings) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Equalizer", style = MaterialTheme.typography.headlineMedium)
            Text(state.routeName, style = MaterialTheme.typography.titleMedium)
            Text("Saved separately for this output. Processing is off until you enable it.", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (draft.enabled) "Processing on" else "Processing off", style = MaterialTheme.typography.titleMedium)
                Switch(checked = draft.enabled, enabled = editable, onCheckedChange = { apply(draft.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = "Enable equalizer" })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = editable, onClick = { apply(EqualizerSettings(enabled = draft.enabled)) }) { Text("Flat") }
                if (state.sony) Button(enabled = editable, onClick = { apply(SonyCorrection.settings) }) { Text("Sony correction") }
            }
            if (state.sony) Text("Sony correction uses AutoEq with Rtings B&K 5128 measurements. Fit and taste vary. Turn off Sony's own EQ first to avoid applying two corrections.", style = MaterialTheme.typography.bodySmall)
            Text(draft.profile, style = MaterialTheme.typography.labelLarge)
            Text("Preamp ${String.format(Locale.ROOT, "%.1f", draft.preampDb)} dB")
            Slider(value = draft.preampDb.toFloat(), valueRange = -24f..0f, enabled = editable,
                onValueChange = { draft = draft.copy(preampDb = it.toDouble()) }, onValueChangeFinished = { onApply(state.routeKey, draft) },
                modifier = Modifier.semantics { contentDescription = "Equalizer preamp" })
            Text("Automatic headroom prevents boosted bands from overloading the output. This can make playback quieter.", style = MaterialTheme.typography.bodySmall)
            draft.bands.forEachIndexed { index, band ->
                val label = if (band.frequencyHz >= 1_000) "${String.format(Locale.ROOT, "%.1f", band.frequencyHz / 1_000)} kHz" else "${band.frequencyHz.toInt()} Hz"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label)
                    Text("${String.format(Locale.ROOT, "%+.1f", band.gainDb)} dB")
                }
                Slider(value = band.gainDb.toFloat(), valueRange = -12f..12f, enabled = editable,
                    onValueChange = { value -> draft = draft.copy(profile = "Custom", bands = draft.bands.mapIndexed { i, item -> if (i == index) item.copy(gainDb = value.toDouble()) else item }) },
                    onValueChangeFinished = { onApply(state.routeKey, draft) }, modifier = Modifier.semantics { contentDescription = "Gain at $label" })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
