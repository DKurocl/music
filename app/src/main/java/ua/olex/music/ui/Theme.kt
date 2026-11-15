@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ua.olex.music.R

/**
 * Google Sans Flex, bundled (SIL Open Font License, assets/licenses), as in the owner's Earbuds
 * app. One variable font: weight and optical size follow each Material style, and display and
 * headline text get the rounded cut (ROND 100) that Material 3 Expressive uses for large type;
 * smaller text stays crisp (ROND 0).
 */
private fun flex(style: TextStyle, round: Boolean): TextStyle {
    val weight = style.fontWeight ?: FontWeight.Normal
    val size = if (style.fontSize.isSp) style.fontSize.value else 14f
    val family = FontFamily(
        Font(
            R.font.google_sans_flex, weight = weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting("opsz", size.coerceIn(6f, 144f)),
                FontVariation.Setting("ROND", if (round) 100f else 0f),
            ),
        ),
    )
    return style.copy(fontFamily = family)
}

private val appTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = flex(t.displayLarge, true), displayMedium = flex(t.displayMedium, true), displaySmall = flex(t.displaySmall, true),
        headlineLarge = flex(t.headlineLarge, true), headlineMedium = flex(t.headlineMedium, true), headlineSmall = flex(t.headlineSmall, true),
        titleLarge = flex(t.titleLarge, false), titleMedium = flex(t.titleMedium, false), titleSmall = flex(t.titleSmall, false),
        bodyLarge = flex(t.bodyLarge, false), bodyMedium = flex(t.bodyMedium, false), bodySmall = flex(t.bodySmall, false),
        labelLarge = flex(t.labelLarge, false), labelMedium = flex(t.labelMedium, false), labelSmall = flex(t.labelSmall, false),
        displayLargeEmphasized = flex(t.displayLargeEmphasized, true), displayMediumEmphasized = flex(t.displayMediumEmphasized, true),
        displaySmallEmphasized = flex(t.displaySmallEmphasized, true), headlineLargeEmphasized = flex(t.headlineLargeEmphasized, true),
        headlineMediumEmphasized = flex(t.headlineMediumEmphasized, true), headlineSmallEmphasized = flex(t.headlineSmallEmphasized, true),
        titleLargeEmphasized = flex(t.titleLargeEmphasized, false), titleMediumEmphasized = flex(t.titleMediumEmphasized, false),
        titleSmallEmphasized = flex(t.titleSmallEmphasized, false), bodyLargeEmphasized = flex(t.bodyLargeEmphasized, false),
        bodyMediumEmphasized = flex(t.bodyMediumEmphasized, false), bodySmallEmphasized = flex(t.bodySmallEmphasized, false),
        labelLargeEmphasized = flex(t.labelLargeEmphasized, false), labelMediumEmphasized = flex(t.labelMediumEmphasized, false),
        labelSmallEmphasized = flex(t.labelSmallEmphasized, false),
    )
}

/** Material 3 Expressive with the phone's wallpaper colors everywhere (DESIGN_DIRECTION.md). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), typography = appTypography, content = content)
}
