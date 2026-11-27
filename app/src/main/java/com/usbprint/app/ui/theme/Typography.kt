package com.usbprint.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.usbprint.app.R

// M3 typeface roles: "brand" for display/headline, "plain" for title/body/label.
// Both are variable fonts from Google Fonts (OFL). Sizes, weights and tracking stay the M3 defaults.

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Brand = FontFamily(
    variable(R.font.google_sans_flex, 400),
    variable(R.font.google_sans_flex, 500),
    variable(R.font.google_sans_flex, 600),
    variable(R.font.google_sans_flex, 700),
)

private val Plain = FontFamily(
    variable(R.font.roboto_flex, 400),
    variable(R.font.roboto_flex, 500),
    variable(R.font.roboto_flex, 600),
    variable(R.font.roboto_flex, 700),
)

private fun TextStyle.brand() = copy(fontFamily = Brand)
private fun TextStyle.plain() = copy(fontFamily = Plain)

val AppTypography: Typography = Typography().let { d ->
    Typography(
        displayLarge = d.displayLarge.brand(),
        displayMedium = d.displayMedium.brand(),
        displaySmall = d.displaySmall.brand(),
        headlineLarge = d.headlineLarge.brand(),
        headlineMedium = d.headlineMedium.brand(),
        headlineSmall = d.headlineSmall.brand(),
        titleLarge = d.titleLarge.plain(),
        titleMedium = d.titleMedium.plain(),
        titleSmall = d.titleSmall.plain(),
        bodyLarge = d.bodyLarge.plain(),
        bodyMedium = d.bodyMedium.plain(),
        bodySmall = d.bodySmall.plain(),
        labelLarge = d.labelLarge.plain(),
        labelMedium = d.labelMedium.plain(),
        labelSmall = d.labelSmall.plain(),
    )
}
