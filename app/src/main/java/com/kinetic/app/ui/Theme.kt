package com.kinetic.app.ui

import com.kinetic.app.R

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object KColors {
    val Bg = Color(0xFF1A1A1A)
    val Surface = Color(0xFF232323)
    val Surface2 = Color(0xFF2D2D2D)
    val Line = Color(0xFF121212)
    val Text = Color(0xFFD9D9D9)
    val Muted = Color(0xFF9D9D9D)
    val Dim = Color(0xFF6E6E6E)
    val Clip = Color(0xFF2F7F82)
    val ClipText = Color(0xFFE9F6F6)
    val Accent = Color(0xFF3F95F7)
    val Row = Color(0xFF1F1F1F)
    val RowSel = Color(0xFF2B3A4D)
    val Fab = Color(0xFF2C7BE5)
    val Danger = Color(0xFFD98A8A)
}

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    resId = R.font.manrope_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/** Bundled Manrope variable font. All weights are available offline in the APK. */
object KineticFonts {
    val Family = FontFamily(
        manrope(200),
        manrope(300),
        manrope(400),
        manrope(500),
        manrope(600),
        manrope(700),
        manrope(800)
    )
    val Regular = FontWeight.Normal
    val Medium = FontWeight.Medium
    val SemiBold = FontWeight.SemiBold
    val Bold = FontWeight.Bold
    val ExtraBold = FontWeight.ExtraBold
}

private val KineticTypography = Typography(
    displayLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Regular, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Regular, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Regular, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.35.sp)
)

@Composable
fun KineticTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = KColors.Bg,
            surface = KColors.Surface,
            onBackground = KColors.Text,
            onSurface = KColors.Text,
            primary = KColors.Accent,
            onPrimary = Color.White,
            secondary = KColors.Clip,
            onSecondary = KColors.ClipText,
            outline = KColors.Line
        ),
        typography = KineticTypography
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = KineticFonts.Family),
            content = content
        )
    }
}
