@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.music.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ua.olex.music.R

private val playerFont = FontFamily(Font(R.font.google_sans_flex))
private val playerTypography = Typography().let { base -> base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = playerFont), displayMedium = base.displayMedium.copy(fontFamily = playerFont), displaySmall = base.displaySmall.copy(fontFamily = playerFont),
    headlineLarge = base.headlineLarge.copy(fontFamily = playerFont), headlineMedium = base.headlineMedium.copy(fontFamily = playerFont), headlineSmall = base.headlineSmall.copy(fontFamily = playerFont),
    titleLarge = base.titleLarge.copy(fontFamily = playerFont), titleMedium = base.titleMedium.copy(fontFamily = playerFont), titleSmall = base.titleSmall.copy(fontFamily = playerFont),
    bodyLarge = base.bodyLarge.copy(fontFamily = playerFont), bodyMedium = base.bodyMedium.copy(fontFamily = playerFont), bodySmall = base.bodySmall.copy(fontFamily = playerFont),
    labelLarge = base.labelLarge.copy(fontFamily = playerFont), labelMedium = base.labelMedium.copy(fontFamily = playerFont), labelSmall = base.labelSmall.copy(fontFamily = playerFont),
) }

/** Material 3 Expressive with the phone's wallpaper colors, as the owner's other apps. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), typography = playerTypography, content = content)
}
