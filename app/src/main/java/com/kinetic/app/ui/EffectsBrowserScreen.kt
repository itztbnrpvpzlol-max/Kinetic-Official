package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class EffectCategory(
    val title: String,
    val accent: Color,
    val deep: Color,
    val glow: Color
)

private val effectCategories = listOf(
    EffectCategory("COLOR & LIGHT", Color(0xFFFF82B2), Color(0xFF24152F), Color(0xFFFF4F8B)),
    EffectCategory("STYLIZE", Color(0xFFC0A2FF), Color(0xFF20183D), Color(0xFF8664FF)),
    EffectCategory("DRAWING & EDGE", Color(0xFFFFC078), Color(0xFF302018), Color(0xFFFF934D)),
    EffectCategory("DISTORTION & WARP", Color(0xFF70E4DC), Color(0xFF102B34), Color(0xFF29BFC7)),
    EffectCategory("PROCEDURAL", Color(0xFFB5A4FF), Color(0xFF201C3A), Color(0xFF806CFF)),
    EffectCategory("BLUR", Color(0xFF8BC8FF), Color(0xFF14283B), Color(0xFF4C9DFF)),
    EffectCategory("3D", Color(0xFFFF987C), Color(0xFF321D24), Color(0xFFFF694F)),
    EffectCategory("TEXT", Color(0xFFFFD77A), Color(0xFF332719), Color(0xFFFFB642)),
    EffectCategory("AUDIO", Color(0xFF79E0AE), Color(0xFF132F2A), Color(0xFF38C98A)),
    EffectCategory("OTHER / MISCELLANEOUS", Color(0xFFB2C0D6), Color(0xFF1B2637), Color(0xFF718EBA))
)

@Composable
fun EffectsBrowserScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(KColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Effects", color = KColors.Text, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Shape the look. Set the mood.",
                    color = KColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                Modifier.size(42.dp)
                    .clip(CircleShape)
                    .background(KColors.Surface2)
                    .border(1.dp, KColors.Line, CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = KColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Light)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeading("Suggested for you")
            Spacer(Modifier.weight(1f))
            Eyebrow("COMING INTO FOCUS")
        }
        SuggestedPanel()

        Spacer(Modifier.height(22.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val shelfWidth = (maxWidth - 10.dp) / 2
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShelfCard(
                    title = "Recently used",
                    eyebrow = "YOUR HISTORY",
                    description = "Your recent effects will show up here.",
                    symbol = "↻",
                    accent = Color(0xFF79B9FF),
                    modifier = Modifier.width(shelfWidth)
                )
                ShelfCard(
                    title = "Favourites",
                    eyebrow = "SAVED BY YOU",
                    description = "Keep your go-to looks close.",
                    symbol = "☆",
                    accent = Color(0xFFFFC36B),
                    modifier = Modifier.width(shelfWidth)
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeading("Explore categories")
            Spacer(Modifier.weight(1f))
            Eyebrow("10 COLLECTIONS")
        }
        effectCategories.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { category -> CategoryTile(category, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        color = KColors.Dim,
        fontSize = 8.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun SuggestedPanel() {
    Box(
        Modifier.fillMaxWidth()
            .height(142.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF24213C), Color(0xFF171B2A), Color(0xFF202B38))
                )
            )
            .border(1.dp, Color(0xFF3B3A55), RoundedCornerShape(18.dp))
            .padding(17.dp)
    ) {
        Box(
            Modifier.align(Alignment.CenterEnd)
                .size(116.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x557C78FF), Color(0x227C78FF), Color.Transparent)
                    )
                )
        )
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.76f)) {
            Text(
                "A LITTLE CREATIVE SPARK",
                color = Color(0xFFB7B2FF),
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "Your next look starts here.",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "Suggested effects will appear as your library grows.",
                color = Color(0xFFB4B8C8),
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
        Text(
            "✦",
            Modifier.align(Alignment.CenterEnd).padding(end = 15.dp),
            color = Color(0xFFD0C7FF),
            fontSize = 43.sp,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
private fun ShelfCard(
    title: String,
    eyebrow: String,
    description: String,
    symbol: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.height(126.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                eyebrow,
                color = KColors.Dim,
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.45.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(
            description,
            color = KColors.Muted,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CategoryArtwork(category: EffectCategory, modifier: Modifier = Modifier) {
    val index = effectCategories.indexOf(category)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val short = minOf(w, h)
        drawRect(
            Brush.linearGradient(
                colors = listOf(category.deep, Color(0xFF111522), category.deep),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(w, h)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(category.glow.copy(alpha = 0.68f), category.glow.copy(alpha = 0.18f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(w * 0.76f, h * 0.24f),
                radius = short * 0.9f
            ),
            radius = short * 0.9f,
            center = androidx.compose.ui.geometry.Offset(w * 0.76f, h * 0.24f)
        )
        val faint = Color.White.copy(alpha = 0.13f)
        when (index) {
            0 -> {
                drawCircle(category.accent.copy(alpha = 0.30f), short * 0.34f, androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.62f))
                drawCircle(category.glow.copy(alpha = 0.34f), short * 0.23f, androidx.compose.ui.geometry.Offset(w * 0.54f, h * 0.34f))
                drawCircle(Color(0xFFFFD78A).copy(alpha = 0.72f), short * 0.075f, androidx.compose.ui.geometry.Offset(w * 0.54f, h * 0.34f))
                for (i in 0..5) {
                    val x = w * (0.10f + i * 0.12f)
                    drawLine(category.accent.copy(alpha = 0.22f), androidx.compose.ui.geometry.Offset(x, h * 0.12f), androidx.compose.ui.geometry.Offset(x + short * 0.18f, h * 0.02f), short * 0.012f)
                }
            }
            1 -> {
                rotate(-18f, androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.46f)) {
                    drawRoundRect(category.accent.copy(alpha = 0.20f), androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.12f), androidx.compose.ui.geometry.Size(w * 0.5f, h * 0.58f), androidx.compose.ui.geometry.CornerRadius(short * 0.12f))
                    drawRoundRect(category.accent.copy(alpha = 0.50f), androidx.compose.ui.geometry.Offset(w * 0.53f, h * 0.23f), androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.36f), androidx.compose.ui.geometry.CornerRadius(short * 0.08f), style = Stroke(short * 0.018f))
                }
                drawCircle(category.accent.copy(alpha = 0.8f), short * 0.045f, androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.28f))
                drawCircle(faint, short * 0.018f, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.18f))
            }
            2 -> {
                val p = Path().apply {
                    moveTo(w * 0.46f, h * 0.08f); lineTo(w * 0.46f, h * 0.88f)
                    moveTo(w * 0.22f, h * 0.48f); lineTo(w * 0.76f, h * 0.48f)
                    moveTo(w * 0.30f, h * 0.20f); lineTo(w * 0.70f, h * 0.76f)
                    moveTo(w * 0.70f, h * 0.20f); lineTo(w * 0.30f, h * 0.76f)
                }
                drawPath(p, category.accent.copy(alpha = 0.58f), style = Stroke(short * 0.014f))
                drawCircle(category.accent.copy(alpha = 0.24f), short * 0.18f, androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.48f))
                drawCircle(category.accent, short * 0.035f, androidx.compose.ui.geometry.Offset(w * 0.52f, h * 0.48f))
            }
            3 -> {
                for (i in 0..4) {
                    val path = Path()
                    val y = h * (0.20f + i * 0.14f)
                    path.moveTo(w * 0.12f, y)
                    path.cubicTo(w * 0.34f, y - h * 0.24f, w * 0.58f, y + h * 0.24f, w * 0.88f, y - h * 0.02f)
                    drawPath(path, category.accent.copy(alpha = 0.18f + i * 0.09f), style = Stroke(short * 0.018f))
                }
                drawCircle(category.accent.copy(alpha = 0.52f), short * 0.10f, androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.48f))
            }
            4 -> {
                for (row in 0..4) for (col in 0..7) {
                    val radius = if ((row + col) % 3 == 0) short * 0.025f else short * 0.012f
                    drawCircle(category.accent.copy(alpha = if ((row + col) % 3 == 0) 0.78f else 0.30f), radius, androidx.compose.ui.geometry.Offset(w * (0.12f + col * 0.105f), h * (0.20f + row * 0.15f)))
                }
                drawCircle(category.glow.copy(alpha = 0.32f), short * 0.20f, androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.50f), style = Stroke(short * 0.018f))
            }
            5 -> {
                for (i in 1..4) drawCircle(category.accent.copy(alpha = 0.10f + i * 0.055f), short * (0.10f + i * 0.085f), androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.46f), style = Stroke(short * 0.026f))
                drawCircle(category.accent.copy(alpha = 0.34f), short * 0.12f, androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.46f))
            }
            6 -> {
                val cube = Path().apply {
                    moveTo(w * 0.66f, h * 0.16f); lineTo(w * 0.88f, h * 0.29f); lineTo(w * 0.66f, h * 0.42f); lineTo(w * 0.44f, h * 0.29f); close()
                    moveTo(w * 0.44f, h * 0.29f); lineTo(w * 0.44f, h * 0.58f); lineTo(w * 0.66f, h * 0.72f); lineTo(w * 0.66f, h * 0.42f)
                    moveTo(w * 0.66f, h * 0.42f); lineTo(w * 0.88f, h * 0.29f); lineTo(w * 0.88f, h * 0.58f); lineTo(w * 0.66f, h * 0.72f)
                }
                drawPath(cube, category.accent.copy(alpha = 0.72f), style = Stroke(short * 0.018f))
                drawPath(Path().apply { moveTo(w * 0.66f, h * 0.16f); lineTo(w * 0.66f, h * 0.42f) }, faint, style = Stroke(short * 0.012f))
            }
            7 -> {
                rotate(-9f, androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.48f)) {
                    for (i in 0..2) drawRoundRect(category.accent.copy(alpha = 0.16f + i * 0.12f), androidx.compose.ui.geometry.Offset(w * (0.40f + i * 0.07f), h * (0.22f + i * 0.08f)), androidx.compose.ui.geometry.Size(w * 0.43f, h * 0.12f), androidx.compose.ui.geometry.CornerRadius(short * 0.035f), style = Stroke(short * 0.014f))
                }
                drawLine(category.accent.copy(alpha = 0.75f), androidx.compose.ui.geometry.Offset(w * 0.20f, h * 0.72f), androidx.compose.ui.geometry.Offset(w * 0.48f, h * 0.72f), short * 0.018f)
            }
            8 -> {
                for (i in 0..10) {
                    val x = w * (0.12f + i * 0.075f)
                    val height = h * (0.12f + ((i * 7) % 5) * 0.085f)
                    drawRoundRect(category.accent.copy(alpha = 0.45f + (i % 3) * 0.15f), androidx.compose.ui.geometry.Offset(x, h * 0.52f - height / 2f), androidx.compose.ui.geometry.Size(short * 0.025f, height), androidx.compose.ui.geometry.CornerRadius(short * 0.02f))
                }
                drawLine(category.accent.copy(alpha = 0.30f), androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.52f), androidx.compose.ui.geometry.Offset(w * 0.90f, h * 0.52f), short * 0.008f)
            }
            else -> {
                drawCircle(category.accent.copy(alpha = 0.48f), short * 0.26f, androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.44f), style = Stroke(short * 0.016f))
                drawCircle(category.accent.copy(alpha = 0.25f), short * 0.16f, androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.44f), style = Stroke(short * 0.016f))
                drawCircle(category.accent.copy(alpha = 0.80f), short * 0.035f, androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.44f))
                drawLine(faint, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.22f), androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.22f), short * 0.012f)
                drawLine(faint, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.30f), androidx.compose.ui.geometry.Offset(w * 0.31f, h * 0.30f), short * 0.012f)
            }
        }
    }
}

@Composable
private fun CategoryTile(category: EffectCategory, modifier: Modifier = Modifier) {
    Box(
        modifier.height(124.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(17.dp))
    ) {
        CategoryArtwork(category, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x22070A12),
                    0.34f to Color(0x3D070A12),
                    0.68f to Color(0x99070A12),
                    1f to Color(0xF2070A12)
                )
            )
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape).background(category.accent)
            )
            Text(
                "COLLECTION",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .padding(10.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0x55070A12))
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("↗", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            category.title,
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.84f)
                .padding(start = 11.dp, end = 4.dp, bottom = 12.dp),
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.55.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
