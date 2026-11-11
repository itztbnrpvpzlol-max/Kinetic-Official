package com.kinetic.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun KineticTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = KColors.Bg,
            surface = KColors.Surface,
            onBackground = KColors.Text,
            onSurface = KColors.Text
        ),
        content = content
    )
}
