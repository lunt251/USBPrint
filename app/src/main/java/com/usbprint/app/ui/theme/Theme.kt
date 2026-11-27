package com.usbprint.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.usbprint.app.settings.ThemeMode

// Wallpaper (dynamic) colour is the default. These schemes are only the fallback for
// Android 8-11 or when dynamic colour is turned off, seeded from a warm toner orange.

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
    // Busy/idle/error follow the scheme; "ready" green is a static semantic colour like error.
    val led = LedColors(
        ready = if (dark) Color(0xFF6DD58C) else Color(0xFF1E8E3E),
        busy = scheme.primary,
        error = scheme.error,
        idle = scheme.outline,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalLed provides led) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            typography = AppTypography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
