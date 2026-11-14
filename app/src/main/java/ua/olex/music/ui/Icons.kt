package ua.olex.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the first screens need, as plain vectors. They are a stand-in: the app moves to
 * the Material Symbols variable font (animated fill and weight) the way Earbuds did
 * (docs/ROADMAP.md, M2), and CI will then reject any other icon source.
 */
object AppIcons {
    val Play = icon("M8,5v14l11,-7z")
    val Pause = icon("M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Next = icon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Previous = icon("M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Note = icon("M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    val Down = icon("M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
