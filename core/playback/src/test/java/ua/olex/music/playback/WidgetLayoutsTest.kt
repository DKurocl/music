package ua.olex.music.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A home-screen widget may only use the views RemoteViews allows; anything else (a plain View as a
 * spacer, say) makes the launcher show "Can't load widget". Every widget layout is checked here.
 */
class WidgetLayoutsTest {
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "AnalogClock", "Button", "Chronometer",
        "ImageButton", "ImageView", "ProgressBar", "TextView", "ViewFlipper", "ListView", "GridView", "StackView",
        "AdapterViewFlipper", "ViewStub", "CheckBox", "Switch", "RadioButton", "RadioGroup", "TextClock", "merge", "include",
    )

    @Test fun onlyRemoteViewsWidgets() {
        val dir = listOf(File("src/main/res/layout"), File("core/playback/src/main/res/layout")).first { it.isDirectory }
        val layouts = dir.listFiles { f -> f.name.startsWith("widget") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue(layouts.isNotEmpty())
        for (f in layouts) {
            val tags = Regex("""<([A-Za-z][\w.]*)[\s>/]""").findAll(f.readText()).map { it.groupValues[1] }.filterNot { it == "xml" }.toSet()
            val bad = tags - allowed
            assertTrue("${f.name} uses ${bad.joinToString()}: the launcher would show Can't load widget", bad.isEmpty())
        }
    }
}
