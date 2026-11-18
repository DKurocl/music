@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ua.olex.music.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
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
    // Three cards (Equalizer, Loudness, Channels), each with its switch or choice in its header,
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
                // The sound as a curve: drag a band's dot up or down; its chip below opens exact settings.
                var held by remember { mutableStateOf<Int?>(null) }
                EqCurve(draft, editable, held,
                    onHold = { held = it },
                    onGain = { index, value -> draft = draft.copy(profile = "Custom", bands = draft.bands.mapIndexed { i, item -> if (i == index) item.copy(gainDb = value) else item }) },
                    onDone = { held = null; onApply(state.routeKey, draft) })
                // The row scrolls: its right edge fades out, so the bands past it read as more to come.
                androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.fadeEnd(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Chips in order of frequency (imported profiles list bands in any order); each opens its own band.
                    val order = draft.bands.indices.sortedBy { draft.bands[it].frequencyHz }
                    items(order.size) { position ->
                        val index = order[position]
                        val band = draft.bands[index]
                        val label = bandLabel(band.frequencyHz)
                        Surface(onClick = { editor = index }, enabled = editable, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            color = if (held == index) cs.primaryContainer else cs.surfaceContainerHigh,
                            modifier = Modifier.semantics { contentDescription = "$label, ${String.format(Locale.ROOT, "%+.1f", band.gainDb)} dB" }) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                Text(String.format(Locale.ROOT, "%+.1f", band.gainDb), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
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

private fun bandLabel(hz: Double) = if (hz >= 1_000) "${String.format(Locale.ROOT, "%.1f", hz / 1_000).removeSuffix(".0")} kHz" else "${hz.toInt()} Hz"

/**
 * The equalizer as its frequency response, 20 Hz to 20 kHz on a log scale, ±15 dB: the curve the
 * bands make together, filled toward 0 dB, with each band as a dot at its frequency and gain.
 * Dragging near a dot moves that band (with a tick at every whole dB); while held, its value shows.
 */
@Composable
private fun EqCurve(settings: EqualizerSettings, enabled: Boolean, held: Int?, onHold: (Int?) -> Unit, onGain: (Int, Double) -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = cs.onPrimary)
    val bands by androidx.compose.runtime.rememberUpdatedState(settings.bands)
    val active = settings.enabled
    val range = 15.0
    fun xOf(hz: Double, w: Float) = (kotlin.math.log10(hz / 20.0) / 3.0).toFloat().coerceIn(0f, 1f) * w
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Equalizer curve" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var index = -1
                var lastDb = 0
                var raw = 0.0
                detectVerticalDragGestures(
                    onDragStart = { at ->
                        // The dot under the finger, by real distance: AutoEq often stacks bands a few
                        // hundred hertz apart, one above another, and frequency alone can't tell them apart.
                        val perDb = (size.height / 2f - 16.dp.toPx()) / range.toFloat()
                        index = bands.indices.minByOrNull {
                            val dx = xOf(bands[it].frequencyHz, size.width.toFloat()) - at.x
                            val dy = (size.height / 2f - bands[it].gainDb.coerceIn(-range, range) * perDb).toFloat() - at.y
                            dx * dx + dy * dy
                        } ?: -1
                        if (index >= 0) { onHold(index); raw = bands[index].gainDb; lastDb = raw.toInt(); haptics.tick() }
                    },
                    onDragEnd = { if (index >= 0) onDone(); index = -1 },
                    onDragCancel = { if (index >= 0) onDone(); index = -1 },
                ) { change, dy ->
                    if (index < 0) return@detectVerticalDragGestures
                    change.consume()
                    val perDb = (size.height / 2f - 16.dp.toPx()) / range.toFloat()
                    raw = (raw - dy / perDb).coerceIn(-range, range)
                    val rounded = kotlin.math.round(raw * 2) / 2
                    if (rounded.toInt() != lastDb) { lastDb = rounded.toInt(); haptics.tick() }
                    onGain(index, rounded)
                }
            },
    ) {
        val w = size.width; val h = size.height
        val mid = h / 2f
        val perDb = (h / 2f - 16.dp.toPx()) / range.toFloat()
        fun yOf(db: Double) = (mid - db * perDb).toFloat()
        // Grid: 0 dB stronger, ±6 and ±12 dB faint; 100 Hz, 1 kHz, 10 kHz marks.
        listOf(-12.0, -6.0, 6.0, 12.0).forEach { drawLine(cs.outlineVariant.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(0f, yOf(it)), androidx.compose.ui.geometry.Offset(w, yOf(it)), 1.dp.toPx()) }
        drawLine(cs.outlineVariant, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(w, mid), 1.5f.dp.toPx())
        listOf(100.0 to "100", 1_000.0 to "1k", 10_000.0 to "10k").forEach { (hz, text) ->
            val x = xOf(hz, w)
            drawLine(cs.outlineVariant.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), 1.dp.toPx())
            drawText(measurer, text, androidx.compose.ui.geometry.Offset(x + 4.dp.toPx(), h - 16.dp.toPx()), labelStyle)
        }
        // The response the bands make together.
        val filters = settings.bands.filter { it.gainDb != 0.0 }.map { ua.olex.music.dsp.Biquad.of(it, 48_000) }
        val steps = 160
        val curve = androidx.compose.ui.graphics.Path()
        val fill = androidx.compose.ui.graphics.Path().apply { moveTo(0f, mid) }
        for (i in 0..steps) {
            val hz = 20.0 * Math.pow(1000.0, i / steps.toDouble())
            val db = filters.sumOf { 20 * kotlin.math.log10(it.magnitudeAt(hz, 48_000).coerceAtLeast(1e-6)) }.coerceIn(-range, range)
            val x = xOf(hz, w); val y = yOf(db)
            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(w, mid); fill.close()
        val tone = if (active) cs.primary else cs.outline
        drawPath(fill, tone.copy(alpha = 0.16f))
        drawPath(curve, tone, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        settings.bands.forEachIndexed { i, band ->
            val c = androidx.compose.ui.geometry.Offset(xOf(band.frequencyHz, w), yOf(band.gainDb.coerceIn(-range, range)))
            val r = if (held == i) 11.dp.toPx() else 7.dp.toPx()
            drawCircle(cs.surfaceContainer, r + 3.dp.toPx(), c)
            drawCircle(tone, r, c)
            if (held == i) {
                val text = "${String.format(Locale.ROOT, "%+.1f", band.gainDb)} dB · ${bandLabel(band.frequencyHz)}"
                val layout = measurer.measure(text, valueStyle)
                val bx = (c.x - layout.size.width / 2f - 10.dp.toPx()).coerceIn(0f, w - layout.size.width - 20.dp.toPx())
                val by = if (c.y > h / 2) c.y - 52.dp.toPx() else c.y + 20.dp.toPx()
                drawRoundRect(cs.primary, androidx.compose.ui.geometry.Offset(bx, by), androidx.compose.ui.geometry.Size(layout.size.width + 20.dp.toPx(), layout.size.height + 10.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(bx + 10.dp.toPx(), by + 5.dp.toPx()))
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
