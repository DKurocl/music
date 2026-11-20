package ua.olex.music.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun StateSwitch(on: Boolean) = AppSwitch(on, null)

/**
 * The app's one switch, as Android's own Settings draw it: a check in the thumb when on, an empty
 * thumb when off. Every toggle in the app uses it, so they all look and move the same.
 */
@Composable
internal fun AppSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        thumbContent = if (checked) ({ Symbol(Glyph.CHECK, size = 14.dp) }) else null)
}

/** The envelope illustrates what the fade does. Changing the setting redraws it once. */
@Composable
internal fun PlaybackFadeSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val level by animateFloatAsState(if (enabled) 1f else 0f,
        if (LocalPowerSave.current) snap() else tween(260), label = "Playback envelope")
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerHigh)) {
            val width = size.width * 0.76f
            val start = (size.width - width) / 2f
            for (index in 0..40) {
                val t = index / 40f
                val envelope = (sin(PI * t).toFloat().coerceAtLeast(0f) - 1f) * level + 1f
                val amp = size.height * (0.16f + 0.16f * sin(index * 0.62).toFloat().let { it * it })
                val h = 1.dp.toPx() + envelope * amp
                val x = start + width * t
                drawLine(if (index < 21) colors.primary else colors.tertiary,
                    Offset(x, center.y - h), Offset(x, center.y + h), width / 80f, StrokeCap.Round)
            }
        }
        Surface(shape = RoundedCornerShape(32.dp), color = if (enabled) colors.primaryContainer else colors.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch) {
                haptics.toggle(it); onChange(it)
            }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smooth pause and play", style = MaterialTheme.typography.titleMedium)
                    Text("Fade in and out on play and pause", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(12.dp))
                StateSwitch(enabled)
            }
        }
    }
}

/** Each bar shows the category's share of measured cache usage, including an empty cache. */
@Composable
internal fun CacheUsageCard(sizes: List<Long>?, limitBytes: Long = Long.MAX_VALUE, limitLabel: String? = null) {
    // As Android's Storage page: the amount used out of the limit, one bar split by kind, a legend.
    val colors = MaterialTheme.colorScheme
    val still = LocalPowerSave.current
    val kinds = listOf("Streamed audio" to colors.primary, "Images" to colors.tertiary, "Moving covers" to colors.secondary)
    Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sizes == null) { Text("Calculating…", style = MaterialTheme.typography.bodyMedium); return@Column }
            val total = sizes.sum()
            val bounded = limitBytes != Long.MAX_VALUE && limitBytes > 0
            Row(verticalAlignment = Alignment.Bottom) {
                Text(cacheSize(total), style = MaterialTheme.typography.headlineLarge)
                if (bounded && limitLabel != null) Text(" of $limitLabel", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            val scale = if (bounded) limitBytes.toFloat() else total.coerceAtLeast(1).toFloat()
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(colors.surfaceVariant)) {
                val track = maxWidth
                Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    kinds.forEachIndexed { index, (name, color) ->
                        val share by animateFloatAsState((sizes[index] / scale).coerceIn(0f, 1f), if (still) snap() else tween(240), label = name)
                        if (sizes[index] > 0) Spacer(Modifier.width((track * share).coerceAtLeast(4.dp)).fillMaxHeight().clip(CircleShape).background(color))
                    }
                }
            }
            kinds.forEachIndexed { index, (name, color) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(cacheSize(sizes[index]), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun cacheSize(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, bytes)
