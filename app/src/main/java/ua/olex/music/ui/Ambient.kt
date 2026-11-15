package ua.olex.music.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.olex.music.model.ArtworkPlan

/**
 * The player's living background: the cover's two or three strongest colours as soft light that
 * drifts slowly behind everything (as Apple Music's animated backgrounds do), only while the music
 * plays; paused, it holds still. The colours come from a 192 px copy of the cover, sampled once
 * and kept in memory for the session, so it costs nothing on disk and almost nothing in time.
 */
@Composable
internal fun AmbientBackground(artworkUri: String?, playing: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val colors by produceState(CoverColors.cached(artworkUri), artworkUri) {
        if (value == null && artworkUri != null) value = withContext(Dispatchers.IO) { CoverColors.of(context, artworkUri) }
    }
    var time by remember { mutableFloatStateOf(0f) }
    val inspection = LocalInspectionMode.current
    LaunchedEffect(playing) {
        if (!playing || inspection) return@LaunchedEffect
        var last = 0L
        // Infinite-animation frames: tests and the system know this never "settles".
        while (true) androidx.compose.animation.core.withInfiniteAnimationFrameMillis { ms ->
            if (last != 0L) time += (ms - last) / 1000f
            last = ms
        }
    }
    val dark = cs.surface.luminance() < 0.5f
    Canvas(modifier.fillMaxSize()) {
        drawRect(cs.surface)
        val palette = colors ?: return@Canvas
        val strength = if (dark) 0.42f else 0.30f
        palette.forEachIndexed { i, c ->
            // Each patch of light circles slowly on its own path (about a minute per lap).
            val t = time / 60f * 2f * Math.PI.toFloat() + i * 2.1f
            val center = Offset(size.width * (0.5f + 0.38f * kotlin.math.sin(t + i)), size.height * (0.22f + 0.5f * i / 3f + 0.12f * kotlin.math.cos(t * 0.8f)))
            val tone = lerp(c, cs.surface, if (dark) 0.25f else 0.15f)
            drawRect(Brush.radialGradient(listOf(tone.copy(alpha = strength), Color.Transparent), center, size.width * 0.95f))
        }
        // The lower part stays calm so the controls read clearly.
        drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to cs.surface.copy(alpha = 0.85f)))
    }
}

/** The strongest colours of covers, sampled once per cover and kept for the session. */
private object CoverColors {
    private val memory = object : LinkedHashMap<String, List<Color>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>?) = size > 64
    }

    fun cached(uri: String?): List<Color>? = uri?.let { synchronized(memory) { memory[it] } }

    fun of(context: android.content.Context, uri: String): List<Color>? = runCatching {
        val source = android.net.Uri.parse(ua.olex.music.library.ArtworkUris.variant(uri, context.packageName, ArtworkPlan.SMALL))
        val bitmap = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) } ?: return null
        // Buckets of hue; colourful, not near-black or near-white pixels count most.
        val buckets = HashMap<Int, FloatArray>()
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val p = bitmap.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.15f || (hsv[1] < 0.12f && hsv[2] > 0.9f)) continue
            val key = (hsv[0] / 30f).toInt()
            val weight = 0.3f + hsv[1]
            val b = buckets.getOrPut(key) { FloatArray(4) }
            b[0] += android.graphics.Color.red(p) * weight; b[1] += android.graphics.Color.green(p) * weight; b[2] += android.graphics.Color.blue(p) * weight; b[3] += weight
        }
        bitmap.recycle()
        val top = buckets.values.sortedByDescending { it[3] }.take(3).map { Color(it[0] / it[3] / 255f, it[1] / it[3] / 255f, it[2] / it[3] / 255f) }
        top.ifEmpty { null }?.also { synchronized(memory) { memory[uri] = it } }
    }.getOrNull()
}
