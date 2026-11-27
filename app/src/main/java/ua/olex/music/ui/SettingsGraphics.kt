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

/** The app's one switch: Pixel Settings' switch, so every toggle in the family looks and moves the same. */
@Composable
internal fun AppSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    PixelSwitch(checked, modifier, onCheckedChange, enabled)

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
