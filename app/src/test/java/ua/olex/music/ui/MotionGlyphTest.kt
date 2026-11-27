package ua.olex.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every animated symbol ends exactly where it started, and a film strip of each is kept for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionGlyphTest {
    private fun bounds(g: MotionGlyph, fill: Float, t: Float): RectF {
        val p = Path(); g.buildPath(fill, t, p)
        return RectF().also { p.asAndroidPath().computeBounds(it, true) }
    }

    @Test fun everyGestureEndsAtRest() {
        MotionGlyph.entries.forEach { g ->
            val rest = bounds(g, 1f, 1f)
            val start = bounds(g, 1f, 0f)
            assertEquals("${g.name} left", start.left, rest.left, 0.5f)
            assertEquals("${g.name} bottom", start.bottom, rest.bottom, 0.5f)
            // It really moves somewhere in between.
            val moved = (1..19).any { i -> val b = bounds(g, 1f, i / 20f); kotlin.math.abs(b.left - rest.left) + kotlin.math.abs(b.top - rest.top) + kotlin.math.abs(b.width() - rest.width()) > 8f }
            assertTrue("${g.name} never moves", moved)
        }
    }

    @Test fun filmStrip() {
        val cell = 72; val steps = 14
        val all = MotionGlyph.entries
        val bmp = Bitmap.createBitmap(cell * steps, cell * all.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFFF4F1F8.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3A6E.toInt() }
        all.forEachIndexed { row, g ->
            for (i in 0 until steps) {
                val t = i / (steps - 1f)
                c.save(); c.translate(i * cell + 12f, row * cell + 12f); c.scale(48f / 960f, 48f / 960f)
                g.render(c, (t * 2.2f).coerceAtMost(1f), t, paint, Path(), Path(), Pose()); c.restore()
            }
        }
        File("build/reports/ui-previews/motion").apply { mkdirs() }.resolve("glyph-film.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
