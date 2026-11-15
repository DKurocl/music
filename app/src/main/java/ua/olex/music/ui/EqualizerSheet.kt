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
    val cs = MaterialTheme.colorScheme
    // Three cards — Equalizer, Loudness, Channels — each with its switch or choice in its header,
    // one-line explanations, and the controls below. The output it belongs to is named on top.
    AppSheet(onDismiss, cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Sound", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Symbol(Glyph.HEADPHONES, size = 18.dp, filled = true); Text("  ${state.routeName}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
            error?.let { Text(it, color = cs.error, modifier = Modifier.padding(horizontal = 8.dp)) }
            importError?.let { Text(it, color = cs.error, modifier = Modifier.padding(horizontal = 8.dp)) }

            SoundCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Equalizer", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(if (draft.enabled) draft.profile else "Off · saved for this output", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = draft.enabled, enabled = editable, onCheckedChange = { apply(draft.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "Enable equalizer" })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.sony) FilterChip(selected = draft.profile == SonyCorrection.settings.profile, enabled = editable,
                        onClick = { apply(SonyCorrection.settings.copy(channels = draft.channels)) }, label = { Text("Sony correction") })
                    FilterChip(selected = draft.bands.all { it.gainDb == 0.0 }, enabled = editable,
                        onClick = { apply(EqualizerSettings(enabled = draft.enabled, channels = draft.channels)) }, label = { Text("Flat") })
                    AssistChip(enabled = editable && !importing, onClick = {
                        importRoute = state.routeKey; importProfile.launch(arrayOf("text/plain", "application/octet-stream"))
                    }, label = { Text(if (importing) "Importing..." else "Import AutoEq") }, leadingIcon = { Symbol(Glyph.FOLDER, size = 18.dp) })
                }
                if (state.sony) Text("Sony correction is AutoEq from Rtings measurements; turn Sony's own EQ off first.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                BandSlider("Preamp", "${String.format(Locale.ROOT, "%.1f", draft.preampDb)} dB", draft.preampDb.toFloat(), -36f..12f, editable, "Equalizer preamp",
                    onLabel = null, onChange = { draft = draft.copy(preampDb = it.toDouble()) }, onDone = { onApply(state.routeKey, draft) })
                draft.bands.forEachIndexed { index, band ->
                    val label = if (band.frequencyHz >= 1_000) "${String.format(Locale.ROOT, "%.1f", band.frequencyHz / 1_000)} kHz" else "${band.frequencyHz.toInt()} Hz"
                    BandSlider(label, "${String.format(Locale.ROOT, "%+.1f", band.gainDb)}", band.gainDb.toFloat(), -24f..24f, editable, "Gain at $label",
                        onLabel = { editor = index },
                        onChange = { value -> draft = draft.copy(profile = "Custom", bands = draft.bands.mapIndexed { i, item -> if (i == index) item.copy(gainDb = value.toDouble()) else item }) },
                        onDone = { onApply(state.routeKey, draft) }, bipolar = true)
                }
                Text("Headroom is kept automatically so boosted bands never clip.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            SoundCard { ReplayGainControls(loudness, connected, onLoudness) }

            SoundCard {
                Text("Channels", style = MaterialTheme.typography.titleMediumEmphasized)
                val mix = draft.channels
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("L", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Slider(value = mix.balance, valueRange = -1f..1f, enabled = editable,
                        onValueChange = { v -> draft = draft.copy(channels = mix.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v)) },
                        onValueChangeFinished = { onApply(state.routeKey, draft) }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Balance" })
                    Text("R", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                }
                Text(when { mix.balance == 0f -> "Centred"; mix.balance < 0 -> "${(-mix.balance * 100).toInt()}% to the left"; else -> "${(mix.balance * 100).toInt()}% to the right" },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Mono", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = mix.mono, enabled = editable, onCheckedChange = { apply(draft.copy(channels = mix.copy(mono = it))) }, modifier = Modifier.semantics { contentDescription = "Mono" })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Swap left and right", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = mix.swap, enabled = editable, onCheckedChange = { apply(draft.copy(channels = mix.copy(swap = it))) }, modifier = Modifier.semantics { contentDescription = "Swap left and right" })
                }
            }
        }
    }
}

/** A rounded card for one part of the sound. */
@Composable
private fun SoundCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** One band in one line: its frequency (tap to edit exactly), the slider, the value. */
@Composable
private fun BandSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, description: String,
    onLabel: (() -> Unit)?, onChange: (Float) -> Unit, onDone: () -> Unit, bipolar: Boolean = false) {
    // A boost or cut is measured from 0, not from the left end: bands show one even track and the thumb.
    val colors = if (bipolar) SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest) else SliderDefaults.colors()
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (onLabel != null) TextButton(enabled = enabled, onClick = onLabel, modifier = Modifier.width(84.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1) }
        else Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(84.dp).padding(start = 12.dp))
        Slider(value = current, valueRange = range, enabled = enabled, onValueChange = onChange, onValueChangeFinished = onDone, colors = colors,
            modifier = Modifier.weight(1f).semantics { contentDescription = description })
        Text(value, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

/** Normalization is global, independent of per-output headphone correction. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReplayGainControls(settings: ReplayGainSettings, enabled: Boolean, onApply: (ReplayGainSettings) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    fun apply(value: ReplayGainSettings) { draft = value; onApply(value) }
    Text("Loudness", style = MaterialTheme.typography.titleMediumEmphasized)
    Text("ReplayGain from the files' tags: Track for shuffle, Album keeps an album's dynamics.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(androidx.compose.material3.ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ReplayGainMode.entries.forEachIndexed { i, mode ->
            androidx.compose.material3.ToggleButton(checked = draft.mode == mode, enabled = enabled, onCheckedChange = { apply(draft.copy(mode = mode)) },
                shapes = when (i) { 0 -> androidx.compose.material3.ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ReplayGainMode.entries.lastIndex -> androidx.compose.material3.ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> androidx.compose.material3.ButtonGroupDefaults.connectedMiddleButtonShapes() },
                modifier = Modifier.weight(1f)) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) }
        }
    }
    if (draft.mode != ReplayGainMode.OFF) {
        BandSlider("Preamp", "${String.format(Locale.ROOT, "%+.1f", draft.preampDb)} dB", draft.preampDb.toFloat(), -24f..12f, enabled, "ReplayGain preamp",
            onLabel = null, onChange = { draft = draft.copy(preampDb = it.toDouble()) }, onDone = { onApply(draft) })
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prevent clipping", style = MaterialTheme.typography.bodyLarge)
                Text("Never boosts past a song's peak", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.preventClipping, enabled = enabled, onCheckedChange = { apply(draft.copy(preventClipping = it)) },
                modifier = Modifier.semantics { contentDescription = "Prevent ReplayGain clipping" })
        }
    }
}

/** Validates the complete filter before replacing it; changing output closes the enclosing editor. */
@Composable
private fun EqualizerBandDialog(band: EqualizerBand, onDismiss: () -> Unit, onApply: (EqualizerBand) -> Unit) {
    var frequency by remember(band) { mutableStateOf(band.frequencyHz.toString()) }
    var q by remember(band) { mutableStateOf(band.q.toString()) }
    var type by remember(band) { mutableStateOf(band.type) }
    val hz = frequency.toDoubleOrNull()?.takeIf { it.isFinite() && it in 10.0..24_000.0 }
    val quality = q.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..12.0 }
    AppDialog(onDismissRequest = onDismiss, title = { Text("Edit filter") }, text = {
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
