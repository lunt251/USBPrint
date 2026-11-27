package com.usbprint.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.usbprint.app.R
import com.usbprint.app.settings.ThemeMode

// Direction: the desk next to a printer. Graphite neutrals with a slight paper warmth,
// one toner-orange accent, and the printer's status LED as the signature detail.

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2A65A),
    onPrimary = Color(0xFF2A1600),
    primaryContainer = Color(0xFF3B2A18),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFFCFC9C1),
    onSecondary = Color(0xFF1E1B17),
    secondaryContainer = Color(0xFF2A2825),
    onSecondaryContainer = Color(0xFFEFE9DE),
    tertiary = Color(0xFFCFC9C1),
    onTertiary = Color(0xFF1E1B17),
    tertiaryContainer = Color(0xFF2A2825),
    onTertiaryContainer = Color(0xFFEFE9DE),
    background = Color(0xFF141413),
    onBackground = Color(0xFFEFECE6),
    surface = Color(0xFF141413),
    onSurface = Color(0xFFEFECE6),
    surfaceVariant = Color(0xFF23221F),
    onSurfaceVariant = Color(0xFFA9A49C),
    surfaceContainerLowest = Color(0xFF0F0F0E),
    surfaceContainerLow = Color(0xFF191917),
    surfaceContainer = Color(0xFF1C1B19),
    surfaceContainerHigh = Color(0xFF23221F),
    surfaceContainerHighest = Color(0xFF2B2A27),
    outline = Color(0xFF6F6A63),
    outlineVariant = Color(0xFF33312D),
    error = Color(0xFFE5484D),
    onError = Color(0xFF2B0606),
    errorContainer = Color(0xFF3A1616),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFA34F0F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE2C6),
    onPrimaryContainer = Color(0xFF3A1E05),
    secondary = Color(0xFF5E584F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE8E0),
    onSecondaryContainer = Color(0xFF1F1B16),
    tertiary = Color(0xFF5E584F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE8E0),
    onTertiaryContainer = Color(0xFF1F1B16),
    background = Color(0xFFF7F5F1),
    onBackground = Color(0xFF1C1A17),
    surface = Color(0xFFF7F5F1),
    onSurface = Color(0xFF1C1A17),
    surfaceVariant = Color(0xFFEDE9E2),
    onSurfaceVariant = Color(0xFF5E584F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFF2EFEA),
    surfaceContainerHigh = Color(0xFFECE8E2),
    surfaceContainerHighest = Color(0xFFE5E1DA),
    outline = Color(0xFF8A847A),
    outlineVariant = Color(0xFFDDD8CF),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

/** Status LED colours, toned to sit inside the palette. */
@Immutable
data class LedColors(val ready: Color, val busy: Color, val error: Color, val idle: Color)

val LocalLed = staticCompositionLocalOf { LedColors(Color(0xFF4CC38A), Color(0xFFF2A65A), Color(0xFFE5484D), Color(0xFF6F6A63)) }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Figtree = FontFamily(figtree(400), figtree(500), figtree(600))

private fun TextStyle.f(size: Int, line: Int, weight: Int = 400) =
    copy(fontFamily = Figtree, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = 0.sp)

private fun typography(): Typography {
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.f(56, 60, 500).copy(letterSpacing = (-1.2).sp),
        displayMedium = b.displayMedium.f(44, 48, 500).copy(letterSpacing = (-0.8).sp),
        displaySmall = b.displaySmall.f(36, 40, 500),
        headlineLarge = b.headlineLarge.f(32, 38, 500),
        headlineMedium = b.headlineMedium.f(28, 34, 500),
        headlineSmall = b.headlineSmall.f(24, 30, 500),
        titleLarge = b.titleLarge.f(20, 26, 500),
        titleMedium = b.titleMedium.f(16, 22, 500),
        titleSmall = b.titleSmall.f(14, 20, 500),
        bodyLarge = b.bodyLarge.f(16, 24),
        bodyMedium = b.bodyMedium.f(14, 20),
        bodySmall = b.bodySmall.f(12, 16),
        labelLarge = b.labelLarge.f(14, 20, 500),
        labelMedium = b.labelMedium.f(12, 16, 500),
        labelSmall = b.labelSmall.f(11, 16, 500),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun USBPrintTheme(
    mode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val context = LocalContext.current
    var scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    if (mode == ThemeMode.BLACK) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0A),
            surfaceContainer = Color(0xFF121211),
        )
    }
    val led = if (dark) LedColors(Color(0xFF4CC38A), Color(0xFFF2A65A), Color(0xFFE5484D), Color(0xFF6F6A63))
    else LedColors(Color(0xFF16A34A), Color(0xFFD97706), Color(0xFFDC2626), Color(0xFF8A847A))
    androidx.compose.runtime.CompositionLocalProvider(LocalLed provides led) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            typography = typography(),
            motionScheme = MotionScheme.standard(),
            content = content,
        )
    }
}
