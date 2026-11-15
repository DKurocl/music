@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import ua.olex.music.dsp.AutoEqProfile
import ua.olex.music.dsp.EqualizerBand
import ua.olex.music.dsp.FilterType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ua.olex.music.dsp.EqualizerSettings
import ua.olex.music.dsp.ReplayGainSettings
import ua.olex.music.dsp.ReplayGainMode
import ua.olex.music.dsp.SonyCorrection
import ua.olex.music.playback.EqualizerState
import java.util.Locale

/** One active output at a time; updates name their route so a Bluetooth handover cannot alter another profile. */
@Composable
internal fun EqualizerSheet(state: EqualizerState, connected: Boolean, error: String?,
    onApply: (String, EqualizerSettings) -> Unit, onDismiss: () -> Unit,
    loudness: ReplayGainSettings = ReplayGainSettings(), onLoudness: (ReplayGainSettings) -> Unit = {}) {
    var draft by remember(state.routeKey, state.settings) { mutableStateOf(state.settings) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember(state.routeKey) { mutableStateOf<Int?>(null) }
    var importError by remember(state.routeKey) { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    // The captured route key keeps a delayed document selection from changing a different output.
    var importRoute by remember { mutableStateOf<String?>(null) }
    val editable = connected && state.routeKey != "none"
    fun apply(settings: EqualizerSettings) { draft = settings; onApply(state.routeKey, settings) }
    val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val route = importRoute
        importRoute = null
        if (uri != null && route == state.routeKey && editable) scope.launch {
            importing = true; importError = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(65_537) }
                        ?: throw IllegalArgumentException("Could not open profile")
                    require(bytes.size <= 65_536)
                    AutoEqProfile.parse(bytes.toString(Charsets.UTF_8), "Imported AutoEq")
                }
                // The service also rejects a stale route if it changed during IO.
                onApply(route, imported.copy(channels = draft.channels))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { importError = "Couldn't import this profile. Use AutoEq parametric TXT with Preamp and PK/LSC/HSC filters." }
            finally { importing = false }
        }
    }
    editor?.let { index -> draft.bands.getOrNull(index)?.let { band ->
        EqualizerBandDialog(band, onDismiss = { editor = null }, onApply = { updated ->
            apply(draft.copy(profile = "Custom", bands = draft.bands.mapIndexed { i, old -> if (i == index) updated else old }))
            editor = null
        })
    } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Equalizer", style = MaterialTheme.typography.headlineMedium)
            ReplayGainControls(loudness, connected, onLoudness)
            HorizontalDivider()
            Text(state.routeName, style = MaterialTheme.typography.titleMedium)
            Text("Saved separately for this output. Processing is off until you enable it.", style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (draft.enabled) "Processing on" else "Processing off", style = MaterialTheme.typography.titleMedium)
                Switch(checked = draft.enabled, enabled = editable, onCheckedChange = { apply(draft.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = "Enable equalizer" })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = editable, onClick = { apply(EqualizerSettings(enabled = draft.enabled, channels = draft.channels)) }) { Text("Flat") }
                OutlinedButton(enabled = editable && !importing, onClick = {
                    importRoute = state.routeKey; importProfile.launch(arrayOf("text/plain", "application/octet-stream"))
                }) { Text(if (importing) "Importing..." else "Import AutoEq") }
                if (state.sony) Button(enabled = editable, onClick = { apply(SonyCorrection.settings.copy(channels = draft.channels)) }) { Text("Sony correction") }
            }
            if (state.sony) Text("Sony correction uses AutoEq with Rtings B&K 5128 measurements. Fit and taste vary. Turn off Sony's own EQ first to avoid applying two corrections.", style = MaterialTheme.typography.bodySmall)
            Text(draft.profile, style = MaterialTheme.typography.labelLarge)
            Text("Preamp ${String.format(Locale.ROOT, "%.1f", draft.preampDb)} dB")
            Slider(value = draft.preampDb.toFloat(), valueRange = -36f..12f, enabled = editable,
                onValueChange = { draft = draft.copy(preampDb = it.toDouble()) }, onValueChangeFinished = { onApply(state.routeKey, draft) },
                modifier = Modifier.semantics { contentDescription = "Equalizer preamp" })
            Text("Automatic headroom prevents boosted bands from overloading the output. This can make playback quieter.", style = MaterialTheme.typography.bodySmall)
            draft.bands.forEachIndexed { index, band ->
                val label = if (band.frequencyHz >= 1_000) "${String.format(Locale.ROOT, "%.1f", band.frequencyHz / 1_000)} kHz" else "${band.frequencyHz.toInt()} Hz"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = editable, onClick = { editor = index }) { Text(label) }
                    Text("${String.format(Locale.ROOT, "%+.1f", band.gainDb)} dB")
                }
                Slider(value = band.gainDb.toFloat(), valueRange = -24f..24f, enabled = editable,
                    onValueChange = { value -> draft = draft.copy(profile = "Custom", bands = draft.bands.mapIndexed { i, item -> if (i == index) item.copy(gainDb = value.toDouble()) else item }) },
                    onValueChangeFinished = { onApply(state.routeKey, draft) }, modifier = Modifier.semantics { contentDescription = "Gain at $label" })
            }
            HorizontalDivider()
            Text("Channels", style = MaterialTheme.typography.titleLarge)
            val mix = draft.channels
            Text(when { mix.balance == 0f -> "Balance centre"; mix.balance < 0 -> "Balance ${(-mix.balance * 100).toInt()}% left"; else -> "Balance ${(mix.balance * 100).toInt()}% right" })
            Slider(value = mix.balance, valueRange = -1f..1f, enabled = editable,
                onValueChange = { v -> draft = draft.copy(channels = mix.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v)) },
                onValueChangeFinished = { onApply(state.routeKey, draft) }, modifier = Modifier.semantics { contentDescription = "Balance" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mono", style = MaterialTheme.typography.titleMedium)
                Switch(checked = mix.mono, enabled = editable, onCheckedChange = { apply(draft.copy(channels = mix.copy(mono = it))) },
                    modifier = Modifier.semantics { contentDescription = "Mono" })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Swap left and right", style = MaterialTheme.typography.titleMedium)
                Switch(checked = mix.swap, enabled = editable, onCheckedChange = { apply(draft.copy(channels = mix.copy(swap = it))) },
                    modifier = Modifier.semantics { contentDescription = "Swap left and right" })
            }
            Text("Works with the equalizer on or off, for this output only. Centre, stereo and unswapped leave the sound untouched.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Normalization is global, independent of per-output headphone correction. */
@Composable
private fun ReplayGainControls(settings: ReplayGainSettings, enabled: Boolean, onApply: (ReplayGainSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    fun apply(value: ReplayGainSettings) { draft = value; onApply(value) }
    Text("ReplayGain", style = MaterialTheme.typography.titleLarge)
    Text("Uses file loudness tags. Album keeps the intended level differences within an album; Track suits shuffled listening. Untagged files keep their original level.", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReplayGainMode.entries.forEach { mode ->
            FilterChip(selected = draft.mode == mode, enabled = enabled, onClick = { apply(draft.copy(mode = mode)) },
                label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) })
        }
    }
    Text("ReplayGain preamp ${String.format(Locale.ROOT, "%+.1f", draft.preampDb)} dB")
    Slider(value = draft.preampDb.toFloat(), valueRange = -24f..12f, enabled = enabled && draft.mode != ReplayGainMode.OFF,
        onValueChange = { draft = draft.copy(preampDb = it.toDouble()) }, onValueChangeFinished = { onApply(draft) },
        modifier = Modifier.semantics { contentDescription = "ReplayGain preamp" })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Prevent clipping", style = MaterialTheme.typography.titleMedium)
        Switch(checked = draft.preventClipping, enabled = enabled && draft.mode != ReplayGainMode.OFF,
            onCheckedChange = { apply(draft.copy(preventClipping = it)) },
            modifier = Modifier.semantics { contentDescription = "Prevent ReplayGain clipping" })
    }
    Text("Without a known peak, clipping protection prevents positive gain. EQ can reserve additional headroom.", style = MaterialTheme.typography.bodySmall)
}

/** Validates the complete filter before replacing it; changing output closes the enclosing editor. */
@Composable
private fun EqualizerBandDialog(band: EqualizerBand, onDismiss: () -> Unit, onApply: (EqualizerBand) -> Unit) {
    var frequency by remember(band) { mutableStateOf(band.frequencyHz.toString()) }
    var q by remember(band) { mutableStateOf(band.q.toString()) }
    var type by remember(band) { mutableStateOf(band.type) }
    val hz = frequency.toDoubleOrNull()?.takeIf { it.isFinite() && it in 10.0..24_000.0 }
    val quality = q.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..12.0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit filter") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = frequency, onValueChange = { frequency = it }, label = { Text("Frequency (Hz)") },
                supportingText = { Text("10 to 24000 Hz") }, isError = hz == null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Q") },
                supportingText = { Text("0.1 to 12") }, isError = quality == null, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterType.entries.forEach { value ->
                    FilterChip(selected = type == value, onClick = { type = value }, label = {
                        Text(when (value) { FilterType.PEAK -> "Peak"; FilterType.LOW_SHELF -> "Low shelf"; FilterType.HIGH_SHELF -> "High shelf" })
                    })
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = hz != null && quality != null, onClick = {
            if (hz != null && quality != null) onApply(band.copy(frequencyHz = hz, q = quality, type = type))
        }) { Text("Apply") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
