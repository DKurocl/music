package ua.olex.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The home-screen widget at its real sizes, light and dark, on a dark wallpaper, for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGalleryTest {
    @Test fun light() = render("39-widget-light")
    @Test @Config(qualifiers = "night") fun dark() = render("40-widget-dark")

    private fun render(name: String) {
        val context = RuntimeEnvironment.getApplication()
        val d = context.resources.displayMetrics.density
        val px = { dp: Int -> (dp * d).toInt() }
        val sheet = FrameLayout(context).apply { setBackgroundColor(Color.rgb(12, 24, 70)) }
        val cover = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(Color.rgb(240, 236, 228)); drawCircle(96f, 104f, 40f, android.graphics.Paint().apply { color = Color.rgb(60, 40, 30) }) }
        }
        listOf(ua.olex.music.playback.R.layout.widget_now_playing to 94, ua.olex.music.playback.R.layout.widget_now_playing_short to 76)
            .forEachIndexed { i, (layout, height) ->
                val v: View = LayoutInflater.from(context).inflate(layout, sheet, false)
                v.findViewById<TextView>(ua.olex.music.playback.R.id.widget_title).text = "Juicy (2005 Remaster)"
                v.findViewById<TextView>(ua.olex.music.playback.R.id.widget_artist).text = "The Notorious B.I.G."
                v.findViewById<ImageView>(ua.olex.music.playback.R.id.widget_art).setImageBitmap(cover)
                sheet.addView(v, FrameLayout.LayoutParams(px(285), px(height)).apply { leftMargin = px(16); topMargin = px(16 + i * 112) })
            }
        val w = px(317); val h = px(16 + 112 + 76 + 16)
        sheet.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        sheet.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        sheet.draw(Canvas(b))
        val out = File("build/reports/ui-previews/gallery/$name.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
