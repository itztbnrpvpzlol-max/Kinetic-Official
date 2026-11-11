package com.kinetic.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object KColors {
    val Bg = Color(0xFF0E0E14)
    val Surface = Color(0xFF15151D)
    val Surface2 = Color(0xFF1D1D27)
    val Line = Color(0xFF24242F)
    val Text = Color(0xFFE9E8F2)
    val Muted = Color(0xFF8D8BA3)
    val Dim = Color(0xFF53526A)
    val Clip = Color(0xFF3A3590)
    val ClipText = Color(0xFFD3D0F5)
    val Accent = Color(0xFF8466FF)
    val Row = Color(0xFF12121A)
    val RowSel = Color(0xFF191925)
    val Fab = Color(0xFF5B31F2)
    val Danger = Color(0xFFC98A8A)
}

/**
 * Native Android typography pack.
 *
 * Uses the platform sans family (Roboto on current Android versions) rather than
 * bundling Apple's San Francisco font. Explicit weight tokens and optical sizing
 * keep the interface close to the clean, compact hierarchy used by polished iOS apps.
 * The font remains available offline and does not require a network font provider.
 */
object KineticFonts {
    val Family: FontFamily = FontFamily.SansSerif
    val Regular = FontWeight.Normal
    val Medium = FontWeight.Medium
    val SemiBold = FontWeight.SemiBold
    val Bold = FontWeight.Bold
}

private val KineticTypography = Typography(
    displayLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Regular, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Regular, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Regular, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = KineticFonts.Family, fontWeight = KineticFonts.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
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
        typography = KineticTypography,
        content = content
    )
}
